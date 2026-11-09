package id.ifan.routtrans.payload.view

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import id.ifan.routtrans.payload.config.Config
import id.ifan.routtrans.payload.web.WebViewTranslator

/**
 * Menjalankan pemindaian berkala pada Activity yang sedang tampil.
 *
 * Pemindaian hanya dilakukan saat ada Activity aktif, dan berhenti sendiri
 * ketika aplikasi masuk latar belakang supaya tidak membuang baterai.
 */
class ActivityWatcher(
    private val application: Application,
    private val config: Config,
    private val scanner: ViewScanner,
    private val webViewTranslator: WebViewTranslator?,
) : Application.ActivityLifecycleCallbacks {

    private val handler = Handler(Looper.getMainLooper())
    private var resumed: Activity? = null

    private val tick = object : Runnable {
        override fun run() {
            scan()
            handler.postDelayed(this, config.scanIntervalMs.toLong())
        }
    }

    fun start() {
        application.registerActivityLifecycleCallbacks(this)
        handler.post(tick)
    }

    private fun scan() {
        val activity = resumed ?: return
        val root = activity.window?.decorView ?: return
        scanner.scan(root)
        webViewTranslator?.scan(root)
    }

    override fun onActivityResumed(activity: Activity) {
        resumed = activity
    }

    override fun onActivityPaused(activity: Activity) {
        if (resumed === activity) resumed = null
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

    override fun onActivityStarted(activity: Activity) = Unit

    override fun onActivityStopped(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) {
        if (resumed === activity) resumed = null
    }
}
