#!/usr/bin/env bash
set -Eeuo pipefail

[[ -d work/apktool ]] || { echo "Decoded tree missing" >&2; exit 1; }
mkdir -p out/report
BT="${ANDROID_SDK_ROOT:-$ANDROID_HOME}/build-tools/${BUILD_TOOLS_VERSION}"
PLATFORM="${ANDROID_SDK_ROOT:-$ANDROID_HOME}/platforms/android-30/android.jar"
PATCH_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HELPER_SOURCE="$PATCH_ROOT/java/com/apple/android/music/player/VivoCarLyrics.java"
PAGINATOR_SOURCE="$PATCH_ROOT/java/com/apple/android/music/player/ClusterLyricsPaginator.java"
HELPER_WORK="$RUNNER_TEMP/vivo-car-lyrics-helper"
SIGNING_KEY="$RUNNER_TEMP/apple-music-vivo-car-lyrics-signing.p12"
SIGNING_CERT_SHA256_FILE="$PATCH_ROOT/../config/signing-cert-sha256.txt"
SIGNING_KEY_ALIAS=apple-music-vivo-car-lyrics
HELPER_DEX_NAME=""
APK_ENTRY_LIST="$HELPER_WORK/apk-entries.txt"
ATOMIC_SERVICE_ACTION='com.vivo.musicwidgetmix.support.service'
FINAL_MANIFEST_DIR="$HELPER_WORK/final-manifest"
FINAL_HELPER_DEX="$HELPER_WORK/final-helper.dex"

[[ -f "$PLATFORM" ]] || { echo "Android 30 platform is missing" >&2; exit 1; }
[[ -f "$HELPER_SOURCE" ]] || { echo "VivoCarLyrics.java is missing" >&2; exit 1; }
[[ -f "$PAGINATOR_SOURCE" ]] || { echo "ClusterLyricsPaginator.java is missing" >&2; exit 1; }
[[ -f "$SIGNING_CERT_SHA256_FILE" ]] || { echo "Signing certificate pin is missing" >&2; exit 1; }
[[ -n "${SIGNING_KEY_BASE64:-}" ]] || { echo "ANDROID_SIGNING_KEY_BASE64 secret is missing" >&2; exit 1; }
[[ -n "${SIGNING_PASSWORD:-}" ]] || { echo "ANDROID_SIGNING_PASSWORD secret is missing" >&2; exit 1; }
umask 077
rm -rf "$HELPER_WORK"
mkdir -p "$HELPER_WORK/classes" "$HELPER_WORK/dex"

find work/apktool -path '*/META-INF/*' -type f \
  \( -iname '*.RSA' -o -iname '*.DSA' -o -iname '*.EC' -o -iname '*.SF' -o -iname 'MANIFEST.MF' \) -delete

java -Xmx4g -jar "$APKTOOL_JAR" b work/apktool -o out/app-unsigned.apk

javac --release 8 -classpath "$PLATFORM" -d "$HELPER_WORK/classes" \
  "$HELPER_SOURCE" "$PAGINATOR_SOURCE"
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

HELPER_MARKERS=(
  'com/apple/android/music/player/VivoCarLyrics' \
  'com/apple/android/music/player/ClusterLyricsPaginator' \
  'vivo-car-cluster-atomic-extras-r12-2026-09-01' \
  'onNativeMediaItem' \
  'ucar.media.metadata.LYRICS_LINE' \
  'ucar.media.metadata.LYRICS_WHOLE' \
  'ucar.media.metadata.LYRICS_STATUS' \
  'music.media.extras.LYRIC' \
  'music.media.extras.LYRIC_IS_ALLOWED' \
  'music.media.extras.NOTICE_CAR' \
  'vivomusicmix.meida.extra.key.action' \
  'vivomusicmix.extra.lrc_change' \
  'vivomusicmix.extra.key.meidia_id' \
  'vivomusicmix.extra.key.lyric' \
  'vivomusicmix.media.metadata.support_event' \
  'com.vivo.musicwidgetmix' \
  'android.media.metadata.MEDIA_ID' \
  'com.apple.android.music.playback.metadata.METADATA_KEY_MEDIA_ID' \
  'com.apple.android.music.playback.metadata.ITEM_QUEUE_ID'
)
for marker in "${HELPER_MARKERS[@]}"; do
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
"$BT/aapt2" dump xmltree out/apple-music-vivo-car-lyrics-debug.apk --file AndroidManifest.xml \
  > out/report/patched-manifest.txt
python3 - out/report/badging.txt out/report/patched-badging.txt <<'PY'
import re
import sys

