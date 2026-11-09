#!/system/bin/sh

# Menyiapkan direktori konfigurasi sebelum aplikasi apa pun berjalan.
# Berkas konfigurasi ditulis oleh aplikasi RoutTrans lewat root.

mkdir -p /data/adb/routtrans/apps
chmod 755 /data/adb/routtrans /data/adb/routtrans/apps
chown 0:0 /data/adb/routtrans /data/adb/routtrans/apps

# Dex payload harus bisa dibaca companion (root) pada setiap peluncuran app.
chmod 644 /data/adb/modules/routtrans/classes.dex 2>/dev/null
chmod 755 /data/adb/modules/routtrans/zygisk 2>/dev/null
chmod 644 /data/adb/modules/routtrans/zygisk/*.so 2>/dev/null
