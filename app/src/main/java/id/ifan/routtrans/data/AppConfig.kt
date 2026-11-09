package id.ifan.routtrans.data

import org.json.JSONException
import org.json.JSONObject

/**
 * Konfigurasi satu aplikasi. Isinya ditulis ke
 * /data/adb/routtrans/apps/<paket>.json dan dibaca langsung oleh payload
 * di proses aplikasi target.
 */
data class AppConfig(
    val from: String,
    val to: String,
    val webView: Boolean = true,
    val minLength: Int = 2,
    val maxLength: Int = 400,
    val scanIntervalMs: Int = 350,
) {

    fun toJson(): String = JSONObject()
        .put("from", from)
        .put("to", to)
        .put("webview", webView)
        .put("minLength", minLength)
        .put("maxLength", maxLength)
        .put("scanIntervalMs", scanIntervalMs)
        .toString()

    companion object {

        fun fromJson(json: String): AppConfig? = try {
            val obj = JSONObject(json)
            AppConfig(
                from = obj.optString("from", "en"),
                to = obj.optString("to", "id"),
                webView = obj.optBoolean("webview", true),
                minLength = obj.optInt("minLength", 2),
                maxLength = obj.optInt("maxLength", 400),
                scanIntervalMs = obj.optInt("scanIntervalMs", 350),
            )
        } catch (e: JSONException) {
            null
        }
    }
}
