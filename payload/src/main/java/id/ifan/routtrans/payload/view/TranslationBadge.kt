package id.ifan.routtrans.payload.view

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView

/**
 * Menempelkan logo RoutTrans di kanan atas layar aplikasi target.
 *
 * Badge dipasang ke View milik aplikasi target (bukan overlay sistem), jadi
 * tidak memerlukan izin "tampil di atas aplikasi lain". Ukurannya kecil dan
 * tidak bisa diketuk, sehingga tidak menghalangi sentuhan pengguna.
 */
class TranslationBadge(private val context: Context) {

    private var view: View? = null
    private var host: Activity? = null
    private var icon: Drawable? = null
    private var iconLoaded = false

    /** Dipanggil saat Activity target tampil di layar. */
    fun attach(activity: Activity) {
        if (host === activity) return
        detach()
        // Sebagian aplikasi tidak memakai android.R.id.content sebagai wadah
        // biasa; decorView dipakai sebagai cadangan.
        val parent = activity.findViewById<ViewGroup>(android.R.id.content)
            ?: (activity.window?.decorView as? ViewGroup)
        if (parent == null) {
            Log.w(TAG, "Badge tidak punya wadah di ${activity.javaClass.simpleName}")
            return
        }

        val size = dp(24)
        val badge = createBadge(activity, size)
        val params = FrameLayout.LayoutParams(size, size).apply {
            gravity = Gravity.TOP or Gravity.END
            setMargins(0, dp(6), dp(6), 0)
        }
        try {
            parent.addView(badge, params)
            view = badge
            host = activity
            Log.i(TAG, "Badge terpasang di ${activity.javaClass.simpleName}")
        } catch (t: Throwable) {
            Log.w(TAG, "Badge gagal dipasang", t)
        }
    }

    /** Melepas badge; dipanggil saat aplikasi target masuk latar belakang. */
    fun detach() {
        val badge = view ?: return
        view = null
        host = null
        try {
            (badge.parent as? ViewGroup)?.removeView(badge)
        } catch (t: Throwable) {
            Log.w(TAG, "Badge gagal dilepas", t)
        }
    }

    private fun createBadge(activity: Activity, size: Int): View {
        val icon = loadIcon(activity)
        if (icon != null) {
            return ImageView(activity).apply {
                setImageDrawable(icon)
                background = circle(Color.argb(210, 255, 255, 255))
                setPadding(dp(3), dp(3), dp(3), dp(3))
                setTouchable(this)
            }
        }
        // Ikon RoutTrans tidak selalu bisa dibaca dari proses aplikasi lain
        // (batasan visibilitas paket), jadi dipakai badge huruf sebagai cadangan.
        return TextView(activity).apply {
            text = "R"
            setTextColor(Color.WHITE)
            textSize = 11f
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            background = circle(BRAND)
            setTouchable(this)
        }
    }

    /** Badge tidak boleh menangkap sentuhan milik aplikasi target. */
    private fun setTouchable(view: View) {
        view.isClickable = false
        view.isFocusable = false
        view.alpha = 0.92f
    }

    private fun circle(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun loadIcon(activity: Activity): Drawable? {
        if (iconLoaded) return icon
        iconLoaded = true
        icon = try {
            activity.packageManager.getApplicationIcon(ROUTTRANS_PACKAGE)
        } catch (e: Exception) {
            null
        }
        return icon
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private companion object {
        const val TAG = "RoutTrans"
        const val ROUTTRANS_PACKAGE = "id.ifan.routrans"

        /** Sama dengan warna utama aplikasi pengendali. */
        val BRAND = Color.rgb(31, 111, 92)
    }
}
