package id.ifan.routtrans.payload.text

/**
 * Cache hasil terjemahan di proses aplikasi target.
 *
 * Pemindaian berulang menemukan teks yang sama berkali-kali (misalnya label
 * tombol), jadi hasil yang sudah ada dipakai ulang tanpa memanggil provider.
 */
class TranslationCache(private val maxEntries: Int = 600) {

    private val entries = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean =
            size > maxEntries
    }

    fun get(from: String, to: String, text: String): String? =
        synchronized(entries) { entries[key(from, to, text)] }

    fun put(from: String, to: String, text: String, translated: String) {
        synchronized(entries) { entries[key(from, to, text)] = translated }
    }

    fun size(): Int = synchronized(entries) { entries.size }

    fun clear() {
        synchronized(entries) { entries.clear() }
    }

    private fun key(from: String, to: String, text: String): String = "$from>$to>$text"
}
