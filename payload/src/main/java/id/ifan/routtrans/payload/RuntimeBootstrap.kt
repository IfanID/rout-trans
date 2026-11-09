@file:JvmName("RuntimeBootstrap")

package id.ifan.routtrans.payload

import android.app.Application
import android.util.Log
import id.ifan.routtrans.payload.config.Config
import id.ifan.routtrans.payload.translate.TranslatorClient
import id.ifan.routtrans.payload.view.ActivityWatcher
import id.ifan.routtrans.payload.view.ViewScanner
import id.ifan.routtrans.payload.web.WebViewTranslator
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "RoutTrans"

/**
 * Titik masuk payload. Modul Zygisk memanggil metode statis ini lewat JNI
 * setelah Application proses target terbentuk.
 *
 * Nama kelas dan tanda tangan metode harus tetap: "RuntimeBootstrap.install"
 * dengan parameter (Application, String) dirujuk langsung dari zygisk.cpp.
 */
private val installed = AtomicBoolean(false)

fun install(application: Application, configJson: String) {
    val config = Config.parse(configJson)
    if (config == null) {
        Log.w(TAG, "Konfigurasi tidak valid, payload dilewati")
        return
    }
    if (!config.autoDetect && config.from == config.to) {
        Log.w(TAG, "Bahasa sumber dan tujuan sama, payload dilewati")
        return
    }
    if (!installed.compareAndSet(false, true)) return

    val client = TranslatorClient(application)
    try {
        val scanner = ViewScanner(config, client)
        val webView = if (config.webView) WebViewTranslator(config, client) else null
        ActivityWatcher(application, config, scanner, webView).start()
        client.reportInjected(config.from, config.to)
        Log.i(TAG, "Payload aktif: ${config.from} -> ${config.to}")
    } catch (t: Throwable) {
        installed.set(false)
        Log.e(TAG, "Payload gagal dipasang", t)
        client.reportInjected(config.from, config.to, t.message ?: t.javaClass.simpleName)
    }
}
