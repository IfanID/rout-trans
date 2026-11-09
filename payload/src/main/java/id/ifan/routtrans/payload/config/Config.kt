package id.ifan.routtrans.payload.config

import org.json.JSONObject

/**
 * Konfigurasi yang dikirim modul Zygisk ke payload.
 *
 * Nilai dari luar dibatasi supaya konfigurasi yang salah ketik tidak membuat
 * pemindaian berjalan terlalu rapat atau menerjemahkan teks raksasa.
 */
data class Config(
    val from: String = "auto",
    val to: String = "id",
    val webView: Boolean = true,
    val minLength: Int = 2,
    val maxLength: Int = 400,
    val scanIntervalMs: Int = 350,
) {

    val autoDetect: Boolean get() = from == "auto"

    companion object {

        fun parse(json: String?): Config? {
            if (json.isNullOrBlank()) return null
            return try {
                val obj = JSONObject(json)
                val to = obj.optString("to", "id").ifBlank { "id" }
                val from = obj.optString("from", "auto").ifBlank { "auto" }
                val minLength = obj.optInt("minLength", 2).coerceIn(1, 50)
                val maxLength = obj.optInt("maxLength", 400).coerceIn(minLength + 1, 2000)
                Config(
                    from = from,
                    to = to,
                    webView = obj.optBoolean("webview", true),
                    minLength = minLength,
                    maxLength = maxLength,
                    scanIntervalMs = obj.optInt("scanIntervalMs", 350).coerceIn(150, 5000),
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}
