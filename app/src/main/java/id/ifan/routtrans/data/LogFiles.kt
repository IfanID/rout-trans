package id.ifan.routtrans.data

import android.content.Context
import java.io.File

/**
 * Lokasi berkas log di penyimpanan aplikasi.
 *
 * Berkasnya ada di `Android/data/id.ifan.routtrans/files`, sehingga bisa
 * dibuka lewat pengelola berkas tanpa perlu root dan tetap ada setelah proses
 * aplikasi dimulai ulang.
 */
object LogFiles {

    const val INJECTION = "injection.log"

    /**
     * Folder log di penyimpanan eksternal aplikasi
     * (`Android/data/id.ifan.routtrans/files`) supaya mudah dibuka lewat
     * pengelola berkas. Jatuh ke penyimpanan internal bila tidak tersedia.
     */
    fun dir(context: Context): File =
        context.getExternalFilesDir(null) ?: context.filesDir

    fun path(context: Context, name: String): String =
        File(dir(context), name).absolutePath

    fun write(context: Context, name: String, text: String) {
        try {
            val target = File(dir(context), name)
            target.parentFile?.mkdirs()
            target.writeText(text)
        } catch (e: Exception) {
            // Log tidak boleh mengganggu alur utama aplikasi.
        }
    }
}
