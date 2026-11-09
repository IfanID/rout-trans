package id.ifan.routtrans.payload.view

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
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
    private val badge: TranslationBadge? = null,
) : Application.ActivityLifecycleCallbacks {

    private val handler = Handler(Looper.getMainLooper())
    private var resumed: Activity? = null
    private var lastWaitLog = 0L

    private val tick = object : Runnable {
        override fun run() {
            // Payload berjalan di proses aplikasi lain. Satu exception yang
            // lolos ke thread utama akan membuat aplikasi target ditutup
            // sistem, jadi setiap pemindaian dibungkus.
            try {
                scan()
            } catch (t: Throwable) {
                Log.w(TAG, "Pemindaian dilewati", t)
            }
            handler.postDelayed(this, config.scanIntervalMs.toLong())
        }
    }

    /**
     * Pendaftaran callback sengaja dilakukan di thread utama.
     *
     * Payload dipasang dari thread JNI, sedangkan saat itu aplikasi target
     * sedang membuat Activity dan mengiterasi daftar callback. Mendaftar dari
     * thread lain di tengah iterasi bisa memicu ConcurrentModificationException
     * di thread utama, yang membuat aplikasi langsung tertutup.
     */
    fun start() {
        handler.post {
            try {
                application.registerActivityLifecycleCallbacks(this)
                Log.i(TAG, "Callback lifecycle terpasang")
            } catch (t: Throwable) {
                Log.w(TAG, "Callback lifecycle gagal dipasang", t)
            }
            handler.postDelayed(tick, START_DELAY_MS)
        }
    }

    private fun scan() {
        val activity = resumed
        if (activity == null) {
            // Berguna saat injeksi tampak berhasil tetapi tidak ada teks yang
            // berubah: Activity target belum pernah terdeteksi tampil.
            val now = SystemClock.uptimeMillis()
            if (now - lastWaitLog > WAIT_LOG_MS) {
                lastWaitLog = now
                Log.i(TAG, "Menunggu Activity target tampil")
            }
            return
        }
        badge?.attach(activity)
        val root = activity.window?.decorView ?: return
        scanner.scan(root)
        webViewTranslator?.scan(root)
    }

    override fun onActivityResumed(activity: Activity) {
        resumed = activity
        Log.i(TAG, "Activity tampil: ${activity.javaClass.name}")
        badge?.attach(activity)
    }

    override fun onActivityPaused(activity: Activity) {
        if (resumed === activity) resumed = null
        badge?.detach()
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

    override fun onActivityStarted(activity: Activity) = Unit

    override fun onActivityStopped(activity: Activity) = Unit

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) {
        if (resumed === activity) resumed = null
        badge?.detach()
    }

    private companion object {
        const val TAG = "RoutTrans"

        /** Jeda awal supaya aplikasi selesai menggambar layar pertamanya. */
        const val START_DELAY_MS = 700L

        /** Jeda antar peringatan "belum ada Activity" di log. */
        const val WAIT_LOG_MS = 10_000L
    }
}
