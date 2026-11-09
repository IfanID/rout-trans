package id.ifan.routtrans.payload.translate

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import id.ifan.routtrans.payload.text.TranslationCache

/**
 * Klien provider terjemahan milik RoutTrans.
 *
 * Payload tidak memuat MLKit, jadi teks dikirim ke proses RoutTrans lewat
 * ContentProvider.call(). Panggilan dilakukan di thread latar pemindai,
 * sehingga UI aplikasi target tidak tertahan.
 */
class TranslatorClient(private val context: Context) {

    private val cache = TranslationCache()

    fun cached(from: String, to: String, text: String): String? = cache.get(from, to, text)

    /** Mengembalikan hasil sesuai urutan masukan; null berarti gagal. */
    fun translate(from: String, to: String, texts: List<String>): List<String?> {
        val results = arrayOfNulls<String>(texts.size)
        val request = ArrayList<String>()
        val indexes = ArrayList<Int>()

        texts.forEachIndexed { index, text ->
            val hit = cache.get(from, to, text)
            if (hit != null) {
                results[index] = hit
            } else {
                request.add(text)
                indexes.add(index)
            }
        }
        if (request.isEmpty()) return results.toList()

        val translated = callProvider(from, to, request)
        if (translated != null) {
            request.indices.forEach { position ->
                val value = translated.getOrNull(position) ?: return@forEach
                results[indexes[position]] = value
                cache.put(from, to, request[position], value)
            }
        }
        return results.toList()
    }

    /**
     * Melapor ke pengendali bahwa payload benar-benar terpasang (atau gagal).
     * Ini yang membuat daftar aplikasi bisa menampilkan status injeksi,
     * bukan sekadar menebak dari konfigurasi.
     */
    fun reportInjected(from: String, to: String, error: String? = null) {
        val extras = Bundle().apply {
            putString("package", context.packageName)
            putString("process", processName())
            putString("from", from)
            putString("to", to)
            if (error != null) putString("error", error.take(300))
        }
        try {
            context.contentResolver.call(Uri.parse(AUTHORITY), "injected", null, extras)
        } catch (e: Exception) {
            Log.w(TAG, "Laporan injeksi gagal dikirim", e)
        }
    }

    private fun callProvider(from: String, to: String, texts: List<String>): List<String?>? {
        val extras = Bundle().apply {
            putString("from", from)
            putString("to", to)
            putString("package", context.packageName)
            putStringArray("texts", texts.toTypedArray())
        }

        var lastError: Exception? = null
        // Provider bisa belum siap tepat setelah proses target dimulai; coba
        // beberapa kali dengan jeda pendek sebelum menyerah.
        for (attempt in 0 until ATTEMPTS) {
            try {
                val bundle = context.contentResolver.call(
                    Uri.parse(AUTHORITY),
                    "translate",
                    null,
                    extras,
                )
                val output = bundle?.getStringArray("texts")
                if (output != null && output.size == texts.size) {
                    return output.toList()
                }
                bundle?.getString("error")?.let { Log.w(TAG, it) }
            } catch (e: Exception) {
                lastError = e
            }
            SystemClock.sleep(RETRY_DELAY_MS * (attempt + 1))
        }
        lastError?.let { Log.w(TAG, "Provider terjemahan tidak bisa dihubungi", it) }
        return null
    }

    private fun processName(): String = try {
        Application.getProcessName()
    } catch (e: Exception) {
        ""
    }

    private companion object {
        const val AUTHORITY = "content://id.ifan.routtrans.translate"
        const val TAG = "RoutTrans"
        const val ATTEMPTS = 3
        const val RETRY_DELAY_MS = 200L
    }
}
