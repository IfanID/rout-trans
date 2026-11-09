package id.ifan.routtrans.root

import android.content.Context
import android.util.Log
import id.ifan.routtrans.data.Settings
import java.io.File
import java.security.MessageDigest

/**
 * Menyalin dex payload terbaru ke folder modul.
 *
 * Dex dibaca ulang setiap kali proses aplikasi target dimulai, jadi setelah
 * pembaruan ini cukup paksa-tutup aplikasi target; tidak perlu memasang ulang
 * modul atau reboot.
 */
object PayloadUpdater {

    private const val TAG = "RoutTrans"
    private const val ASSET = "classes.dex"
    private const val TARGET = "/data/adb/modules/routtrans/classes.dex"

    /**
     * Menyalin dex bawaan APK bila berbeda dengan yang terakhir dipasang.
     *
     * Tanpa ini pengguna harus mengingat menu "Perbarui payload" setiap kali
     * memperbarui APK; kalau terlewat, payload lama tetap berjalan dan
     * perbaikan tidak pernah terasa.
     */
    fun ensureLatest(context: Context) {
        val hash = assetHash(context) ?: return
        if (Settings.payloadHash(context) == hash) return
        val result = update(context)
        if (result.ok) {
            Settings.setPayloadHash(context, hash)
            Log.i(TAG, "Payload otomatis diperbarui")
        } else {
            Log.w(TAG, "Payload otomatis gagal: ${result.output}")
        }
    }

    fun update(context: Context): RootShell.Result {
        val asset = try {
            context.assets.open(ASSET)
        } catch (e: Exception) {
            return RootShell.Result(-1, "APK ini tidak memuat classes.dex payload.")
        }

        val staged = File(context.cacheDir, ASSET)
        try {
            asset.use { input -> staged.outputStream().use { input.copyTo(it) } }
        } catch (e: Exception) {
            return RootShell.Result(-1, "Gagal menyiapkan dex: ${e.message}")
        }

        val result = RootShell.run("cp '${staged.absolutePath}' '$TARGET' && chmod 644 '$TARGET'")
        staged.delete()
        return result
    }

    /** Mencatat dex yang sekarang sebagai yang sudah terpasang. */
    fun rememberCurrent(context: Context) {
        assetHash(context)?.let { Settings.setPayloadHash(context, it) }
    }

    private fun assetHash(context: Context): String? = try {
        val digest = MessageDigest.getInstance("SHA-256")
        context.assets.open(ASSET).use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    } catch (e: Exception) {
        null
    }
}
