#!/usr/bin/env bash
set -Eeuo pipefail

[[ -d work/apktool ]] || { echo "Decoded tree missing" >&2; exit 1; }
mkdir -p out/report
BT="${ANDROID_SDK_ROOT:-$ANDROID_HOME}/build-tools/${BUILD_TOOLS_VERSION}"
PLATFORM="${ANDROID_SDK_ROOT:-$ANDROID_HOME}/platforms/android-35/android.jar"
PATCH_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HELPER_SOURCE="$PATCH_ROOT/java/com/apple/android/music/player/VivoCarLyrics.java"
HELPER_WORK="$RUNNER_TEMP/vivo-car-lyrics-helper"
SIGNING_KEY="$RUNNER_TEMP/apple-music-vivo-car-lyrics-signing.p12"
SIGNING_CERT_SHA256_FILE="$PATCH_ROOT/../config/signing-cert-sha256.txt"
SIGNING_KEY_ALIAS=apple-music-vivo-car-lyrics
HELPER_DEX_NAME=""
APK_ENTRY_LIST="$HELPER_WORK/apk-entries.txt"

[[ -f "$PLATFORM" ]] || { echo "Android 35 platform is missing" >&2; exit 1; }
[[ -f "$HELPER_SOURCE" ]] || { echo "VivoCarLyrics.java is missing" >&2; exit 1; }
[[ -f "$SIGNING_CERT_SHA256_FILE" ]] || { echo "Signing certificate pin is missing" >&2; exit 1; }
[[ -n "${SIGNING_KEY_BASE64:-}" ]] || { echo "ANDROID_SIGNING_KEY_BASE64 secret is missing" >&2; exit 1; }
[[ -n "${SIGNING_PASSWORD:-}" ]] || { echo "ANDROID_SIGNING_PASSWORD secret is missing" >&2; exit 1; }
umask 077
rm -rf "$HELPER_WORK"
mkdir -p "$HELPER_WORK/classes" "$HELPER_WORK/dex"

find work/apktool -path '*/META-INF/*' -type f \
  \( -iname '*.RSA' -o -iname '*.DSA' -o -iname '*.EC' -o -iname '*.SF' -o -iname 'MANIFEST.MF' \) -delete

java -Xmx4g -jar "$APKTOOL_JAR" b work/apktool -o out/app-unsigned.apk

javac --release 8 -classpath "$PLATFORM" -d "$HELPER_WORK/classes" "$HELPER_SOURCE"
jar --create --file "$HELPER_WORK/vivo-car-lyrics.jar" -C "$HELPER_WORK/classes" .
"$BT/d8" --min-api 30 --output "$HELPER_WORK/dex" "$HELPER_WORK/vivo-car-lyrics.jar"
unzip -Z1 out/app-unsigned.apk > "$APK_ENTRY_LIST"
helper_dex_number=1
while :; do
  if ((helper_dex_number == 1)); then
    candidate_dex='classes.dex'
  else
    candidate_dex="classes${helper_dex_number}.dex"
  fi
  if ! grep -Fxq "$candidate_dex" "$APK_ENTRY_LIST"; then
    HELPER_DEX_NAME="$candidate_dex"
    break
  fi
  helper_dex_number=$((helper_dex_number + 1))
done
mv "$HELPER_WORK/dex/classes.dex" "$HELPER_WORK/dex/$HELPER_DEX_NAME"
printf '%s\n' "$HELPER_DEX_NAME" > out/report/helper-dex-name.txt

unsigned_apk="$(realpath out/app-unsigned.apk)"
(cd "$HELPER_WORK/dex" && zip -q "$unsigned_apk" "$HELPER_DEX_NAME")
unzip -p out/app-unsigned.apk "$HELPER_DEX_NAME" > "out/report/$HELPER_DEX_NAME"
strings "out/report/$HELPER_DEX_NAME" > out/report/vivo-car-lyrics-helper-strings.txt

for marker in \
  'com/apple/android/music/player/VivoCarLyrics' \
  'vivo-car-lyrics-fix-2026-08-28' \
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
  "$BT/dexdump" -f "out/report/$HELPER_DEX_NAME" > out/report/vivo-car-lyrics-dexdump.txt
fi
sha256sum "out/report/$HELPER_DEX_NAME" > out/report/helper-dex.sha256

"$BT/zipalign" -p -f -v 4 out/app-unsigned.apk out/app-aligned.apk

printf '%s' "$SIGNING_KEY_BASE64" | base64 --decode > "$SIGNING_KEY"
chmod 600 "$SIGNING_KEY"
[[ -s "$SIGNING_KEY" ]] || { echo "Decoded signing key is empty" >&2; exit 1; }

"$BT/apksigner" sign \
  --ks "$SIGNING_KEY" --ks-type PKCS12 --ks-key-alias "$SIGNING_KEY_ALIAS" \
  --ks-pass env:SIGNING_PASSWORD --key-pass env:SIGNING_PASSWORD \
  --v4-signing-enabled false \
  --out out/apple-music-vivo-car-lyrics-debug.apk out/app-aligned.apk

"$BT/apksigner" verify --verbose --print-certs out/apple-music-vivo-car-lyrics-debug.apk \
  > out/report/patched-signature.txt
expected_signer="$(tr -d '[:space:]' < "$SIGNING_CERT_SHA256_FILE" | tr '[:upper:]' '[:lower:]')"
actual_signer="$(awk -F': ' '/Signer #1 certificate SHA-256 digest:/ {print tolower($2); exit}' \
  out/report/patched-signature.txt)"
[[ "$expected_signer" =~ ^[0-9a-f]{64}$ ]] || { echo "Invalid signing certificate pin" >&2; exit 1; }
[[ "$actual_signer" == "$expected_signer" ]] || {
  echo "Signing certificate SHA-256 mismatch" >&2
  exit 1
}
printf '%s\n' "$actual_signer" > out/report/fixed-signing-cert-sha256.txt
"$BT/aapt2" dump badging out/apple-music-vivo-car-lyrics-debug.apk \
  > out/report/patched-badging.txt
package_name="$(sed -n "s/^package: name='\([^']*\)'.*/\1/p" out/report/patched-badging.txt | head -n 1)"
[[ "$package_name" == 'com.apple.android.music' ]] || {
  echo "Unexpected package name: ${package_name:-<missing>}" >&2
  exit 1
}
printf '%s\n' "$package_name" > out/report/patched-package-name.txt
grep -Fq 'Verified using v3 scheme (APK Signature Scheme v3): true' out/report/patched-signature.txt || {
  echo "APK v3 signature verification is missing" >&2
  exit 1
}
unzip -Z1 out/apple-music-vivo-car-lyrics-debug.apk \
  | grep -E '^classes[0-9]*\.dex$' \
  > out/report/patched-dex-files.txt
grep -Fxq "$HELPER_DEX_NAME" out/report/patched-dex-files.txt
sha256sum out/apple-music-vivo-car-lyrics-debug.apk \
  > out/apple-music-vivo-car-lyrics-debug.apk.sha256

rm -f out/app-unsigned.apk out/app-aligned.apk
