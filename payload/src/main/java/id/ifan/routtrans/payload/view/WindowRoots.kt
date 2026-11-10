package id.ifan.routtrans.payload.view

import android.util.Log
import android.view.View
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Mengumpulkan semua root jendela milik proses ini.
 *
 * `Activity.window.decorView` hanya mencakup jendela Activity. Dialog, bottom
 * sheet, dan popup punya jendela sendiri sehingga tidak ikut terpindai — itu
 * sebabnya sebagian aplikasi hanya menerjemahkan layar pertamanya.
 *
 * Daftar jendela diambil dari `WindowManagerGlobal.mViews`. Kalau Android
 * menolak aksesnya (kebijakan hidden API), pemanggil tetap mendapat
 * `decorView` sebagai cadangan.
 */
object WindowRoots {

    private const val TAG = "RoutTrans"

    private var cached: Access? = null
    private val warned = AtomicBoolean(false)

    fun collect(fallback: View?): List<View> {
        val roots = read()
        if (roots.isEmpty() && fallback != null) roots.add(fallback)
        return roots
    }

    private fun read(): ArrayList<View> {
        val result = ArrayList<View>()
        val access = access() ?: return result
        try {
            val instance = access.method.invoke(null) ?: return result
            val views = access.field.get(instance) ?: return result
            when (views) {
                is Collection<*> -> views.forEach { view -> (view as? View)?.let(result::add) }
                is Array<*> -> views.forEach { view -> (view as? View)?.let(result::add) }
            }
        } catch (t: Throwable) {
            // Setiap kegagalan refleksi cukup dilaporkan sekali; pemindaian
            // tetap berjalan lewat fallback.
            if (warned.compareAndSet(false, true)) {
                Log.w(TAG, "Daftar jendela tidak bisa dibaca: ${t.javaClass.simpleName}")
            }
        }
        return result
    }

    private fun access(): Access? {
        cached?.let { return it }
        return try {
            val global = Class.forName("android.view.WindowManagerGlobal")
            val method = global.getMethod("getInstance")
            val field = global.getDeclaredField("mViews").apply { isAccessible = true }
            Access(method, field).also { cached = it }
        } catch (t: Throwable) {
            if (warned.compareAndSet(false, true)) {
                Log.w(TAG, "WindowManagerGlobal tidak tersedia: ${t.javaClass.simpleName}")
            }
            null
        }
    }

    private data class Access(val method: Method, val field: Field)
}
