package id.ifan.routtrans.payload.view

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.method.PasswordTransformationMethod
import android.util.Log
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

    // Statistik ringan untuk log: membantu memastikan pemindaian benar-benar
    // menemukan teks pada aplikasi target.
    private var textViews = 0
    private var candidates = 0
    private var requested = 0
    private var windows = 0
    private var lastReport = 0L

    fun scan(roots: List<View>) {
        batch = LinkedHashMap()
        textViews = 0
        candidates = 0
        requested = 0
        windows = roots.size
        roots.forEach { scanRoot(it) }
        submit()
        report()
    }

    /** Penelusuran memakai stack, bukan rekursi: pohon View aplikasi besar
     * (misalnya Compose) bisa sangat dalam dan rekursi berisiko
     * StackOverflowError yang mematikan proses target. */
    private fun scanRoot(root: View) {
        val stack = ArrayDeque<View>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val view = stack.removeLast()
            try {
                if (view.visibility != View.VISIBLE) continue
                if (view is TextView) inspect(view)
                if (view is ViewGroup) {
                    for (index in view.childCount - 1 downTo 0) {
                        stack.addLast(view.getChildAt(index))
                    }
                }
            } catch (t: Throwable) {
                // Satu View bermasalah tidak boleh menghentikan pemindaian.
                Log.w(TAG, "View dilewati", t)
            }
        }
    }

    /** Mencatat ringkasan pemindaian tiap beberapa detik agar tidak membanjiri log. */
    private fun report() {
        val now = SystemClock.uptimeMillis()
        if (now - lastReport < REPORT_MS) return
        lastReport = now
        Log.i(TAG, "Pindai: $textViews TextView, $candidates kandidat, $requested baru, $windows jendela")
    }

    private fun inspect(view: TextView) {
        textViews++
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
        candidates++

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
        requested++
    }

    private fun submit() {
        if (busy || batch.isEmpty()) return
        val requests = batch.values.flatten()
        val texts = batch.keys.toList()
        busy = true

        worker.execute {
            // Kegagalan satu batch (provider mati, timeout Binder) tidak boleh
            // membekukan pemindaian: busy harus selalu dilepas.
            val results = try {
                client.translate(config.from, config.to, texts)
            } catch (t: Throwable) {
                Log.w(TAG, "Batch terjemahan gagal", t)
                null
            }
            handler.post {
                try {
                    if (results == null) {
                        val now = SystemClock.uptimeMillis()
                        requests.forEach { attempted[keyOf(it.hint, it.source)] = now }
                    } else {
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
                    }
                } finally {
                    busy = false
                    batch = LinkedHashMap()
                }
            }
        }
    }

    private fun apply(view: TextView, hint: Boolean, source: String, translated: String) {
        val state = states[view] ?: return
        // Mengubah teks View milik aplikasi lain bisa memicu exception pada
        // View kustom. Jangan sampai exception itu sampai ke thread utama.
        try {
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
        } catch (t: Throwable) {
            Log.w(TAG, "Teks gagal dipasang", t)
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
        const val TAG = "RoutTrans"
        const val RETRY_MS = 60_000L

        /** Jeda antar ringkasan log pemindaian. */
        const val REPORT_MS = 5000L
    }
}
