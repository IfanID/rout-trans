package id.ifan.routtrans.xposed

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import java.util.concurrent.Executors

/**
 * Penghubung hook dengan aplikasi RoutTrans.
 *
 * Hook berjalan di proses aplikasi target, jadi teks dikirim ke provider
 * RoutTrans. Hasilnya di-cache agar penggambaran berikutnya langsung memakai
 * teks Indonesia tanpa menunggu.
 */
object Hub {

    data class Config(val enabled: Boolean, val from: String, val to: String)

    private const val AUTHORITY = "content://id.ifan.routrans.translate"
    private const val TAG = "RoutTransHook"
    private const val BATCH_DELAY_MS = 60L

    private val cache = HashMap<String, String>()
    private val queue = LinkedHashSet<String>()
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "RoutTransHook").apply { isDaemon = true }
    }

    private var scheduled = false
    private var context: Context? = null
    private var from = "auto"
    private var to = "id"

    /** Menanyakan konfigurasi untuk satu paket; null berarti tidak dipilih. */
    fun config(ctx: Context, pkg: String): Config? = try {
        val result = ctx.contentResolver.call(
            Uri.parse(AUTHORITY),
            "config",
            null,
            Bundle().apply { putString("package", pkg) },
        )
        if (result?.getBoolean("enabled") == true) {
            Config(
                enabled = true,
                from = result.getString("from") ?: "auto",
                to = result.getString("to") ?: "id",
            )
        } else {
            null
        }
    } catch (t: Throwable) {
        Log.w(TAG, "Konfigurasi tidak bisa dibaca", t)
        null
    }

    fun prepare(ctx: Context, config: Config) {
        context = ctx.applicationContext ?: ctx
        from = config.from
        to = config.to
    }

    /** Hasil cache, atau null sambil menjadwalkan penerjemahan. */
    fun translateOrRequest(text: String): String? {
        synchronized(cache) {
            cache[text]?.let { return it }
        }
        schedule(text)
        return null
    }

    private fun schedule(text: String) {
        synchronized(queue) {
            queue.add(text)
            if (scheduled) return
            scheduled = true
        }
        handler.postDelayed({ flush() }, BATCH_DELAY_MS)
    }

    private fun flush() {
        val texts = synchronized(queue) {
            val list = queue.toList()
            queue.clear()
            scheduled = false
            list
        }
        if (texts.isEmpty()) return
        val ctx = context ?: return

        worker.execute {
            try {
                val bundle = ctx.contentResolver.call(
                    Uri.parse(AUTHORITY),
                    "translate",
                    null,
                    Bundle().apply {
                        putString("from", from)
                        putString("to", to)
                        putString("package", ctx.packageName)
                        putStringArray("texts", texts.toTypedArray())
                    },
                )
                val output = bundle?.getStringArray("texts") ?: return@execute
                synchronized(cache) {
                    texts.forEachIndexed { index, text ->
                        val translated = output.getOrNull(index) ?: return@forEachIndexed
                        cache[text] = translated
                        // Supaya teks hasil tidak diterjemahkan ulang.
                        cache[translated] = translated
                    }
                }
                invalidateRoots()
            } catch (t: Throwable) {
                Log.w(TAG, "Terjemahan hook gagal", t)
            }
        }
    }

    /** Memaksa gambar ulang supaya teks yang baru diterjemahkan langsung tampil. */
    private fun invalidateRoots() {
        handler.post {
            try {
                val globalClass = Class.forName("android.view.WindowManagerGlobal")
                val instance = globalClass.getMethod("getInstance").invoke(null)
                val field = globalClass.getDeclaredField("mViews").apply { isAccessible = true }
                val views = field.get(instance)
                (views as? Collection<*>)?.forEach { view ->
                    (view as? View)?.postInvalidateOnAnimation()
                }
            } catch (t: Throwable) {
                // Refleksi bisa diblokir; penggambaran berikutnya tetap memakai
                // cache saat aplikasi menggambar ulang dengan sendirinya.
            }
        }
    }
}
