# RoutTrans

Penerjemah teks di dalam aplikasi Android, berjalan saat aplikasi dipakai.
Teks antarmuka diganti ke bahasa tujuan tanpa mengubah APK aplikasi tersebut.

RoutTrans memakai **Zygisk** untuk menyuntikkan kode ke proses aplikasi.
Proyek ini **tidak memakai LSPosed maupun EdXposed**.

## Kebutuhan

- Root dengan Magisk (atau KernelSU/APatch) dan **Zygisk aktif**
  (Zygisk bawaan Magisk atau Zygisk Next).
- Android 12 (API 31) atau lebih baru, `arm64-v8a`.
- Internet sekali untuk mengunduh model bahasa MLKit.

## Cara kerja

```
RoutTrans.apk (pengendali)              routtrans-module.zip (Magisk/Zygisk)
├─ UI pilih aplikasi & bahasa           ├─ module.prop, customize.sh
├─ mesin MLKit + cache terjemahan       ├─ zygisk/arm64-v8a.so
└─ provider content://…translate  ◄─────┤  ├─ companion (root): baca config + dex
                                        │  └─ suntik payload ke proses aplikasi
                                        ├─ classes.dex (payload Kotlin)
                                        └─ post-fs-data.sh
```

1. Saat aplikasi target akan dimulai, Zygisk memanggil `preAppSpecialize`.
   Modul menghubungi companion (berjalan sebagai root) lewat socket.
2. Companion memeriksa `/data/adb/routtrans/apps/<paket>.json`.
   Tidak ada berkas berarti aplikasi itu tidak diterjemahkan dan library modul
   langsung dilepas dari proses.
3. Kalau ada, companion mengirim isi konfigurasi dan `classes.dex`.
4. `postAppSpecialize` memuat dex dengan `InMemoryDexClassLoader`, menunggu
   `Application` terbentuk, lalu memanggil `RuntimeBootstrap.install(...)`.
5. Payload memindai pohon view setiap ±350 ms saat ada activity terlihat,
   mengirim teks baru ke provider RoutTrans, dan memasang hasilnya. WebView
   ditangani lewat skrip JavaScript yang disuntikkan.
6. Setelah berhasil (atau gagal) terpasang, payload melapor ke provider.
   Laporan itu disimpan per aplikasi sehingga layar pengendali bisa
   menampilkan status injeksi yang sebenarnya.

Terjemahan berjalan di proses aplikasi RoutTrans (MLKit), dan hasilnya
disimpan di cache. Payload hanya menerima teks yang sudah diterjemahkan, jadi
Binder tidak pernah menunggu proses MLKit.

## Bangun

APK bisa dibangun di perangkat ini:

```sh
android-build debug /data/data/com.termux/files/home/IfanID/rout-trans
```

Modul lengkap memerlukan NDK untuk `zygisk/arm64-v8a.so`, jadi dibangun lewat
GitHub Actions (`.github/workflows/build.yml`). Workflow menghasilkan:

- `dist/routtrans-module.zip` — modul Magisk siap pasang
- `app/build/outputs/apk/debug/app-debug.apk` — aplikasi pengendali

Langkah manual setara:

```sh
# 1. library Zygisk (butuh NDK + CMake)
cmake -S module -B module/build -G Ninja \
  -DCMAKE_TOOLCHAIN_FILE=$ANDROID_NDK_HOME/build/cmake/android.toolchain.cmake \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-31 -DCMAKE_BUILD_TYPE=Release
cmake --build module/build
mkdir -p module/zygisk && cp module/build/arm64-v8a.so module/zygisk/

# 2. payload dex (butuh build-tools Android untuk R8)
bash tools/build-payload-dex.sh

# 3. zip modul + salin ke aset APK
bash tools/package-module.sh --so module/zygisk/arm64-v8a.so

# 4. APK
./gradlew :app:assembleDebug
```

## Pasang

1. Pasang APK RoutTrans.
2. Pasang modul: menu → **Pasang modul** (memakai `magisk --install-module`),
   atau pasang `routtrans-module.zip` lewat aplikasi Magisk.
3. Reboot.
4. Buka RoutTrans → pilih **Dari bahasa** dan **Ke bahasa** → tekan
   **Siapkan model** (unduh model MLKit) → pilih aplikasi → **Simpan & terapkan**.
   Aplikasi yang dipilih dihentikan otomatis agar konfigurasi baru dipakai saat
   dibuka lagi.

## Status dan indikator

- **Dari bahasa → Deteksi otomatis** memakai MLKit Language ID. Satu deteksi
  dilakukan per batch teks, lalu dipakai ulang selama 30 detik agar bahasa
  aplikasi tidak berubah-ubah antar pemindaian.
- Setiap baris aplikasi menampilkan status injeksi: *belum disuntik*,
  *tersuntik pukul …*, atau pesan galat dari payload.
- Baris ringkasan di bawah daftar (bisa diketuk) menunjukkan berapa aplikasi
  yang aktif diinjeksi sejak konfigurasi terakhir disimpan.
- Menu **Status** menampilkan root, modul, layanan terjemahan, jumlah teks yang
  diterjemahkan, serta waktu injeksi terakhir.
- Menu **Log terjemahan** menampilkan teks sumber dan hasilnya, termasuk galat
  seperti model yang belum diunduh.
- Proses simpan, unduh model, dan pasang/hapus modul memakai indikator progres,
  teks status, toast, dan dialog hasil.

## Batasan

- Hanya teks `TextView`/`setHint` dan WebView. Teks yang digambar langsung ke
  `Canvas` (game, custom view) serta Compose/Flutter tidak ikut diterjemahkan.
- Aplikasi di DenyList Magisk tidak diinjeksi, jadi tidak bisa diterjemahkan.
- Teks yang sangat panjang (> 400 karakter) dan teks tanpa huruf (angka, URL,
  email) sengaja dilewati.
- Perubahan bahasa memerlukan restart aplikasi target (dilakukan otomatis).
- `classes.dex` hanya untuk `arm64-v8a`.

## Struktur

```
app/       aplikasi pengendali (Kotlin + XML)
payload/   kode yang berjalan di proses aplikasi target (Kotlin)
module/    berkas modul Magisk + sumber C++ Zygisk
tools/     skrip build dex dan paket zip
```

## Lisensi

GPL-3.0. Lihat `LICENSE`.
