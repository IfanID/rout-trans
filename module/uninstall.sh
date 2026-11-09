#!/system/bin/sh

# Konfigurasi per aplikasi ikut dibersihkan saat modul dihapus supaya tidak
# meninggalkan berkas yatim di /data/adb.
rm -rf /data/adb/routtrans
