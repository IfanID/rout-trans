package id.ifan.routtrans.data

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Catatan kejadian aplikasi: pembaruan payload, pemasangan modul, dan
 * penyimpanan konfigurasi.
 *
 * Berbeda dari `translations.log` (isi terjemahan) dan `injection.log`
 * (logcat), berkas ini hanya memuat peristiwa penting agar mudah dibaca.
 * Isinya dibatasi 200 baris terakhir supaya tidak menumpuk.
 */
object AppLog {

    const val FILE_NAME = "app.log"
    private const val MAX_LINES = 200

    private val lock = Any()
    private val format = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun add(context: Context, event: String) {
        synchronized(lock) {
            try {
                val file = File(LogFiles.dir(context), FILE_NAME)
                file.parentFile?.mkdirs()
                val lines = if (file.exists()) {
                    file.readLines().toMutableList()
                } else {
                    mutableListOf()
                }
                lines.add("${format.format(Date())}  $event")
                while (lines.size > MAX_LINES) lines.removeAt(0)
                file.writeText(lines.joinToString("\n", postfix = "\n"))
            } catch (e: Exception) {
                // Log tidak boleh mengganggu alur utama aplikasi.
            }
        }
    }
}
