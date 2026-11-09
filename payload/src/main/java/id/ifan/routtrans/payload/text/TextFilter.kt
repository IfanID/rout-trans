package id.ifan.routtrans.payload.text

/**
 * Menentukan teks mana yang layak diterjemahkan.
 *
 * Tujuannya menghindari panggilan provider untuk teks yang jelas bukan bahasa
 * manusia: angka, simbol, alamat web, email, dan placeholder format.
 */
object TextFilter {

    private val LETTER = Regex("\\p{L}")
    private val URL = Regex("^(https?://|www\\.)\\S+$", RegexOption.IGNORE_CASE)
    private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
    private val PLACEHOLDER = Regex("^\\{[^{}]+}$")

    fun shouldTranslate(text: String, minLength: Int, maxLength: Int): Boolean {
        val value = text.trim()
        if (value.length < minLength || value.length > maxLength) return false
        if (!LETTER.containsMatchIn(value)) return false
        if (URL.matches(value) || EMAIL.matches(value)) return false
        if (PLACEHOLDER.matches(value)) return false
        return true
    }
}
