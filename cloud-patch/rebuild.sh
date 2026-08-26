#!/usr/bin/env bash
set -Eeuo pipefail

[[ -d work/apktool ]] || { echo "Decoded tree missing" >&2; exit 1; }
mkdir -p out/report
BT="${ANDROID_SDK_ROOT:-$ANDROID_HOME}/build-tools/${BUILD_TOOLS_VERSION}"
PLATFORM="${ANDROID_SDK_ROOT:-$ANDROID_HOME}/platforms/android-35/android.jar"
PATCH_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HELPER_SOURCE="$PATCH_ROOT/java/com/apple/android/music/player/VivoCarLyrics.java"
HELPER_WORK="$RUNNER_TEMP/vivo-car-lyrics-helper"

[[ -f "$PLATFORM" ]] || { echo "Android 35 platform is missing" >&2; exit 1; }
[[ -f "$HELPER_SOURCE" ]] || { echo "VivoCarLyrics.java is missing" >&2; exit 1; }
rm -rf "$HELPER_WORK"
mkdir -p "$HELPER_WORK/classes" "$HELPER_WORK/dex"

find work/apktool -path '*/META-INF/*' -type f \
  \( -iname '*.RSA' -o -iname '*.DSA' -o -iname '*.EC' -o -iname '*.SF' -o -iname 'MANIFEST.MF' \) -delete

java -Xmx4g -jar "$APKTOOL_JAR" b work/apktool -o out/app-unsigned.apk

javac --release 8 -classpath "$PLATFORM" -d "$HELPER_WORK/classes" "$HELPER_SOURCE"
jar --create --file "$HELPER_WORK/vivo-car-lyrics.jar" -C "$HELPER_WORK/classes" .
"$BT/d8" --min-api 30 --output "$HELPER_WORK/dex" "$HELPER_WORK/vivo-car-lyrics.jar"
mv "$HELPER_WORK/dex/classes.dex" "$HELPER_WORK/dex/classes5.dex"

if unzip -Z1 out/app-unsigned.apk | grep -qx 'classes5.dex'; then
  echo "Unexpected pre-existing classes5.dex" >&2
  exit 1
fi

unsigned_apk="$(realpath out/app-unsigned.apk)"
(cd "$HELPER_WORK/dex" && zip -q "$unsigned_apk" classes5.dex)
unzip -p out/app-unsigned.apk classes5.dex > out/report/classes5.dex
strings out/report/classes5.dex > out/report/vivo-car-lyrics-helper-strings.txt

for marker in \
  'com/apple/android/music/player/VivoCarLyrics' \
  'ucar.media.metadata.LYRICS_LINE' \
  'ucar.media.metadata.LYRICS_WHOLE' \
  'ucar.media.metadata.LYRICS_STATUS' \
  'music.media.extras.LYRIC' \
  'music.media.extras.LYRIC_IS_ALLOWED' \
  'music.media.extras.NOTICE_CAR'; do
  grep -Fq "$marker" out/report/vivo-car-lyrics-helper-strings.txt || {
    echo "Missing helper marker: $marker" >&2
    exit 1
  }
done

if [[ -x "$BT/dexdump" ]]; then
  "$BT/dexdump" -f out/report/classes5.dex > out/report/vivo-car-lyrics-dexdump.txt
fi
sha256sum out/report/classes5.dex > out/report/classes5.dex.sha256

"$BT/zipalign" -p -f -v 4 out/app-unsigned.apk out/app-aligned.apk

keytool -genkeypair -noprompt -storetype JKS \
  -keystore "$RUNNER_TEMP/debug.keystore" -storepass android -keypass android \
  -alias androiddebugkey -keyalg RSA -keysize 2048 -sigalg SHA256withRSA \
  -validity 10000 -dname 'CN=Android Debug,O=Android,C=US'

"$BT/apksigner" sign \
  --ks "$RUNNER_TEMP/debug.keystore" --ks-key-alias androiddebugkey \
  --ks-pass pass:android --key-pass pass:android --v4-signing-enabled false \
  --out out/apple-music-vivo-car-lyrics-debug.apk out/app-aligned.apk

"$BT/apksigner" verify --verbose --print-certs out/apple-music-vivo-car-lyrics-debug.apk \
  > out/report/patched-signature.txt
"$BT/aapt2" dump badging out/apple-music-vivo-car-lyrics-debug.apk \
  > out/report/patched-badging.txt
unzip -Z1 out/apple-music-vivo-car-lyrics-debug.apk \
  | grep -E '^classes[0-9]*\.dex$' \
  > out/report/patched-dex-files.txt
grep -qx 'classes5.dex' out/report/patched-dex-files.txt
sha256sum out/apple-music-vivo-car-lyrics-debug.apk \
  > out/apple-music-vivo-car-lyrics-debug.apk.sha256

rm -f out/app-unsigned.apk out/app-aligned.apk
