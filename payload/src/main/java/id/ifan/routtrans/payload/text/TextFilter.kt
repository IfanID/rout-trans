package id.ifan.routtrans.payload.text

/**
 * Menentukan teks mana yang layak diterjemahkan.
 *
 * Tujuannya menghindari panggilan provider untuk teks yang jelas bukan bahasa
 * manusia: angka, simbol, alamat web, email, dan placeholder format.
 *
 * Pemeriksaan ditulis tanpa Regex: mesin regex di Android (ICU) menolak
 * sebagian pola yang sah di JVM, dan kegagalan itu membuat seluruh kelas
 * gagal dimuat di proses aplikasi target.
 */
object TextFilter {

    fun shouldTranslate(text: String, minLength: Int, maxLength: Int): Boolean {
        val value = text.trim()
        if (value.length < minLength || value.length > maxLength) return false
        if (!hasLetter(value)) return false
        if (isUrl(value) || isEmail(value)) return false
        if (isPlaceholder(value)) return false
        return true
    }

    /** Huruf Unicode dihitung per code point agar aksara non-Latin ikut dikenali. */
    private fun hasLetter(value: String): Boolean {
        var index = 0
        while (index < value.length) {
            val code = value.codePointAt(index)
            if (Character.isLetter(code)) return true
            index += Character.charCount(code)
        }
        return false
    }

    private fun isUrl(value: String): Boolean {
        if (value.any { it.isWhitespace() }) return false
        val lower = value.lowercase()
        return lower.startsWith("http://") ||
            lower.startsWith("https://") ||
            lower.startsWith("www.")
    }

    private fun isEmail(value: String): Boolean {
        if (value.any { it.isWhitespace() }) return false
        val at = value.indexOf('@')
        if (at <= 0 || at == value.length - 1) return false
        if (value.indexOf('@', at + 1) >= 0) return false
        val dot = value.indexOf('.', at + 2)
        return dot > at && dot < value.length - 1
    }

    /** Placeholder seperti {0} atau {name} tidak perlu diterjemahkan. */
    private fun isPlaceholder(value: String): Boolean =
        value.length >= 3 &&
            value.startsWith("{") &&
            value.endsWith("}") &&
            value.indexOf('{', 1) < 0 &&
            value.indexOf('}') == value.length - 1
}
