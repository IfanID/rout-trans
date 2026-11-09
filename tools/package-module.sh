#!/usr/bin/env bash
#
# Menyusun zip modul Magisk/Zygisk dari isi module/.
#
# Pemakaian:
#   tools/package-module.sh [--so <path ke arm64-v8a.so>]
#
# Tanpa --so, skrip mencari .so hasil build CMake di module/build/.
# Hasil zip juga disalin ke app/src/main/assets/ supaya tombol
# "Pasang modul" di aplikasi bisa memasangnya.
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
STAGE="$ROOT/dist/stage"
ZIP="$ROOT/dist/routtrans-module.zip"
SO=""

while [ $# -gt 0 ]; do
    case "$1" in
        --so)
            SO="${2:-}"
            shift 2
            ;;
        *)
            echo "Argumen tidak dikenal: $1" >&2
            exit 1
            ;;
    esac
done

if [ -z "$SO" ]; then
    SO="$(find "$ROOT/module/build" "$ROOT/module/zygisk" -name 'arm64-v8a.so' 2>/dev/null | head -1)"
fi
if [ -z "$SO" ] || [ ! -f "$SO" ]; then
    echo "arm64-v8a.so tidak ditemukan. Bangun dulu lewat CMake/NDK atau kirim --so <path>." >&2
    exit 1
fi
if [ ! -f "$ROOT/module/classes.dex" ]; then
    echo "module/classes.dex tidak ada. Jalankan tools/build-payload-dex.sh dulu." >&2
    exit 1
fi

rm -rf "$ROOT/dist"
mkdir -p "$STAGE/zygisk"

cp "$ROOT/module/module.prop" "$ROOT/module/customize.sh" \
   "$ROOT/module/post-fs-data.sh" "$ROOT/module/uninstall.sh" "$STAGE/"
cp "$ROOT/module/classes.dex" "$STAGE/classes.dex"
cp "$SO" "$STAGE/zygisk/arm64-v8a.so"
chmod 755 "$STAGE/customize.sh" "$STAGE/post-fs-data.sh" "$STAGE/uninstall.sh"

(cd "$STAGE" && jar cfM "$ZIP" .)

mkdir -p "$ROOT/app/src/main/assets"
cp "$ZIP" "$ROOT/app/src/main/assets/routtrans-module.zip"

echo "Zip modul: $ZIP"
unzip -l "$ZIP"
