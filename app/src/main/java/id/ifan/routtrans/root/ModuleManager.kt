package id.ifan.routtrans.root

import android.content.Context
import id.ifan.routtrans.data.Settings
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Memasang dan memeriksa modul Zygisk lewat root.
 *
 * Pemasangan memakai `magisk --install-module` (dipakai juga oleh Kitsune
 * Mask). Kalau perintah itu tidak ada, zip dibongkar langsung ke folder modul
 * sebagai cadangan. Modul baru benar-benar aktif setelah perangkat di-reboot.
 */
object ModuleManager {

    const val MODULE_DIR = "/data/adb/modules/routtrans"
    private const val ASSET = "routtrans-module.zip"
    private const val STAGING = "/data/local/tmp/routtrans-module.zip"

    data class Status(
        val installed: Boolean,
        val disabled: Boolean,
        val version: String,
        val description: String,
    ) {
        /** Terpasang dan tidak dinonaktifkan; efeknya tetap butuh reboot. */
        val active: Boolean get() = installed && !disabled
    }

    fun status(): Status {
        val props = RootShell.run("cat $MODULE_DIR/module.prop 2>/dev/null")
        if (!props.ok || props.output.isBlank()) {
            return Status(false, false, "", "")
        }
        val values = props.output.lineSequence()
            .mapNotNull { line ->
                val index = line.indexOf('=')
                if (index <= 0) null else line.substring(0, index).trim() to line.substring(index + 1).trim()
            }
            .toMap()
        val disabled = RootShell.run("test -f $MODULE_DIR/disable").ok
        return Status(
            installed = true,
            disabled = disabled,
            version = values["version"].orEmpty(),
            description = values["description"].orEmpty(),
        )
    }

    fun install(context: Context): RootShell.Result {
        val zip = File(context.cacheDir, ASSET)
        try {
            context.assets.open(ASSET).use { input ->
                zip.outputStream().use { output -> input.copyTo(output) }
            }
        } catch (e: IOException) {
            return RootShell.Result(
                -1,
                "Paket modul tidak ditemukan di APK. Pasang APK hasil build CI yang menyertakan assets/$ASSET.",
            )
        }

        val copy = RootShell.run("cp '${zip.absolutePath}' '$STAGING' && chmod 644 '$STAGING'")
        if (!copy.ok) return copy
        zip.delete()

        val magisk = RootShell.run("magisk --install-module '$STAGING' 2>&1")
        if (magisk.ok) return magisk

        // Cadangan: bongkar zip langsung. Dipakai bila perintah magisk tidak
        // tersedia di ROM, misalnya pada varian yang hanya menyediakan ksud.
        val manual = RootShell.run(
            "rm -rf $MODULE_DIR.new && mkdir -p $MODULE_DIR.new && " +
                "unzip -o '$STAGING' -d $MODULE_DIR.new >/dev/null 2>&1 && " +
                "chmod 755 $MODULE_DIR.new/*.sh 2>/dev/null; " +
                "chmod 644 $MODULE_DIR.new/module.prop 2>/dev/null; " +
                "rm -rf $MODULE_DIR && mv $MODULE_DIR.new $MODULE_DIR"
        )
        return if (manual.ok) {
            RootShell.Result(0, "Modul dipasang langsung ke $MODULE_DIR")
        } else {
            RootShell.Result(
                magisk.code,
                "Pemasangan gagal. Keluaran magisk: ${magisk.output.ifBlank { "kosong" }}",
            )
        }
    }

    fun remove(): RootShell.Result =
        RootShell.run("rm -rf $MODULE_DIR '$STAGING'")

    /**
     * Memasang zip modul bawaan APK bila belum pernah dipasang versi ini.
     *
     * APK hasil CI membawa `assets/routtrans-module.zip`; tanpa langkah ini
     * pengguna harus ingat memasang modul setiap kali modul ikut berubah.
     *
     * @return true bila modul baru saja dipasang dan perlu reboot.
     */
    fun ensureLatest(context: Context): Boolean {
        val hash = bundledHash(context) ?: return false
        if (Settings.moduleHash(context) == hash) return false
        val result = install(context)
        if (!result.ok) return false
        Settings.setModuleHash(context, hash)
        return true
    }

    /** Sidik zip modul di dalam APK; null bila APK tidak memuatnya. */
    private fun bundledHash(context: Context): String? = try {
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

    fun isProviderAlive(context: Context): Boolean = try {
        val result = context.contentResolver.call(
            android.net.Uri.parse("content://id.ifan.routtrans.translate"),
            "ping",
            null,
            null,
        )
        result?.getBoolean("ok") == true
    } catch (e: Exception) {
        false
    }
}
