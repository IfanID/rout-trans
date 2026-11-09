#!/usr/bin/env bash
#
# Membangun modul :payload lalu mengubahnya menjadi module/classes.dex.
# Dipakai di CI dan bisa dijalankan lokal selama d8/R8 tersedia.
#
# Pemakaian: tools/build-payload-dex.sh
#
# Payload ditulis dengan Kotlin, sedangkan proses aplikasi target tidak punya
# Kotlin stdlib. Karena itu AAR digabung dengan dependensi runtime lalu
# disusutkan R8 menjadi satu classes.dex yang mandiri.
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
R8="${R8:-$SDK/build-tools/36.0.0/lib/d8.jar}"
ANDROID_JAR="${ANDROID_JAR:-$SDK/platforms/android-36/android.jar}"
OUTPUT="$ROOT/module/classes.dex"
JAVA="${JAVA:-java}"
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    JAVA="$JAVA_HOME/bin/java"
fi

if [ ! -f "$R8" ]; then
    echo "R8 tidak ditemukan di $R8" >&2
    echo "Set R8=/path/ke/d8.jar atau pasang build-tools Android." >&2
    exit 1
fi
if [ ! -f "$ANDROID_JAR" ]; then
    echo "android.jar tidak ditemukan di $ANDROID_JAR" >&2
    echo "Set ANDROID_JAR=/path/ke/android.jar." >&2
    exit 1
fi

cd "$ROOT"
./gradlew :payload:assembleRelease :payload:copyRuntimeDeps --no-daemon

AAR="$(find payload/build/outputs/aar -name '*.aar' | head -1)"
if [ -z "$AAR" ]; then
    echo "AAR payload tidak ditemukan." >&2
    exit 1
fi

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

unzip -o -q "$AAR" classes.jar -d "$TMP"
mkdir -p "$TMP/dex"

LIBS=()
while IFS= read -r jar; do
    LIBS+=("$jar")
done < <(find payload/build/runtime-libs -name '*.jar' 2>/dev/null)
if [ ${#LIBS[@]} -eq 0 ]; then
    echo "Dependensi runtime payload tidak ditemukan." >&2
    exit 1
fi

# --release + shrinker: hanya API stdlib Kotlin yang dipakai payload yang ikut.
"$JAVA" -cp "$R8" com.android.tools.r8.R8 \
    --release \
    --min-api 31 \
    --output "$TMP/dex" \
    --lib "$ANDROID_JAR" \
    --pg-conf "$ROOT/payload/payload-rules.pro" \
    "$TMP/classes.jar" "${LIBS[@]}"

cp "$TMP/dex/classes.dex" "$OUTPUT"

SIZE="$(wc -c < "$OUTPUT")"
echo "classes.dex: $OUTPUT ($SIZE byte)"

if ! grep -aq "RuntimeBootstrap" "$OUTPUT"; then
    echo "Kelas RuntimeBootstrap tidak ada di classes.dex." >&2
    exit 1
fi

if command -v dexdump >/dev/null 2>&1; then
    DEXDUMP="$(command -v dexdump)"
elif [ -x "$SDK/build-tools/36.0.0/dexdump" ]; then
    DEXDUMP="$SDK/build-tools/36.0.0/dexdump"
else
    DEXDUMP=""
fi

# dexdump dari build-tools resmi hanya x86_64; di perangkat ARM cukup dilewati.
if [ -n "$DEXDUMP" ]; then
    "$DEXDUMP" -f "$OUTPUT" 2>/dev/null | head -8 || \
        echo "dexdump tidak bisa dijalankan di mesin ini, verifikasi dilewati"
fi
