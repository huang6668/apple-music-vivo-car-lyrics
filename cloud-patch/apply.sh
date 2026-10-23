#!/usr/bin/env bash
set -Eeuo pipefail

patch_root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
patch_file="$patch_root/apple-vivo-car-lyrics.patch"
helper_source="$patch_root/java/com/apple/android/music/player/VivoCarLyrics.java"
paginator_source="$patch_root/java/com/apple/android/music/player/ClusterLyricsPaginator.java"
title_verifier="$patch_root/verify_title_hook.py"
lyrics_verifier="$patch_root/verify_lyrics_hooks.py"
manager_target=work/apktool/smali_classes2/com/apple/android/music/player/P.smali
connection_target=work/apktool/smali_classes2/com/apple/android/music/player/d0.smali
manifest=work/apktool/AndroidManifest.xml
atomic_service_action='com.vivo.musicwidgetmix.support.service'

[[ -d work/apktool ]] || { echo "Decoded tree missing: $PWD/work/apktool" >&2; exit 1; }
[[ -f "$manager_target" ]] || { echo "Playback manager Smali missing: $manager_target" >&2; exit 1; }
[[ -f "$connection_target" ]] || { echo "MediaSession callback Smali missing: $connection_target" >&2; exit 1; }
[[ -f "$manifest" ]] || { echo "Decoded AndroidManifest.xml missing: $manifest" >&2; exit 1; }
[[ -f "$patch_file" ]] || { echo "Patch file missing: $patch_file" >&2; exit 1; }
[[ -f "$helper_source" ]] || { echo "Java helper missing: $helper_source" >&2; exit 1; }
[[ -f "$paginator_source" ]] || { echo "Cluster paginator missing: $paginator_source" >&2; exit 1; }
[[ -f "$title_verifier" ]] || { echo "Title hook verifier missing: $title_verifier" >&2; exit 1; }
[[ -f "$lyrics_verifier" ]] || { echo "Lyrics hook verifier missing: $lyrics_verifier" >&2; exit 1; }
item_title_patcher="$patch_root/patch_item_title.py"
[[ -f "$item_title_patcher" ]] || { echo "Item title patcher missing: $item_title_patcher" >&2; exit 1; }
car_cover_patcher="$patch_root/patch_car_cover.py"
[[ -f "$car_cover_patcher" ]] || { echo "Car cover patcher missing: $car_cover_patcher" >&2; exit 1; }
reflection_verifier="$patch_root/verify_reflection_names.py"
[[ -f "$reflection_verifier" ]] || { echo "Reflection name verifier missing: $reflection_verifier" >&2; exit 1; }

python3 "$title_verifier" work/apktool --unpatched
python3 "$lyrics_verifier" work/apktool --unpatched
python3 "$item_title_patcher" work/apktool --unpatched
python3 "$car_cover_patcher" work/apktool --unpatched
python3 "$patch_root/verify_reflection_names.py" work/apktool --preflight
echo "Applying vivo car and Atomic Player lyrics hooks"
patch --batch --forward --fuzz=0 --strip=1 --directory=work/apktool < "$patch_file"
python3 "$title_verifier" work/apktool
python3 "$lyrics_verifier" work/apktool
python3 "$patch_root/patch_catalog_region.py" work/apktool --apply
python3 "$item_title_patcher" work/apktool --apply
python3 "$item_title_patcher" work/apktool
python3 "$car_cover_patcher" work/apktool --apply
python3 "$car_cover_patcher" work/apktool

python3 - "$manifest" "$atomic_service_action" <<'PY'
import os
import sys
import tempfile
import xml.etree.ElementTree as ET

manifest_path, action_name = sys.argv[1:]
android = "http://schemas.android.com/apk/res/android"
name_attr = "{%s}name" % android
exported_attr = "{%s}exported" % android
ET.register_namespace("android", android)

parser = ET.XMLParser(target=ET.TreeBuilder(insert_comments=True, insert_pis=True))
tree = ET.parse(manifest_path, parser=parser)
root = tree.getroot()
services = [item for item in root.findall("./application/service")
            if item.get(name_attr) == "com.apple.android.music.player.MediaPlaybackService"]
if len(services) != 1:
    raise SystemExit("Expected exactly one MediaPlaybackService in AndroidManifest.xml")
service = services[0]
if service.get(exported_attr) != "true":
    raise SystemExit("MediaPlaybackService must remain exported for Atomic Player")

browser_filters = [intent_filter for intent_filter in service.findall("intent-filter")
                   if any(action.get(name_attr) == "android.media.browse.MediaBrowserService"
                          for action in intent_filter.findall("action"))]
