#!/system/bin/sh

# Dijalankan Magisk saat modul dipasang (sebelum reboot).

SKIPUNZIP=0

ui_print "- RoutTrans: modul terjemahan berbasis Zygisk"

if [ ! -f "$MODPATH/zygisk/arm64-v8a.so" ]; then
    ui_print "! zygisk/arm64-v8a.so tidak ada di zip"
    ui_print "! Pasang zip hasil GitHub Actions, bukan folder proyek mentah"
    abort "! Modul tidak lengkap"
fi

if [ ! -f "$MODPATH/classes.dex" ]; then
    ui_print "! classes.dex tidak ada di zip"
    abort "! Modul tidak lengkap"
fi

# Zygisk wajib aktif, baik bawaan Magisk maupun Zygisk Next.
ZYGISK_SETTING="$(magisk --sqlite "SELECT value FROM settings WHERE key='zygisk'" 2>/dev/null | sed 's/.*value=//')"
if [ "$ZYGISK_SETTING" != "1" ] && [ ! -d /data/adb/modules/zygisksu ]; then
    ui_print "! Zygisk tidak terdeteksi aktif"
    ui_print "! Nyalakan Zygisk (bawaan Magisk atau Zygisk Next), lalu reboot"
fi

if [ -d /data/adb/modules/riru-core ]; then
    ui_print "! Riru terpasang. Riru bentrok dengan Zygisk dan harus dihapus."
fi

ui_print "- Setuju GPL-3.0: kode sumber ada di repositori proyek"
