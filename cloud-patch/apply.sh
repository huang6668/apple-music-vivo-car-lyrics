#!/usr/bin/env bash
set -Eeuo pipefail

[[ -d work/apktool ]] || { echo "Decoded tree missing" >&2; exit 1; }
patch_root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
patch_file="$patch_root/apple-vivo-car-lyrics.patch"

git apply --check --directory=work/apktool "$patch_file"
git apply --directory=work/apktool "$patch_file"

target=work/apktool/smali_classes2/com/apple/android/music/player/P.smali
grep -Fq 'VivoCarLyrics;->onCurrentItemChanged' "$target"
grep -Fq 'VivoCarLyrics;->onMetadataUpdated' "$target"
grep -Fq 'VivoCarLyrics;->onPlaybackError' "$target"

mkdir -p out/report
sha256sum "$patch_file" "$patch_root/java/com/apple/android/music/player/VivoCarLyrics.java" \
  > out/report/vivo-car-lyrics-patch-sha256.txt
