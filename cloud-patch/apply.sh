#!/usr/bin/env bash
set -Eeuo pipefail

patch_root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
patch_file="$patch_root/apple-vivo-car-lyrics.patch"
helper_source="$patch_root/java/com/apple/android/music/player/VivoCarLyrics.java"
paginator_source="$patch_root/java/com/apple/android/music/player/ClusterLyricsPaginator.java"
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
[[ -f "$paginator_source" ]] || { echo "Cluster paginator missing: $paginator_source" >&2; exit 1; }

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
     "public final I(Lv3/t;I)V",
     "Lcom/apple/android/music/player/VivoCarLyrics;->onNativeMediaItem(Ljava/lang/Object;)V"),
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

native_blocks = re.findall(
    r"(?ms)^\.method public final I\(Lv3/t;I\)V\n.*?^\.end method$",
    manager_text,
)
if len(native_blocks) != 1:
    raise SystemExit("Expected exactly one native MediaItem publish method")
native_block = native_blocks[0]
native_order = (
    native_block.find("if-nez p2, :cond_4"),
    native_block.find("Lcom/apple/android/music/player/VivoCarLyrics;->onNativeMediaItem(Ljava/lang/Object;)V"),
    native_block.find("invoke-virtual {p1}, Lv3/t;->hashCode()I"),
    native_block.find("iput-object p1, p0, Lcom/apple/android/music/player/P;->j:Lv3/t;"),
)
if -1 in native_order or tuple(sorted(native_order)) != native_order:
    raise SystemExit("Native metadata hook must remain between the publish guard and stock hash/store")
PY

# The AndroidManifest.xml stays byte-for-byte native. Advertising
# com.vivo.musicwidgetmix.support.service makes Atomic Player classify Apple Music as a
# cooperation app and drop its generic MediaSession controller, which is what renders the
# progress bar. Builds 50b7498, cfd0d7e and 96312d1 all had a pure native manifest and a
# working progress bar; builds 47 and 48 added the action and lost it. Lyrics are carried by
# the MediaMetadata capability bit plus session Extras, which need no manifest change.
python3 - "$manifest" "$atomic_service_action" <<'PY'
import sys
import xml.etree.ElementTree as ET

manifest_path, action_name = sys.argv[1:]
name_attr = "{http://schemas.android.com/apk/res/android}name"
exported_attr = "{http://schemas.android.com/apk/res/android}exported"
root = ET.parse(manifest_path).getroot()
services = [item for item in root.findall("./application/service")
            if item.get(name_attr) == "com.apple.android.music.player.MediaPlaybackService"]
if len(services) != 1:
    raise SystemExit("Expected exactly one MediaPlaybackService in AndroidManifest.xml")
if services[0].get(exported_attr) != "true":
    raise SystemExit("MediaPlaybackService must remain exported for Atomic Player")

native_actions = (
    "android.media.browse.MediaBrowserService",
    "androidx.media3.session.MediaSessionService",
    "android.intent.action.MEDIA_BUTTON",
)
filters = [[action.get(name_attr) for action in intent_filter.findall("action")]
           for intent_filter in services[0].findall("intent-filter")]
matching_filters = [actions for actions in filters
                    if set(native_actions).issubset(set(actions))]
if len(matching_filters) != 1:
    raise SystemExit("Expected exactly one native MediaBrowser/MediaSession intent-filter")
for required_action in native_actions:
    if matching_filters[0].count(required_action) != 1:
        raise SystemExit("Native service action must occur exactly once in target filter: %s" %
                         required_action)

all_actions = [action.get(name_attr) for action in root.findall(".//action")]
if action_name in all_actions:
    raise SystemExit(
        "Atomic Player service action must stay out of the manifest: it disables the generic "
        "MediaSession controller and removes the progress bar (%s)" % action_name
    )
PY

mkdir -p out/report
sha256sum "$patch_file" "$helper_source" "$paginator_source" \
  > out/report/vivo-car-lyrics-patch-sha256.txt
printf 'absent-by-design: %s\n' "$atomic_service_action" \
  > out/report/atomic-player-service-action.txt
echo "Vivo car lyrics hooks applied"
