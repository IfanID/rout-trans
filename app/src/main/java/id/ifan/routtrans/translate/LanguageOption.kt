package id.ifan.routtrans.translate

import android.content.Context
import id.ifan.routtrans.R
import java.util.Locale

/** Bahasa yang bisa dipilih sebagai sumber atau tujuan terjemahan. */
data class LanguageOption(
    val code: String,
    private val fallbackLabel: String,
) {
    /**
     * Nama bahasa mengikuti bahasa HP. Kode "auto" memakai teks resource agar
     * ikut berubah saat bahasa antarmuka diganti.
     */
    fun label(context: Context): String {
        if (code == LanguageCatalog.AUTO) return context.getString(R.string.language_auto)
        val localized = Locale.forLanguageTag(code).getDisplayName(Locale.getDefault())
        return localized.ifBlank { fallbackLabel }
    }
}
