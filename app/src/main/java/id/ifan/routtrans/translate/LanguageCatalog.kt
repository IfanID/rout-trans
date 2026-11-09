package id.ifan.routtrans.translate

import android.content.Context
import com.google.mlkit.nl.translate.TranslateLanguage
import java.util.Locale

/**
 * Daftar bahasa diambil dari konstanta TranslateLanguage milik MLKit, jadi
 * hanya bahasa yang benar-benar didukung mesin terjemahan yang tampil.
 *
 * Selain bahasa konkret, daftar sumber mendapat satu pilihan "auto":
 * payload mengirim kode itu apa adanya dan provider yang mendeteksi bahasa
 * teksnya. Pilihan itu hanya untuk bahasa sumber, bukan tujuan.
 */
object LanguageCatalog {

    const val AUTO = "auto"

    /**
     * Cadangan kalau refleksi gagal (mis. kelas diubah antar versi MLKit).
     * Isinya sengaja pendek; daftar lengkap tetap dari TranslateLanguage.
     */
    private val FALLBACK = listOf(
        "en", "id", "ko", "ms", "ar", "de", "es", "fr", "hi", "it", "ja",
        "nl", "pl", "pt", "ru", "th", "tr", "uk", "vi", "zh",
    )

    private val LANGUAGE_CODE = Regex("[a-zA-Z]{2,3}(-[a-zA-Z]{2,4})?")

    private val codes: Set<String> by lazy { readCodes() }

    /** Daftar sumber: "Deteksi otomatis" di paling atas, lalu bahasa lain. */
    fun sources(context: Context): List<LanguageOption> {
        val options = targets(context)
        return listOf(LanguageOption(AUTO, AUTO)) + options
    }

    /** Daftar tujuan: tanpa pilihan "auto" karena hasil terjemahan harus jelas. */
    fun targets(context: Context): List<LanguageOption> {
        val languageCodes = codes.ifEmpty { FALLBACK.toSet() }
        return languageCodes
            .map { LanguageOption(it, it) }
            .sortedBy { it.label(context).lowercase(Locale.getDefault()) }
    }

    /** Kode bahasa yang dikenali MLKit, dipakai juga oleh TranslateEngine. */
    fun supportedCodes(): Set<String> = codes.ifEmpty { FALLBACK.toSet() }

    private fun readCodes(): Set<String> {
        val found = sortedSetOf<String>()
        try {
            for (field in TranslateLanguage::class.java.declaredFields) {
                if (field.type != String::class.java) continue
                field.isAccessible = true
                val value = field.get(null) as? String ?: continue
                if (isLanguageCode(value)) found.add(value)
            }
        } catch (ignored: Throwable) {
            // Pakai daftar cadangan di pemanggil.
        }
        return found
    }

    /** Hanya kode berbentuk "xx" atau "xx-YY"; konstanta lain seperti "auto" dibuang. */
    private fun isLanguageCode(code: String): Boolean =
        LANGUAGE_CODE.matches(code) &&
            !code.equals("auto", ignoreCase = true) &&
            !code.equals("und", ignoreCase = true)

    fun indexOf(options: List<LanguageOption>, code: String): Int =
        options.indexOfFirst { it.code == code }

    /** Memilih bahasa tersimpan, lalu bahasa HP, lalu Inggris sebagai cadangan. */
    fun resolve(options: List<LanguageOption>, code: String): Int {
        indexOf(options, normalize(code)).takeIf { it >= 0 }?.let { return it }
        indexOf(options, normalize(deviceLanguage())).takeIf { it >= 0 }?.let { return it }
        indexOf(options, "en").takeIf { it >= 0 }?.let { return it }
        return 0
    }

    /**
     * Android masih bisa memberi kode lama dari Locale ("in" untuk Indonesia,
     * "iw" untuk Ibrani, "ji" untuk Yiddish), sedangkan MLKit memakai kode baru.
     */
    fun normalize(code: String?): String = when (code) {
        null -> ""
        "in" -> "id"
        "iw" -> "he"
        "ji" -> "yi"
        else -> code
    }

    /** Kode bahasa dari locale HP, dipakai sebagai nilai awal sumber terjemahan. */
    fun deviceLanguage(): String = Locale.getDefault().language.ifEmpty { "en" }
}
