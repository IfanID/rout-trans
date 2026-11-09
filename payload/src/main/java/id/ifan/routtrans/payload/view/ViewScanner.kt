package id.ifan.routtrans.payload.view

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import id.ifan.routtrans.payload.config.Config
import id.ifan.routtrans.payload.text.TextFilter
import id.ifan.routtrans.payload.translate.TranslatorClient
import java.util.WeakHashMap
import java.util.concurrent.Executors

/**
 * Memindai pohon View dan mengganti teks TextView dengan terjemahan.
 *
 * Teks asli disimpan per View supaya hasil terjemahan tidak diterjemahkan
 * ulang, dan supaya teks yang diubah aplikasi (misalnya daftar chat) dikenali
 * sebagai sumber baru. Teks pada EditText tidak disentuh; hanya hint-nya.
 */
class ViewScanner(
    private val config: Config,
    private val client: TranslatorClient,
) {

    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "RoutTransScan").apply { isDaemon = true }
    }

    private val states = WeakHashMap<TextView, ViewState>()
    private val attempted = HashMap<String, Long>()
    private var busy = false
    private var batch = LinkedHashMap<String, MutableList<Request>>()

    fun scan(root: View) {
        batch = LinkedHashMap()
        walk(root)
        submit()
    }

    private fun walk(view: View) {
        if (view.visibility != View.VISIBLE) return
        if (view is TextView) inspect(view)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) walk(view.getChildAt(index))
        }
    }

    private fun inspect(view: TextView) {
        if (view.transformationMethod is PasswordTransformationMethod) return
        val state = states.getOrPut(view) { ViewState() }
        // Isi EditText adalah ketikan pengguna; hanya hint yang diterjemahkan.
        if (view !is EditText) inspectText(view, state)
        inspectHint(view, state)
    }

    private fun inspectText(view: TextView, state: ViewState) {
        val current = view.text?.toString()?.trim().orEmpty()
        if (current.isEmpty() || current == state.textTranslated) return
        if (current != state.textSource) {
            state.textSource = current
            state.textTranslated = null
        }
        request(view, hint = false, source = current)
    }

    private fun inspectHint(view: TextView, state: ViewState) {
        val current = view.hint?.toString()?.trim().orEmpty()
        if (current.isEmpty() || current == state.hintTranslated) return
        if (current != state.hintSource) {
            state.hintSource = current
            state.hintTranslated = null
        }
        request(view, hint = true, source = current)
    }

    private fun request(view: TextView, hint: Boolean, source: String) {
        if (!TextFilter.shouldTranslate(source, config.minLength, config.maxLength)) return

        // Hasil yang sudah tersimpan bisa langsung dipasang tanpa menunggu worker.
        client.cached(config.from, config.to, source)?.let { translated ->
            apply(view, hint, source, translated)
            return
        }

        val key = keyOf(hint, source)
        val now = SystemClock.uptimeMillis()
        val last = attempted[key]
        if (last != null && now - last < RETRY_MS) return
        if (batch[source]?.any { it.hint == hint && it.view === view } == true) return

        batch.getOrPut(source) { mutableListOf() }.add(Request(view, hint, source))
    }

    private fun submit() {
        if (busy || batch.isEmpty()) return
        val requests = batch.values.flatten()
        val texts = batch.keys.toList()
        busy = true

        worker.execute {
            val results = client.translate(config.from, config.to, texts)
            handler.post {
                val bySource = texts.indices.associate { texts[it] to results[it] }
                for (request in requests) {
                    val translated = bySource[request.source]
                    if (translated != null) {
                        attempted.remove(keyOf(request.hint, request.source))
                        apply(request.view, request.hint, request.source, translated)
                    } else {
                        attempted[keyOf(request.hint, request.source)] = SystemClock.uptimeMillis()
                    }
                }
                busy = false
                batch = LinkedHashMap()
            }
        }
    }

    private fun apply(view: TextView, hint: Boolean, source: String, translated: String) {
        val state = states[view] ?: return
        if (hint) {
            if (view.hint?.toString()?.trim() != source) return
            state.hintSource = source
            state.hintTranslated = translated
            view.hint = translated
        } else {
            if (view.text?.toString()?.trim() != source) return
            state.textSource = source
            state.textTranslated = translated
            view.text = translated
        }
    }

    private fun keyOf(hint: Boolean, source: String): String =
        (if (hint) "h:" else "t:") + source

    private class ViewState {
        var textSource: String? = null
        var textTranslated: String? = null
        var hintSource: String? = null
        var hintTranslated: String? = null
    }

    private data class Request(
        val view: TextView,
        val hint: Boolean,
        val source: String,
    )

    private companion object {
        const val RETRY_MS = 60_000L
    }
}
