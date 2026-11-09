package id.ifan.routtrans.data

import android.content.Context
import id.ifan.routtrans.root.RootShell
import java.io.File
import java.io.FileOutputStream

/**
 * Menulis konfigurasi ke /data/adb/routtrans/apps/ melalui root.
 *
 * Berkas ditulis dulu ke cache aplikasi (bisa dibaca root), lalu seluruh
 * direktori tujuan diganti sekaligus. Pola ini menghindari JSON yang harus
 * di-quote di dalam perintah shell dan memastikan aplikasi yang dimatikan
 * benar-benar kehilangan berkasnya.
 */
object ConfigStore {

    const val APPS_DIR = "/data/adb/routtrans/apps"
    private const val STAGE_DIR = "config-stage"

    fun apply(context: Context, config: AppConfig, packages: Collection<String>): RootShell.Result {
        val stage = File(context.cacheDir, STAGE_DIR)
        stage.deleteRecursively()
        if (!stage.mkdirs()) {
            return RootShell.Result(-1, "Gagal membuat direktori sementara")
        }

        var count = 0
        for (pkg in packages) {
            if (!isSafePackage(pkg)) continue
            val bytes = config.toJson().toByteArray(Charsets.UTF_8)
            try {
                FileOutputStream(File(stage, "$pkg.json")).use { it.write(bytes) }
                count++
            } catch (e: Exception) {
                return RootShell.Result(-1, "Gagal menulis konfigurasi: ${e.message}")
            }
        }

        val command = buildString {
            append("mkdir -p $APPS_DIR && ")
            append("rm -rf $APPS_DIR.new $APPS_DIR.old && ")
            append("mkdir -p $APPS_DIR.new && ")
            append("cp ${stage.absolutePath}/*.json $APPS_DIR.new/ 2>/dev/null; ")
            append("mv $APPS_DIR $APPS_DIR.old 2>/dev/null; ")
            append("mv $APPS_DIR.new $APPS_DIR && ")
            append("rm -rf $APPS_DIR.old && ")
            append("chmod 755 $APPS_DIR && ")
            append("chmod 644 $APPS_DIR/*.json 2>/dev/null; ")
            append("chown -R 0:0 $APPS_DIR")
        }
        val result = RootShell.run(command)
        return if (result.ok) {
            RootShell.Result(0, "Konfigurasi ditulis untuk $count aplikasi")
        } else {
            result
        }
    }

    /**
     * Membaca konfigurasi yang aktif sekarang, untuk memulihkan posisi tombol
     * saat aplikasi dibuka kembali.
     */
    fun loadActive(): Map<String, AppConfig> {
        val active = LinkedHashMap<String, AppConfig>()
        val listing = RootShell.run(
            "for f in $APPS_DIR/*.json; do echo \"@@\$f\"; cat \"\$f\"; echo; done 2>/dev/null"
        )
        if (!listing.ok || listing.output.isEmpty()) return active

        var current: String? = null
        val json = StringBuilder()
        for (line in listing.output.split("\n")) {
            if (line.startsWith("@@")) {
                flush(active, current, json)
                current = line.substring(2).trim()
                json.setLength(0)
            } else if (current != null) {
                json.append(line)
            }
        }
        flush(active, current, json)
        return active
    }

    fun forceStop(packages: Collection<String>): RootShell.Result {
        val command = packages.asSequence()
            .filter { isSafePackage(it) }
            .joinToString("") { "am force-stop $it; " }
        return if (command.isEmpty()) RootShell.Result(0, "") else RootShell.run(command)
    }

    /** Nama paket hanya berisi huruf, angka, titik, dan garis bawah. */
    fun isSafePackage(pkg: String): Boolean =
        pkg.isNotEmpty() && pkg.all { it.isLetterOrDigit() && it.code < 128 || it == '.' || it == '_' }

    private fun flush(
        active: MutableMap<String, AppConfig>,
        path: String?,
        json: StringBuilder,
    ) {
        if (path == null || json.isEmpty()) return
        val name = File(path).name
        if (!name.endsWith(".json")) return
        val pkg = name.removeSuffix(".json")
        AppConfig.fromJson(json.toString())?.let { active[pkg] = it }
    }
}
