# Payload dimuat lewat InMemoryDexClassLoader dan dipanggil dari JNI, jadi
# semua kelasnya dipertahankan. R8 tetap dipakai untuk menyusutkan stdlib Kotlin.
-keep class id.ifan.routtrans.payload.** { *; }

# Aplikasi target bisa saja sudah membawa Kotlin stdlib versi lain. ClassLoader
# untuk dex ini mencari ke parent lebih dulu, jadi stdlib milik payload harus
# diberi paket unik agar tidak tertimpa stdlib bawaan aplikasi target.
-repackageclasses 'id.ifan.routtrans.shaded'
-allowaccessmodification

# Metode jembatan WebView dipanggil dari JavaScript berdasarkan anotasi.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

-dontwarn org.json.**
