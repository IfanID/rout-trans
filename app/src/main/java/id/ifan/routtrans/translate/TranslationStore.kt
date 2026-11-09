package id.ifan.routtrans.translate

import android.content.Context
import id.ifan.routtrans.data.LogFiles
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

/**
 * Catatan terjemahan terakhir supaya pengguna bisa memastikan payload benar
 * benar bekerja di aplikasi target.
 *
 * Entri disimpan juga ke `files/translations.log` di penyimpanan aplikasi
 * (Android/data/id.ifan.routtrans/files) agar tidak hilang setiap proses
 * RoutTrans dimulai ulang, misalnya saat aplikasi target baru dibuka.
 */
object TranslationStore {

    const val MAX_ENTRIES = 200
    const val FILE_NAME = "translations.log"

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

    /** Berkas log; null selama [init] belum dipanggil. */
    private var file: File? = null

    /** Jumlah baris di berkas, dipakai untuk memutuskan penulisan ulang. */
    private var lines = 0

    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "RoutTransLog").apply { isDaemon = true }
    }

    /** Memuat log lama. Aman dipanggil berulang dari proses yang sama. */
    fun init(context: Context) {
        if (file != null) return
        val target = File(LogFiles.dir(context), FILE_NAME)
        target.parentFile?.mkdirs()
        file = target
        val loaded = read(target)
        synchronized(entries) {
            // Berkas menyimpan urutan lama -> baru, sedangkan daftar di memori
            // menyimpan yang terbaru lebih dulu.
            loaded.takeLast(MAX_ENTRIES).forEach { entries.addLast(it) }
            lines = loaded.size
        }
    }

    fun add(from: String, to: String, source: String, result: String?, error: String?) {
        val entry = Entry(System.currentTimeMillis(), from, to, source, result, error)
        synchronized(entries) {
            entries.addFirst(entry)
            total++
            while (entries.size > MAX_ENTRIES) entries.removeLast()
        }
        persist(entry)
    }

    /** Terbaru lebih dulu. */
    fun recent(): List<Entry> = synchronized(entries) { entries.toList() }

    fun totalCount(): Int = synchronized(entries) { total }

    fun lastTime(): Long? = synchronized(entries) { entries.firstOrNull()?.time }

    private fun persist(entry: Entry) {
        val target = file ?: return
        writer.execute {
            try {
                synchronized(entries) {
                    FileOutputStream(target, true).use { output ->
                        output.write((encode(entry) + "\n").toByteArray(Charsets.UTF_8))
                    }
                    lines++
                    // Berkas hanya menyimpan entri terbaru; catatan lama
                    // dibuang supaya tidak menumpuk dan membingungkan.
                    if (lines > MAX_ENTRIES) rewrite(target)
                }
            } catch (e: Exception) {
                // Log tidak boleh mengganggu terjemahan; kegagalan tulis
                // diabaikan.
            }
        }
    }

    private fun rewrite(target: File) {
        val snapshot = entries.toList().asReversed()
        FileOutputStream(target, false).use { output ->
            snapshot.forEach { output.write((encode(it) + "\n").toByteArray(Charsets.UTF_8)) }
        }
        lines = snapshot.size
    }

    private fun read(target: File): List<Entry> = try {
        if (!target.exists()) emptyList()
        else target.readLines().mapNotNull { decode(it) }
    } catch (e: Exception) {
        emptyList()
    }

    private fun encode(entry: Entry): String = JSONObject()
        .put("t", entry.time)
        .put("f", entry.from)
        .put("to", entry.to)
        .put("s", entry.source)
        .put("r", entry.result ?: JSONObject.NULL)
        .put("e", entry.error ?: JSONObject.NULL)
        .toString()

    private fun decode(line: String): Entry? = try {
        val json = JSONObject(line)
        Entry(
            time = json.optLong("t", 0L),
            from = json.optString("f"),
            to = json.optString("to"),
            source = json.optString("s"),
            result = json.optString("r").ifBlank { null },
            error = json.optString("e").ifBlank { null },
        )
    } catch (e: Exception) {
        null
    }
}
