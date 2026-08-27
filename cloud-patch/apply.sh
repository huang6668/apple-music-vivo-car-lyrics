#!/usr/bin/env bash
set -Eeuo pipefail

patch_root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
patch_file="$patch_root/apple-vivo-car-lyrics.patch"
helper_source="$patch_root/java/com/apple/android/music/player/VivoCarLyrics.java"
target=work/apktool/smali_classes2/com/apple/android/music/player/P.smali

[[ -d work/apktool ]] || { echo "Decoded tree missing: $PWD/work/apktool" >&2; exit 1; }
[[ -f "$target" ]] || { echo "Playback manager Smali missing: $target" >&2; exit 1; }
[[ -f "$patch_file" ]] || { echo "Patch file missing: $patch_file" >&2; exit 1; }
[[ -f "$helper_source" ]] || { echo "Java helper missing: $helper_source" >&2; exit 1; }

echo "Applying vivo car lyrics hooks to $target"
patch --batch --forward --strip=1 --directory=work/apktool < "$patch_file"

for marker in \
  'VivoCarLyrics;->onCurrentItemChanged' \
  'VivoCarLyrics;->onMetadataUpdated' \
  'VivoCarLyrics;->onPlaybackError' \
  'VivoCarLyrics;->onSeek(Ljava/lang/Object;J)V'; do
  grep -Fq "$marker" "$target" || {
    echo "Patched Smali marker missing: $marker" >&2
    exit 1
  }
done

mkdir -p out/report
sha256sum "$patch_file" "$helper_source" \
  > out/report/vivo-car-lyrics-patch-sha256.txt
echo "Vivo car lyrics hooks applied"
