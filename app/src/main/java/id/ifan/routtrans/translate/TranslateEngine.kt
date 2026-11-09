package id.ifan.routtrans.translate

import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentifier
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.concurrent.TimeUnit

/**
 * Pembungkus MLKit yang dipakai provider.
 *
 * Semua pemanggilan di sini memblokir thread pemanggil; provider menerima
 * panggilan di thread Binder, jadi aman. Aplikasi pemanggil (payload) tetap
 * memakai thread latar sendiri sehingga UI target tidak ikut tertahan.
 */
object TranslateEngine {

    private const val TRANSLATE_TIMEOUT_SECONDS = 15L
    private const val DETECT_TIMEOUT_SECONDS = 10L
    private const val MODEL_TIMEOUT_MINUTES = 2L
    private const val AUTO_DOWNLOAD_TTL_MS = 5 * 60_000L

    /** Hasil deteksi dipakai ulang sebentar agar bahasa aplikasi tidak berubah tiap batch. */
    private const val DETECT_TTL_MS = 30_000L

    data class Result(
        val texts: List<String?>,
        val detected: String?,
        val error: String?,
    )

    private val lock = Any()
    private val translators = HashMap<String, Translator>()
    private val downloadAttempts = HashMap<String, Long>()
    private val detections = HashMap<String, Pair<String, Long>>()
    private val identifier: LanguageIdentifier by lazy { LanguageIdentification.getClient() }

    /** Mengunduh model sumber dan tujuan. Dipanggil dari tombol "Siapkan model". */
    fun prepare(from: String, to: String) {
        val source = if (from == LanguageCatalog.AUTO) defaultSource(to) else from
        download(source, to)
    }

    /**
     * @param client nama paket pemanggil. Deteksi bahasa disimpan per aplikasi
     *   supaya dua aplikasi berbahasa berbeda tidak saling memengaruhi.
     */
    fun translate(from: String, to: String, texts: List<String>, client: String = ""): Result {
        if (texts.isEmpty()) return Result(emptyList(), null, null)
        val target = LanguageCatalog.normalize(to)
        if (target.isEmpty()) return fail(texts, from, to, "Bahasa tujuan tidak valid")

        val detected = if (from == LanguageCatalog.AUTO) detect(texts, target, client) else null
        val source = detected ?: LanguageCatalog.normalize(from)
        if (source.isEmpty()) return fail(texts, from, to, "Bahasa sumber tidak valid")

        // Sumber dan tujuan sama: tidak ada yang perlu diterjemahkan.
        if (source == target) return Result(texts, detected, null)

        val translator = try {
            translator(source, target)
        } catch (e: Exception) {
            return fail(texts, source, target, "Bahasa tidak didukung: ${e.message}")
        }

        val results = ArrayList<String?>(texts.size)
        var error: String? = null
        var translated = 0
        for (text in texts) {
            if (text.isBlank()) {
                results.add(text)
                continue
            }
            try {
                val out = translateWithModel(translator, source, target, text)
                results.add(out)
                translated++
                TranslationStore.add(source, target, text, out, null)
            } catch (e: Exception) {
                results.add(text)
                error = friendlyError(e)
                TranslationStore.add(source, target, text, null, error)
            }
        }

        // Kalau semua gagal dengan galat yang sama, cukup laporkan sekali.
        val message = if (translated == 0 && results.isNotEmpty()) error else null
        return Result(results, detected, message)
    }

    /**
     * Mendeteksi bahasa dari gabungan teks. Satu deteksi per batch jauh lebih
     * akurat daripada memeriksa potongan pendek satu per satu, dan hasilnya
     * dipakai ulang selama 30 detik per aplikasi agar tidak berubah-ubah.
     */
    private fun detect(texts: List<String>, target: String, client: String): String {
        val joined = texts.joinToString("\n").take(1000)
        val key = "$client|$target"
        val now = System.currentTimeMillis()
        synchronized(lock) {
            detections[key]?.let { (code, at) ->
                if (now - at < DETECT_TTL_MS) return code
            }
        }

        val raw = try {
            Tasks.await(identifier.identifyLanguage(joined), DETECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (e: Exception) {
            "und"
        }

        val supported = LanguageCatalog.supportedCodes()
        var code = LanguageCatalog.normalize(raw)
        if (code.isEmpty() || code == "und" || code !in supported) {
            code = defaultSource(target)
        }
        synchronized(lock) {
            if (detections.size > 32) detections.clear()
            detections[key] = code to now
        }
        return code
    }

    /** Cadangan saat deteksi gagal: bahasa HP, lalu Inggris (atau Indonesia bila tujuan Inggris). */
    private fun defaultSource(target: String): String {
        val supported = LanguageCatalog.supportedCodes()
        val device = LanguageCatalog.normalize(LanguageCatalog.deviceLanguage())
        if (device in supported && device != target) return device
        if ("en" in supported && target != "en") return "en"
        return "id"
    }

    /**
     * Menerjemahkan satu teks. Kalau model belum ada (umum saat sumber
     * "auto" mendeteksi bahasa baru), unduhan dicoba sekali lalu diulang.
     */
    private fun translateWithModel(
        translator: Translator,
        source: String,
        target: String,
        text: String,
    ): String = try {
        Tasks.await(translator.translate(text), TRANSLATE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    } catch (e: Exception) {
        if (!downloadIfAllowed(source, target)) throw e
        Tasks.await(translator.translate(text), TRANSLATE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    private fun downloadIfAllowed(source: String, target: String): Boolean {
        val key = "$source>$target"
        val now = System.currentTimeMillis()
        synchronized(lock) {
            val last = downloadAttempts[key]
            if (last != null && now - last < AUTO_DOWNLOAD_TTL_MS) return false
            downloadAttempts[key] = now
        }
        return try {
            download(source, target)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun download(source: String, target: String) {
        Tasks.await(
            translator(source, target).downloadModelIfNeeded(DownloadConditions.Builder().build()),
            MODEL_TIMEOUT_MINUTES,
            TimeUnit.MINUTES,
        )
    }

    private fun translator(source: String, target: String): Translator =
        synchronized(lock) {
            val key = "$source>$target"
            translators.getOrPut(key) {
                Translation.getClient(
                    TranslatorOptions.Builder()
                        .setSourceLanguage(source)
                        .setTargetLanguage(target)
                        .build()
                )
            }
        }

    private fun fail(texts: List<String>, from: String, to: String, message: String): Result {
        texts.forEach { TranslationStore.add(from, to, it, null, message) }
        return Result(texts, null, message)
    }

    private fun friendlyError(e: Exception): String {
        val detail = e.message?.takeIf { it.isNotBlank() }
        return if (detail != null) {
            "Terjemahan gagal: $detail. Pastikan model sudah diunduh lewat tombol Siapkan model."
        } else {
            "Terjemahan gagal. Pastikan model sudah diunduh lewat tombol Siapkan model."
        }
    }
}
