package id.ifan.routtrans.data

import android.content.Context
import org.json.JSONObject

/**
 * Catatan injeksi payload per aplikasi.
 *
 * Payload yang berjalan di proses aplikasi target melapor ke provider
 * RoutTrans lewat ContentProvider.call(), sehingga pengendali bisa
 * menampilkan apakah injeksi benar-benar terjadi (bukan hanya berasumsi
 * dari konfigurasi yang tersimpan).
 */
object InjectionStore {

    private const val PREF = "routtrans_injections"
    private const val KEY_TOTAL = "total"

    data class Record(
        val time: Long,
        val process: String,
        val from: String,
        val to: String,
        val error: String?,
    ) {
        val ok: Boolean get() = error == null
    }

    fun record(
        context: Context,
        packageName: String,
        process: String,
        from: String,
        to: String,
        error: String?,
    ) {
        if (packageName.isBlank()) return
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val value = JSONObject()
            .put("time", System.currentTimeMillis())
            .put("process", process)
            .put("from", from)
            .put("to", to)
            .put("error", error.orEmpty())
            .toString()
        prefs.edit()
            .putString(packageName, value)
            .putLong(KEY_TOTAL, prefs.getLong(KEY_TOTAL, 0) + 1)
            .apply()
    }

    fun get(context: Context, packageName: String): Record? {
        val raw = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(packageName, null) ?: return null
        return parse(raw)
    }

    fun all(context: Context): Map<String, Record> {
        val prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val result = LinkedHashMap<String, Record>()
        for ((key, value) in prefs.all) {
            if (key == KEY_TOTAL || value !is String) continue
            parse(value)?.let { result[key] = it }
        }
        return result
    }

    fun total(context: Context): Long =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getLong(KEY_TOTAL, 0)

    fun lastTime(context: Context): Long? = all(context).values.maxOfOrNull { it.time }

    fun clear(context: Context) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun parse(raw: String): Record? = try {
        val json = JSONObject(raw)
        val error = json.optString("error").ifBlank { null }
        Record(
            time = json.optLong("time", 0),
            process = json.optString("process"),
            from = json.optString("from"),
            to = json.optString("to"),
            error = error,
        )
    } catch (e: Exception) {
        null
    }
}
