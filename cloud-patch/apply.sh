#!/usr/bin/env bash
set -Eeuo pipefail

patch_root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
patch_file="$patch_root/apple-vivo-car-lyrics.patch"
helper_source="$patch_root/java/com/apple/android/music/player/VivoCarLyrics.java"
manager_target=work/apktool/smali_classes2/com/apple/android/music/player/P.smali
connection_target=work/apktool/smali_classes2/com/apple/android/music/player/c0.smali
manifest=work/apktool/AndroidManifest.xml
atomic_service_action='com.vivo.musicwidgetmix.support.service'

[[ -d work/apktool ]] || { echo "Decoded tree missing: $PWD/work/apktool" >&2; exit 1; }
[[ -f "$manager_target" ]] || { echo "Playback manager Smali missing: $manager_target" >&2; exit 1; }
[[ -f "$connection_target" ]] || { echo "MediaSession callback Smali missing: $connection_target" >&2; exit 1; }
[[ -f "$manifest" ]] || { echo "Decoded AndroidManifest.xml missing: $manifest" >&2; exit 1; }
[[ -f "$patch_file" ]] || { echo "Patch file missing: $patch_file" >&2; exit 1; }
[[ -f "$helper_source" ]] || { echo "Java helper missing: $helper_source" >&2; exit 1; }

echo "Applying vivo car and Atomic Player lyrics hooks"
patch --batch --forward --strip=1 --directory=work/apktool < "$patch_file"

python3 - "$manager_target" "$connection_target" <<'PY'
import re
import sys

manager_path, connection_path = sys.argv[1:]
manager_text = open(manager_path, encoding="utf-8").read()
connection_text = open(connection_path, encoding="utf-8").read()

checks = (
    (manager_text,
     "public final onCurrentItemChanged(Lcom/apple/android/music/playback/controller/MediaPlayerController;Lcom/apple/android/music/playback/model/PlayerQueueItem;Lcom/apple/android/music/playback/model/PlayerQueueItem;)V",
     "Lcom/apple/android/music/player/VivoCarLyrics;->onCurrentItemChanged(Ljava/lang/Object;Ljava/lang/Object;)V"),
    (manager_text,
     "public final onMetadataUpdated(Lcom/apple/android/music/playback/controller/MediaPlayerController;Lcom/apple/android/music/playback/model/PlayerQueueItem;)V",
     "Lcom/apple/android/music/player/VivoCarLyrics;->onMetadataUpdated(Ljava/lang/Object;Ljava/lang/Object;)V"),
    (manager_text,
     "public final onPlaybackError(Lcom/apple/android/music/playback/controller/MediaPlayerController;Lcom/apple/android/music/playback/model/MediaPlayerException;)V",
     "Lcom/apple/android/music/player/VivoCarLyrics;->onPlaybackError(Ljava/lang/Object;)V"),
    (manager_text,
     "public final seekTo(J)V",
     "Lcom/apple/android/music/player/VivoCarLyrics;->onSeek(Ljava/lang/Object;J)V"),
    (connection_text,
     "public final e(LE3/B2;LE3/B2$e;)V",
     "Lcom/apple/android/music/player/VivoCarLyrics;->onAtomicControllerConnected(Ljava/lang/String;)V"),
)

for text, signature, call in checks:
    blocks = re.findall(
        r"(?ms)^\.method " + re.escape(signature) + r"\n.*?^\.end method$",
        text,
    )
    if len(blocks) != 1:
        raise SystemExit("Expected exactly one Smali method: %s (found %d)" %
                         (signature, len(blocks)))
    invocation = (r"^\s*invoke-static(?:/range)?\s+\{[^}]*\},\s*" +
                  re.escape(call) + r"\s*$")
    method_count = len(re.findall(invocation, blocks[0], re.M))
    file_count = len(re.findall(invocation, text, re.M))
    if method_count != 1 or file_count != 1:
        raise SystemExit(
            "Hook must occur exactly once in %s: %s (method=%d, file=%d)" %
            (signature, call, method_count, file_count)
        )
PY

mkdir -p out/report
sha256sum "$patch_file" "$helper_source" \
  > out/report/vivo-car-atomic-lyrics-patch-sha256.txt
echo "Vivo car and Atomic Player lyrics hooks applied"
