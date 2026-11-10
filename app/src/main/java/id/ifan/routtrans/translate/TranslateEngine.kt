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
    private const val MODEL_TIMEOUT_MINUTES = 5L
    private const val AUTO_DOWNLOAD_TTL_MS = 5 * 60_000L

    /** Hasil deteksi dipakai ulang sebentar agar bahasa aplikasi tidak berubah tiap batch. */
    private const val DETECT_TTL_MS = 30_000L

    data class Result(
        val texts: List<String?>,
        val detected: String?,
        val error: String?,
    )

    private val lock = Any()
    /** Kode bahasa dengan aksara bukan Latin (aksara utama). */
    private val NON_LATIN = setOf(
        "ko", "zh", "ja", "ru", "uk", "bg", "sr", "mk", "be", "kk", "ky", "mn",
        "ar", "fa", "ur", "he", "th", "hi", "bn", "gu", "pa", "ta", "te", "kn",
        "ml", "mr", "ne", "si", "my", "km", "lo", "am", "ka", "hy", "el",
    )
    private val translators = HashMap<String, Translator>()
    private val downloadAttempts = HashMap<String, Long>()
    private val detections = HashMap<String, Pair<String, Long>>()
    private val identifier: LanguageIdentifier by lazy { LanguageIdentification.getClient() }

    /** Hasil unduhan satu model; [error] null berarti siap dipakai offline. */
    data class ModelResult(val source: String, val error: String?)

    /** Folder model MLKit di penyimpanan aplikasi. */
    fun modelsDir(context: android.content.Context): java.io.File =
        java.io.File(context.noBackupFilesDir, "com.google.mlkit.translate.models")

    /** Berkas model yang sudah terunduh, diurutkan menurut nama pasangan. */
    fun installedModels(context: android.content.Context): List<java.io.File> =
        modelsDir(context)
            .listFiles { file -> file.isFile && file.name.endsWith(".model") }
            ?.sortedBy { it.name }
            ?: emptyList()

    /**
     * Menghapus berkas model lalu membuang seluruh translator aktif.
     *
     * MLKit tidak menyediakan API penghapusan, jadi berkasnya dihapus langsung
     * dari folder aplikasi; translator lama harus dibuang agar tidak memakai
     * berkas yang sudah hilang.
     */
    fun deleteModels(files: List<java.io.File>) {
        files.forEach { file ->
            try {
                file.delete()
            } catch (ignored: Exception) {
                // Berkas yang gagal dihapus dibiarkan; pengguna bisa mencoba lagi.
            }
        }
        synchronized(lock) {
            translators.values.forEach { translator ->
                try {
                    translator.close()
                } catch (ignored: Exception) {
                    // Menutup translator yang sudah mati tidak perlu dilaporkan.
                }
            }
            translators.clear()
            downloadAttempts.clear()
        }
    }

    /**
     * Mengunduh beberapa model sumber menuju satu bahasa tujuan.
     *
     * MLKit tidak menyediakan unduhan paralel, jadi model diproses berurutan
     * dan setiap kegagalan dilaporkan sendiri agar satu bahasa gagal tidak
     * menggagalkan sisanya.
     */
    fun prepareMany(
        sources: List<String>,
        target: String,
        progress: (done: Int, total: Int, source: String) -> Unit,
    ): List<ModelResult> {
        val list = sources.map { LanguageCatalog.normalize(it) }
            .filter { it.isNotEmpty() && it != target }
            .distinct()
        val results = ArrayList<ModelResult>(list.size)
        list.forEachIndexed { index, source ->
            progress(index + 1, list.size, source)
            val error = try {
                download(source, target)
                null
            } catch (e: Exception) {
                // Dicatat juga ke log terjemahan supaya pengguna bisa membaca
                // ulang penyebabnya tanpa mengulang unduhan.
                describe(e).also { TranslationStore.add(source, target, "model", null, it) }
            }
            results.add(ModelResult(source, error))
        }
        return results
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
            // Nama pengguna dan istilah Latin tidak boleh diterjemahkan saat
            // sumbernya beraksara non-Latin; hasilnya selalu ngawur
            // ("kim" -> "Kim", "Air mata" -> tetap).
            if (isNonLatinSource(source) && isLatinOnly(text)) {
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
    private fun detect(texts: List<String>, target: String, client: String, fresh: Boolean = false): String {
        val joined = texts.joinToString("\n").take(1000)
        val key = "$client|$target"
        val now = System.currentTimeMillis()
        if (!fresh) {
            synchronized(lock) {
                detections[key]?.let { (code, at) ->
                    if (now - at < DETECT_TTL_MS) return code
                }
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
            if (!fresh) {
                if (detections.size > 32) detections.clear()
                detections[key] = code to now
            }
        }
        return code
    }

    /**
     * Mendeteksi bahasa dari gabungan teks.
     *
     * @param fresh true melewati cache 30 detik; dipakai layanan aksesibilitas
     *   karena isi layar bisa berganti bahasa lebih cepat dari itu.
     */
    fun detectLanguage(texts: List<String>, target: String, client: String, fresh: Boolean = false): String =
        detect(texts, target, if (fresh) "" else client, fresh)

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
        val conditions = DownloadConditions.Builder().build()
        try {
            Tasks.await(
                translator(source, target).downloadModelIfNeeded(conditions),
                MODEL_TIMEOUT_MINUTES,
                TimeUnit.MINUTES,
            )
        } catch (e: Exception) {
            // Model yang tersimpan setengah jadi bisa membuat unduhan selalu
            // gagal. Buang translator lama lalu coba sekali lagi dengan yang baru.
            resetTranslator(source, target)
            Tasks.await(
                translator(source, target).downloadModelIfNeeded(conditions),
                MODEL_TIMEOUT_MINUTES,
                TimeUnit.MINUTES,
            )
        }
    }

    private fun resetTranslator(source: String, target: String) {
        val key = "$source>$target"
        val old = synchronized(lock) { translators.remove(key) }
        try {
            old?.close()
        } catch (ignored: Exception) {
            // Menutup translator yang gagal tidak perlu dilaporkan.
        }
    }

    /**
     * Menggabungkan pesan dari seluruh rantai penyebab.
     *
     * MLKit membungkus kegagalan unduhan dengan ExecutionException yang
     * pesannya hanya "N out of N underlying tasks failed", sehingga penyebab
     * sebenarnya (mis. Play Services atau jaringan) ada di cause terdalam.
     */
    fun describe(error: Throwable): String {
        val chain = ArrayList<Throwable>()
        var current: Throwable? = error
        while (current != null && chain.size < 8) {
            chain.add(current)
            val next = current.cause
            if (next === current) break
            current = next
        }
        return chain.asReversed().joinToString(" <- ") { item ->
            val detail = item.message?.trim().orEmpty()
            if (detail.isEmpty()) item.javaClass.simpleName
            else "${item.javaClass.simpleName}: $detail"
        }
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

    /**
     * Bahasa sumber beraksara non-Latin. Dipakai untuk melewati teks Latin
     * seperti nama pengguna yang tidak perlu diterjemahkan.
     */
    private fun isNonLatinSource(code: String): Boolean = code in NON_LATIN

    /** Teks hanya berisi huruf Latin, angka, dan tanda baca. */
    private fun isLatinOnly(text: String): Boolean {
        var letter = false
        for (ch in text) {
            if (!ch.isLetter()) continue
            letter = true
            val block = Character.UnicodeBlock.of(ch)
            if (block != Character.UnicodeBlock.BASIC_LATIN &&
                block != Character.UnicodeBlock.LATIN_1_SUPPLEMENT &&
                block != Character.UnicodeBlock.LATIN_EXTENDED_A &&
                block != Character.UnicodeBlock.LATIN_EXTENDED_B
            ) {
                return false
            }
        }
        return letter
    }

    private fun friendlyError(e: Exception): String {
        val detail = describe(e).takeIf { it.isNotBlank() }
        return if (detail != null) {
            "Terjemahan gagal: $detail. Pastikan model sudah diunduh lewat tombol Siapkan model."
        } else {
            "Terjemahan gagal. Pastikan model sudah diunduh lewat tombol Siapkan model."
        }
    }
}
