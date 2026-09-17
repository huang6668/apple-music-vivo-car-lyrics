#!/usr/bin/env bash
set -Eeuo pipefail

compat_root="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
work="$RUNNER_TEMP/ampp-native-compat"
tools="$RUNNER_TEMP/tools"
bt="${ANDROID_SDK_ROOT:-$ANDROID_HOME}/build-tools/$BUILD_TOOLS_VERSION"
platform="${ANDROID_SDK_ROOT:-$ANDROID_HOME}/platforms/android-35/android.jar"
input="$1"
output="$2"

mkdir -p "$work/classes" "$work/dex"
java -Xmx2g -jar "$tools/apktool.jar" d -f -r "$input" -o "$work/module"
python3 "$compat_root/patch_native_directory.py" "$work/module"
javac --release 8 -classpath "$platform" -d "$work/classes" \
  "$compat_root/java/dev/amenhancer/compat/ModuleNativeLibrary.java"
jar --create --file "$work/compat.jar" -C "$work/classes" .
"$bt/d8" --min-api 30 --output "$work/dex" "$work/compat.jar"
java -Xmx2g -jar "$tools/apktool.jar" b "$work/module" -o "$work/module-unsigned.apk"
python3 - "$work/module-unsigned.apk" "$work/dex/classes.dex" <<'PY'
import pathlib
import sys
import zipfile

with zipfile.ZipFile(sys.argv[1], "a") as apk:
    number = 2
    while "classes%d.dex" % number in apk.namelist():
        number += 1
    apk.writestr("classes%d.dex" % number, pathlib.Path(sys.argv[2]).read_bytes())
    for entry in ("META-INF/xposed/module.prop", "META-INF/xposed/java_init.list",
                  "META-INF/xposed/scope.list", "lib/arm64-v8a/libdexkit.so"):
        if entry not in apk.namelist():
            raise SystemExit("Rebuilt module lost " + entry)
PY
"$bt/zipalign" -f -p 4 "$work/module-unsigned.apk" "$work/module-aligned.apk"
"$bt/apksigner" sign \
  --ks "$RUNNER_TEMP/apple-music-vivo-car-lyrics-signing.p12" --ks-type PKCS12 \
  --ks-key-alias apple-music-vivo-car-lyrics \
  --ks-pass env:SIGNING_PASSWORD --key-pass env:SIGNING_PASSWORD \
  --v4-signing-enabled false --out "$output" "$work/module-aligned.apk"
"$bt/apksigner" verify "$output"
