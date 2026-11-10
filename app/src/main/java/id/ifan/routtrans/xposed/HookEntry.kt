package id.ifan.routtrans.xposed

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.Log
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers

/**
 * Hook teks yang digambar langsung oleh aplikasi.
 *
 * Ada dua jalur penggambaran di Android modern:
 * - `Canvas` untuk penggambaran software,
 * - `BaseRecordingCanvas` (induk `RecordingCanvas`) untuk penggambaran
 *   hardware. Compose dan hampir semua View modern lewat jalur ini.
 *
 * Keduanya di-hook, termasuk `drawTextRun` yang dipakai Compose untuk
 * menggambar satu baris teks.
 */
class HookEntry : de.robv.android.xposed.IXposedHookLoadPackage {

    override fun handleLoadPackage(param: de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam) {
        val pkg = param.packageName ?: return
        if (pkg == "android" || pkg == "id.ifan.routrans") return
        Log.i(TAG, "HookEntry dimuat untuk $pkg")

        val context = applicationContext()
        if (context == null) {
            Log.w(TAG, "Konteks tidak tersedia untuk $pkg")
            return
        }
        val config = Hub.config(context, pkg)
        if (config == null) {
            Log.i(TAG, "Paket tidak dipilih: $pkg")
            return
        }

        Hub.prepare(context, config)
        hookSetText(param.classLoader)
        hookCanvas(Canvas::class.java)
        recordingCanvas()?.let { hookCanvas(it) }
        Log.i(TAG, "Hook dipasang untuk $pkg")
    }

    private fun hookSetText(loader: ClassLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                "android.widget.TextView",
                loader,
                "setText",
                CharSequence::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val original = param.args[0]?.toString() ?: return
                        val translated = Hub.translateOrRequest(original) ?: return
                        param.args[0] = translated
                    }
                },
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Hook setText gagal", t)
        }

        try {
            XposedHelpers.findAndHookMethod(
                "android.widget.TextView",
                loader,
                "setText",
                CharSequence::class.java,
                TextView.BufferType::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val original = param.args[0]?.toString() ?: return
                        val translated = Hub.translateOrRequest(original) ?: return
                        param.args[0] = translated
                    }
                },
            )
            XposedHelpers.findAndHookMethod(
                "android.widget.TextView",
                loader,
                "setHint",
                CharSequence::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val original = param.args[0]?.toString() ?: return
                        val translated = Hub.translateOrRequest(original) ?: return
                        param.args[0] = translated
                    }
                },
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Hook setHint gagal", t)
        }
    }

    /**
     * Kelas induk `RecordingCanvas` yang memuat implementasi `drawText` untuk
     * penggambaran hardware.
     */
    private fun recordingCanvas(): Class<*>? = try {
        Class.forName("android.graphics.RecordingCanvas").superclass
    } catch (t: Throwable) {
        Log.w(TAG, "RecordingCanvas tidak ditemukan", t)
        null
    }

    private fun hookCanvas(clazz: Class<*>) {
        val f = Float::class.javaPrimitiveType!!
        val i = Int::class.javaPrimitiveType!!
        val z = Boolean::class.javaPrimitiveType!!

        hookDraw(clazz, "drawText", String::class.java, f, f, Paint::class.java)
        hookDraw(clazz, "drawText", String::class.java, i, i, f, f, Paint::class.java)
        hookDraw(clazz, "drawText", CharArray::class.java, i, i, f, f, Paint::class.java)
        hookDraw(clazz, "drawText", CharSequence::class.java, i, i, f, f, Paint::class.java)

        hookDraw(clazz, "drawTextRun", CharArray::class.java, i, i, i, i, f, f, z, Paint::class.java)
        hookDraw(clazz, "drawTextRun", CharSequence::class.java, i, i, i, i, f, f, z, Paint::class.java)
    }

    private fun hookDraw(clazz: Class<*>, name: String, vararg types: Any) {
        val args = ArrayList<Any>(types.size + 1)
        args.addAll(types)
        args.add(TextDrawHook)
        try {
            XposedHelpers.findAndHookMethod(clazz, name, *args.toTypedArray())
        } catch (t: Throwable) {
            // Sebagian metode tidak ada di versi Android tertentu.
            Log.w(TAG, "Hook $name gagal di ${clazz.simpleName}", t)
        }
    }

    /** Mengganti teks yang digambar, termasuk indeks rentang milik Compose. */
    private object TextDrawHook : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val args = param.args
            if (args.isEmpty()) return
            val run = (param.method as? java.lang.reflect.Method)?.name == "drawTextRun"
            val first = args[0] ?: return
            val source = when (first) {
                is String -> first
                is CharArray -> {
                    val start = (args[1] as? Int) ?: 0
                    val count = (args[2] as? Int) ?: (first.size - start)
                    String(first, start, count)
                }
                is CharSequence -> {
                    val start = (args[1] as? Int) ?: 0
                    val end = (args[2] as? Int) ?: first.length
                    first.subSequence(start, end.coerceAtMost(first.length)).toString()
                }
                else -> return
            }
            if (source.isBlank()) return

            val translated = Hub.translateOrRequest(source.trim()) ?: return
            when (first) {
                is String -> args[0] = translated
                is CharArray -> {
                    val chars = translated.toCharArray()
                    args[0] = chars
                    if (args.size > 2 && args[1] is Int) {
                        args[1] = 0
                        args[2] = chars.size
                    }
                }
                is CharSequence -> {
                    args[0] = translated
                    if (args.size > 2 && args[1] is Int) {
                        args[1] = 0
                        args[2] = translated.length
                    }
                }
            }
            // drawTextRun memakai dua indeks konteks tambahan (argumen 3 dan 4).
            if (run && args.size > 4) {
                args[3] = 0
                args[4] = translated.length
            }
        }
    }

    /** Konteks aplikasi target; dipakai untuk menghubungi provider RoutTrans. */
    private fun applicationContext(): Context? = try {
        val thread = Class.forName("android.app.ActivityThread")
        thread.getMethod("currentApplication").invoke(null) as? Context
    } catch (t: Throwable) {
        null
    }

    private companion object {
        const val TAG = "RoutTransHook"
    }
}