def values(path):
    text = open(path, encoding="utf-8").read()
    package = re.search(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']*)'", text, re.M)
    minimum = re.search(r"^minSdkVersion:'([^']+)'", text, re.M)
    if not package or not minimum:
        raise SystemExit("Could not read package/version/minSdk from %s" % path)
    return package.groups() + (minimum.group(1),)

original = values(sys.argv[1])
patched = values(sys.argv[2])
if original != patched:
    raise SystemExit("Original and patched package metadata differ: %r != %r" % (original, patched))
if patched[0] != "com.apple.android.music":
    raise SystemExit("Unexpected package name: %s" % patched[0])
PY
package_name="$(sed -n "s/^package: name='\([^']*\)'.*/\1/p" out/report/patched-badging.txt | head -n 1)"
printf '%s\n' "$package_name" > out/report/patched-package-name.txt
rm -rf "$FINAL_MANIFEST_DIR"
java -Xmx4g -jar "$APKTOOL_JAR" d -f \
  out/apple-music-vivo-car-lyrics-debug.apk -o "$FINAL_MANIFEST_DIR" \
  > out/report/final-apktool.log 2>&1
cp "$FINAL_MANIFEST_DIR/AndroidManifest.xml" out/report/patched-manifest-decoded.xml
python3 - "$FINAL_MANIFEST_DIR/AndroidManifest.xml" "$ATOMIC_SERVICE_ACTION" <<'PY'
import sys
import xml.etree.ElementTree as ET

manifest_path, action_name = sys.argv[1:]
name_attr = "{http://schemas.android.com/apk/res/android}name"
exported_attr = "{http://schemas.android.com/apk/res/android}exported"
root = ET.parse(manifest_path).getroot()
services = [item for item in root.findall("./application/service")
            if item.get(name_attr) == "com.apple.android.music.player.MediaPlaybackService"]
if len(services) != 1 or services[0].get(exported_attr) != "true":
    raise SystemExit("MediaPlaybackService rebuilt manifest verification failed")
required = {
    action_name,
    "android.media.browse.MediaBrowserService",
    "androidx.media3.session.MediaSessionService",
    "android.intent.action.MEDIA_BUTTON",
}
filters = [[action.get(name_attr) for action in intent_filter.findall("action")]
           for intent_filter in services[0].findall("intent-filter")]
matching_filters = [actions for actions in filters if required.issubset(set(actions))]
if len(matching_filters) != 1:
    raise SystemExit("Native and Atomic Player actions must share one rebuilt intent-filter")
for required_action in required:
    if matching_filters[0].count(required_action) != 1:
        raise SystemExit("Rebuilt service action must occur exactly once in target filter: %s" %
                         required_action)
all_actions = [action.get(name_attr) for action in root.findall(".//action")]
if all_actions.count(action_name) != 1:
    raise SystemExit("Atomic Player service action must occur exactly once in final manifest")
PY
printf '%s\n' "$ATOMIC_SERVICE_ACTION" > out/report/verified-atomic-player-action.txt
grep -Fq 'Verified using v3 scheme (APK Signature Scheme v3): true' out/report/patched-signature.txt || {
  echo "APK v3 signature verification is missing" >&2
  exit 1
}
grep -Fq 'Number of signers: 1' out/report/patched-signature.txt || {
  echo "Patched APK must have exactly one signer" >&2
  exit 1
}
signer_digest_count="$(grep -Ec '^Signer #[0-9]+ certificate SHA-256 digest:' \
  out/report/patched-signature.txt || true)"
[[ "$signer_digest_count" == 1 ]] || {
  echo "Patched APK must expose exactly one signer certificate digest" >&2
  exit 1
}
unzip -p out/apple-music-vivo-car-lyrics-debug.apk "$HELPER_DEX_NAME" > "$FINAL_HELPER_DEX"
cmp -s "out/report/$HELPER_DEX_NAME" "$FINAL_HELPER_DEX" || {
  echo "Helper DEX changed after APK signing" >&2
  exit 1
}
strings "$FINAL_HELPER_DEX" > out/report/final-vivo-car-lyrics-helper-strings.txt
for marker in "${HELPER_MARKERS[@]}"; do
  grep -Fq "$marker" out/report/final-vivo-car-lyrics-helper-strings.txt || {
    echo "Missing final helper marker: $marker" >&2
    exit 1
  }
done
sha256sum "$FINAL_HELPER_DEX" > out/report/final-helper-dex.sha256
python3 - "$FINAL_MANIFEST_DIR" <<'PY'
import glob
import re
import sys

root = sys.argv[1]
manager_paths = glob.glob(root + "/smali*/com/apple/android/music/player/P.smali")
if len(manager_paths) != 1:
    raise SystemExit("Expected exactly one final MediaPlaybackManager P.smali, found %d" % len(manager_paths))
