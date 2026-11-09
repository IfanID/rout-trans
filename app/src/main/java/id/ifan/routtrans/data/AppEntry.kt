package id.ifan.routtrans.data

import android.graphics.drawable.Drawable

/** Satu baris aplikasi terpasang di daftar pengendali. */
data class AppEntry(
    val packageName: String,
    val label: String,
    val icon: Drawable,
    var enabled: Boolean = false,
)