if len(browser_filters) != 1:
    raise SystemExit("Expected exactly one MediaBrowserService intent-filter")
target_filter = browser_filters[0]
native_actions = (
    "android.media.browse.MediaBrowserService",
    "androidx.media3.session.MediaSessionService",
    "android.intent.action.MEDIA_BUTTON",
)
target_action_names = [action.get(name_attr) for action in target_filter.findall("action")]
for required_action in native_actions:
    if target_action_names.count(required_action) != 1:
        raise SystemExit("Native service action must occur exactly once in target filter: %s" %
                         required_action)

# Remove split attributes from <manifest> so Android treats rebuilt package as a standalone APK
split_attrs = [
    "{%s}requiredSplitTypes" % android,
    "{%s}splitTypes" % android,
    "{%s}isSplitRequired" % android,
    "requiredSplitTypes",
    "splitTypes",
    "isSplitRequired",
]
manifest_modified = False
for attr in split_attrs:
    if attr in root.attrib:
        del root.attrib[attr]
        manifest_modified = True

# Remove only the Play split-delivery meta-data. Keep this list identical to rebuild.sh;
# unrelated meta-data whose name merely contains "split" must survive.
split_meta_names = (
    "com.android.vending.splits.required",
    "com.android.vending.splits",
)
app = root.find("./application")
if app is not None:
    for meta in list(app.findall("meta-data")):
        m_name = meta.get(name_attr, "")
        if m_name in split_meta_names:
            app.remove(meta)
            manifest_modified = True
        elif "split" in m_name.lower():
            print("Keeping non-delivery meta-data that mentions split: %s" % m_name)

existing = [action for action in root.findall(".//action")
            if action.get(name_attr) == action_name]
if len(existing) > 1:
    raise SystemExit("Atomic Player service action already occurs more than once")
if existing and existing[0] not in target_filter.findall("action"):
    raise SystemExit("Atomic Player service action exists outside the MediaBrowser filter")
if not existing:
    closing_indent = target_filter[-1].tail if len(target_filter) else None
    action = ET.SubElement(target_filter, "action", {name_attr: action_name})
    action.tail = closing_indent
    manifest_modified = True

if manifest_modified:
    directory = os.path.dirname(os.path.abspath(manifest_path))
    fd, temporary_path = tempfile.mkstemp(prefix="AndroidManifest.", suffix=".tmp",
                                          dir=directory)
    os.close(fd)
    try:
        tree.write(temporary_path, encoding="utf-8", xml_declaration=True)
        os.replace(temporary_path, manifest_path)
    finally:
        if os.path.exists(temporary_path):
            os.unlink(temporary_path)

root = ET.parse(manifest_path).getroot()
services = [item for item in root.findall("./application/service")
            if item.get(name_attr) == "com.apple.android.music.player.MediaPlaybackService"]
if len(services) != 1 or services[0].get(exported_attr) != "true":
    raise SystemExit("MediaPlaybackService manifest verification failed")
required = set(native_actions + (action_name,))
filters = [[action.get(name_attr) for action in intent_filter.findall("action")]
           for intent_filter in services[0].findall("intent-filter")]
matching_filters = [actions for actions in filters if required.issubset(set(actions))]
if len(matching_filters) != 1:
    raise SystemExit("Native and Atomic Player actions must share exactly one intent-filter")
for required_action in required:
    if matching_filters[0].count(required_action) != 1:
        raise SystemExit("Service action must occur exactly once in target filter: %s" %
                         required_action)
all_actions = [action.get(name_attr) for action in root.findall(".//action")]
if all_actions.count(action_name) != 1:
    raise SystemExit("Atomic Player service action must occur exactly once")
PY

mkdir -p out/report
sha256sum "$patch_file" "$helper_source" "$paginator_source" "$title_verifier" "$lyrics_verifier" \
  "$item_title_patcher" "$car_cover_patcher" "$reflection_verifier" \
  "$patch_root/patch_catalog_region.py" \
  "$patch_root/java/com/apple/android/music/player/TitleCorrectionState.java" \
  "$patch_root/java/com/apple/android/music/player/TitleCacheStore.java" \
  "$patch_root/java/com/apple/android/music/player/CatalogQueryMethod.java" \
  "$patch_root/java/com/apple/android/music/player/CatalogTitleResolver.java" \
  "$patch_root/java/com/apple/android/music/player/ChineseConverter.java" \
  > out/report/vivo-car-lyrics-patch-sha256.txt
printf '%s\n' "$atomic_service_action" > out/report/atomic-player-service-action.txt
echo "Vivo car lyrics hooks applied"
