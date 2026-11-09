package id.ifan.routtrans.translate

/**
 * Catatan terjemahan terakhir supaya pengguna bisa memastikan payload benar
 * benar bekerja di aplikasi target. Isinya hanya di memori: cukup untuk
 * pemeriksaan saat aplikasi masih berjalan, tanpa menulis apa pun ke disk.
 */
object TranslationStore {

    const val MAX_ENTRIES = 200

    data class Entry(
        val time: Long,
        val from: String,
        val to: String,
        val source: String,
        val result: String?,
        val error: String?,
    ) {
        val ok: Boolean get() = error == null
    }

    private val entries = ArrayDeque<Entry>()
    private var total = 0

    fun add(from: String, to: String, source: String, result: String?, error: String?) {
        synchronized(entries) {
            entries.addFirst(Entry(System.currentTimeMillis(), from, to, source, result, error))
            total++
            while (entries.size > MAX_ENTRIES) entries.removeLast()
        }
    }

    /** Terbaru lebih dulu. */
    fun recent(): List<Entry> = synchronized(entries) { entries.toList() }

    fun totalCount(): Int = synchronized(entries) { total }

    fun lastTime(): Long? = synchronized(entries) { entries.firstOrNull()?.time }
}
