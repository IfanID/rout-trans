# Payload dimuat lewat InMemoryDexClassLoader, jadi nama kelas harus tetap.
-keep class id.ifan.routtrans.payload.RuntimeBootstrap { *; }

# MLKit memakai refleksi internal pada model yang diunduh.
-keep class com.google.mlkit.** { *; }