text = open(manager_paths[0], encoding="utf-8").read()
connection_paths = glob.glob(root + "/smali*/com/apple/android/music/player/c0.smali")
if len(connection_paths) != 1:
    raise SystemExit("Expected exactly one final MediaSession callback c0.smali, found %d" % len(connection_paths))
connection_text = open(connection_paths[0], encoding="utf-8").read()

checks = (
    (text,
     "public final I(Lv3/t;I)V",
     "Lcom/apple/android/music/player/VivoCarLyrics;->onNativeMediaItem(Ljava/lang/Object;)V"),
    (text,
     "public final onCurrentItemChanged(Lcom/apple/android/music/playback/controller/MediaPlayerController;Lcom/apple/android/music/playback/model/PlayerQueueItem;Lcom/apple/android/music/playback/model/PlayerQueueItem;)V",
     "Lcom/apple/android/music/player/VivoCarLyrics;->onCurrentItemChanged(Ljava/lang/Object;Ljava/lang/Object;)V"),
    (text,
     "public final onMetadataUpdated(Lcom/apple/android/music/playback/controller/MediaPlayerController;Lcom/apple/android/music/playback/model/PlayerQueueItem;)V",
     "Lcom/apple/android/music/player/VivoCarLyrics;->onMetadataUpdated(Ljava/lang/Object;Ljava/lang/Object;)V"),
    (text,
     "public final onPlaybackError(Lcom/apple/android/music/playback/controller/MediaPlayerController;Lcom/apple/android/music/playback/model/MediaPlayerException;)V",
     "Lcom/apple/android/music/player/VivoCarLyrics;->onPlaybackError(Ljava/lang/Object;)V"),
    (text,
     "public final seekTo(J)V",
     "Lcom/apple/android/music/player/VivoCarLyrics;->onSeek(Ljava/lang/Object;J)V"),
    (connection_text,
     "public final e(LE3/B2;LE3/B2$e;)V",
     "Lcom/apple/android/music/player/VivoCarLyrics;->onAtomicControllerConnected(Ljava/lang/String;)V"),
)

for source, signature, call in checks:
    blocks = re.findall(
        r"(?ms)^\.method " + re.escape(signature) + r"\n.*?^\.end method$",
        source,
    )
    if len(blocks) != 1:
        raise SystemExit("Expected exactly one final Smali method: %s (found %d)" %
                         (signature, len(blocks)))
    invocation = (r"^\s*invoke-static(?:/range)?\s+\{[^}]*\},\s*" +
                  re.escape(call) + r"\s*$")
    method_count = len(re.findall(invocation, blocks[0], re.M))
    file_count = len(re.findall(invocation, source, re.M))
    if method_count != 1 or file_count != 1:
        raise SystemExit(
            "Final hook must occur exactly once in %s: %s (method=%d, file=%d)" %
            (signature, call, method_count, file_count)
        )

native_blocks = re.findall(
    r"(?ms)^\.method public final I\(Lv3/t;I\)V\n.*?^\.end method$",
    text,
)
if len(native_blocks) != 1:
    raise SystemExit("Expected exactly one final native MediaItem publish method")
native_block = native_blocks[0]
native_order = (
    native_block.find("if-nez p2, :cond_4"),
    native_block.find("Lcom/apple/android/music/player/VivoCarLyrics;->onNativeMediaItem(Ljava/lang/Object;)V"),
    native_block.find("invoke-virtual {p1}, Lv3/t;->hashCode()I"),
    native_block.find("iput-object p1, p0, Lcom/apple/android/music/player/P;->j:Lv3/t;"),
)
if -1 in native_order or tuple(sorted(native_order)) != native_order:
    raise SystemExit("Final native metadata hook is outside the guarded stock publish path")
PY
printf '%s\n' "$(find "$FINAL_MANIFEST_DIR" -path '*/com/apple/android/music/player/P.smali' -print -quit)" \
  > out/report/final-manager-smali-path.txt
printf '%s\n' "$(find "$FINAL_MANIFEST_DIR" -path '*/com/apple/android/music/player/c0.smali' -print -quit)" \
  > out/report/final-connection-smali-path.txt
unzip -Z1 out/apple-music-vivo-car-lyrics-debug.apk \
  | grep -E '^classes[0-9]*\.dex$' \
  > out/report/patched-dex-files.txt
grep -Fxq "$HELPER_DEX_NAME" out/report/patched-dex-files.txt
sha256sum out/apple-music-vivo-car-lyrics-debug.apk \
  > out/apple-music-vivo-car-lyrics-debug.apk.sha256

rm -f out/app-unsigned.apk out/app-aligned.apk
