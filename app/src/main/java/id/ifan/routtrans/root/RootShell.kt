package id.ifan.routtrans.root

import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Pembungkus pemanggilan `su`.
 *
 * Pembacaan output dilakukan di thread terpisah dengan batas waktu: perintah
 * root yang macet tidak boleh menahan thread pemanggil selamanya.
 */
object RootShell {

    private const val TIMEOUT_SECONDS = 25L

    data class Result(val code: Int, val output: String) {
        val ok: Boolean get() = code == 0
    }

    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "RoutTransSu").apply { isDaemon = true }
    }

    fun run(command: String): Result {
        val future = executor.submit<Result> { execute(command) }
        return try {
            future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (e: Exception) {
            future.cancel(true)
            Result(-1, "Perintah root gagal atau melebihi batas waktu")
        }
    }

    fun hasRoot(): Boolean = run("id -u").let { it.ok && it.output.trim() == "0" }

    private fun execute(command: String): Result {
        var process: Process? = null
        return try {
            process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
            val lines = mutableListOf<String>()
            BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8)).use { reader ->
                reader.forEachLine { lines.add(it) }
            }
            Result(process.waitFor(), lines.joinToString("\n"))
        } catch (e: Exception) {
            Result(-1, "Gagal menjalankan su: ${e.message}")
        } finally {
            process?.destroy()
        }
    }
}
