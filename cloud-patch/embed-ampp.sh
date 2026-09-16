#!/usr/bin/env bash
#
# Optional, opt-in artifact: take the APK the main pipeline already rebuilt and signed,
# and wrap it in an LSPatch shell with the AM++ module baked in.
#
# This exists because the two projects want opposite things. Our patch lives in the APK's
# own smali/dex, so it must be inside the app. AM++ is an LSPosed module, so it must load
# *around* the app, which is exactly what an LSPatch shell provides. Wrapping ours with
# theirs is a shell around a patched app; patching theirs would mean reaching into the
# nested origin.apk of a 3-split APK Set, which is the harder direction and was rejected.
#
# Nothing here feeds back into the main build. The input is the finished, verified
# artifact, the output is a separate file, and a failure in this path cannot change what
# the main Release produces.
set -Eeuo pipefail

SRC_APK="out/apple-music-vivo-car-lyrics-debug.apk"
EMBED_OUT_DIR="out/embed-ampp"
EMBED_APK_NAME="apple-music-vivo-car-lyrics-ampp-lspatched.apk"
BT="${ANDROID_SDK_ROOT:-$ANDROID_HOME}/build-tools/${BUILD_TOOLS_VERSION}"
SIGNING_KEY="$RUNNER_TEMP/apple-music-vivo-car-lyrics-signing.p12"
SIGNING_KEY_ALIAS=apple-music-vivo-car-lyrics
SIGNING_CERT_SHA256_FILE="$(dirname "${BASH_SOURCE[0]}")/../config/signing-cert-sha256.txt"
LSPATCH_JAR="${LSPATCH_JAR:-$RUNNER_TEMP/tools/lspatch.jar}"
AMPP_MODULE_APK="${AMPP_MODULE_APK:-$RUNNER_TEMP/tools/AM-plus-plus-v1.5.5.apk}"
SIG_BYPASS_LEVEL="${SIG_BYPASS_LEVEL:-2}"
PATCH_WORK="$RUNNER_TEMP/embed-ampp-work"
REPORT="$EMBED_OUT_DIR/report"

[[ -f "$SRC_APK" ]] || { echo "Patched APK is missing: $SRC_APK" >&2; exit 1; }
[[ -f "$BT/apksigner" ]] || { echo "apksigner is missing at $BT" >&2; exit 1; }
[[ -f "$LSPATCH_JAR" ]] || { echo "lspatch.jar is missing: $LSPATCH_JAR" >&2; exit 1; }
[[ -f "$AMPP_MODULE_APK" ]] || { echo "AM++ module APK is missing: $AMPP_MODULE_APK" >&2; exit 1; }
[[ -f "$SIGNING_CERT_SHA256_FILE" ]] || { echo "Signing certificate pin is missing" >&2; exit 1; }
[[ -n "${SIGNING_KEY_BASE64:-}" ]] || { echo "ANDROID_SIGNING_KEY_BASE64 secret is missing" >&2; exit 1; }
[[ -n "${SIGNING_PASSWORD:-}" ]] || { echo "ANDROID_SIGNING_PASSWORD secret is missing" >&2; exit 1; }

# The module is an LSPosed module in the libxposed layout; its own metadata declares the
# API level it was built against, and the loader must be able to serve that API. Checked
# here rather than after a two-minute patch, because a mismatch is the one failure that
# would otherwise look like "the module just does nothing" at runtime.
python3 - "$AMPP_MODULE_APK" "$LSPATCH_JAR" <<'PY'
import sys
import zipfile

module_path, jar_path = sys.argv[1:]
with zipfile.ZipFile(module_path) as apk:
    names = set(apk.namelist())
    for required in ("META-INF/xposed/module.prop", "META-INF/xposed/scope.list"):
        if required not in names:
            raise SystemExit("%s is not an LSPosed module: %s is absent" % (module_path, required))
    prop = apk.read("META-INF/xposed/module.prop").decode("utf-8")
    scope = apk.read("META-INF/xposed/scope.list").decode("utf-8")

values = dict(
    line.split("=", 1) for line in prop.splitlines() if "=" in line
)
target_api = values.get("targetApiVersion")
min_api = values.get("minApiVersion")
if min_api != target_api:
    raise SystemExit("Unexpected module API range: min=%s target=%s" % (min_api, target_api))
if "com.apple.android.music" not in scope.split():
    raise SystemExit("Module does not declare com.apple.android.music in its scope")

with zipfile.ZipFile(jar_path) as jar:
    jar_names = set(jar.namelist())
for entry in ("assets/lspatch/loader.dex", "assets/lspatch/metaloader.dex",
              "assets/lspatch/so/arm64-v8a/liblspatch.so"):
    if entry not in jar_names:
        raise SystemExit("lspatch.jar is missing %s" % entry)

print("module package=%s minApi=%s targetApi=%s" % (
    values.get("package", "unknown"), min_api, target_api))
PY

umask 077
rm -rf "$PATCH_WORK"
mkdir -p "$PATCH_WORK/in" "$PATCH_WORK/out" "$EMBED_OUT_DIR" "$REPORT"
cp "$SRC_APK" "$PATCH_WORK/in/apple-music-vivo-car-lyrics-debug.apk"
cp "$AMPP_MODULE_APK" "$PATCH_WORK/in/AM-plus-plus.apk"

printf '%s' "$SIGNING_KEY_BASE64" | base64 --decode > "$SIGNING_KEY"
chmod 600 "$SIGNING_KEY"
[[ -s "$SIGNING_KEY" ]] || { echo "Decoded signing key is empty" >&2; exit 1; }

# -m embeds the module; --manager would instead resolve modules live from an installed
# manager app, and the two are mutually exclusive by construction. Level 2 signature
# bypass (pm + openat) is what NPatch's own embedded build ships with; it keeps the
# original Apple signature readable at runtime so AM++ cannot notice the rewrite.
java -Xmx4g -jar "$LSPATCH_JAR" \
  -m "$PATCH_WORK/in/AM-plus-plus.apk" \
  -k "$SIGNING_KEY" "$SIGNING_PASSWORD" "$SIGNING_KEY_ALIAS" "$SIGNING_PASSWORD" \
  -l "$SIG_BYPASS_LEVEL" \
  -f -o "$PATCH_WORK/out" \
  "$PATCH_WORK/in/apple-music-vivo-car-lyrics-debug.apk" \
  2>&1 | tee "$REPORT/lspatch.log"

mapfile -t produced < <(find "$PATCH_WORK/out" -maxdepth 1 -type f -name '*-lspatched.apk' -print)
[[ "${#produced[@]}" == 1 ]] || {
  echo "Expected exactly one lspatched APK, found ${#produced[@]}" >&2
  exit 1
}
LSPATCHED_APK="${produced[0]}"

# LSPatch signs as it writes, but its signer is registered with minSdkVersion 28 -- above
# the v2 threshold -- so which schemes survive is not something to assume. Re-signing with
# the same fixed key costs one pass and puts the output on exactly the footing the main
# artifact is on: v2 + v3, one signer, pinned certificate.
"$BT/apksigner" sign \
  --ks "$SIGNING_KEY" --ks-type PKCS12 --ks-key-alias "$SIGNING_KEY_ALIAS" \
  --ks-pass env:SIGNING_PASSWORD --key-pass env:SIGNING_PASSWORD \
  --v4-signing-enabled false \
  --out "$EMBED_OUT_DIR/$EMBED_APK_NAME" "$LSPATCHED_APK"

"$BT/apksigner" verify --verbose --print-certs "$EMBED_OUT_DIR/$EMBED_APK_NAME" \
  > "$REPORT/embed-signature.txt"

expected_signer="$(tr -d '[:space:]' < "$SIGNING_CERT_SHA256_FILE" | tr '[:upper:]' '[:lower:]')"
actual_signer="$(awk -F': ' '/Signer #1 certificate SHA-256 digest:/ {print tolower($2); exit}' \
  "$REPORT/embed-signature.txt")"
# Every one of these prints the whole verification report on the way out. A signature
# failure here is rare and its cause is always in the report, not in the sentence.
signature_failure() {
  echo "$1" >&2
  echo "--- apksigner verify report ---" >&2
  cat "$REPORT/embed-signature.txt" >&2
  exit 1
}

[[ "$expected_signer" =~ ^[0-9a-f]{64}$ ]] || { echo "Invalid signing certificate pin" >&2; exit 1; }
[[ "$actual_signer" == "$expected_signer" ]] || {
  signature_failure "Embedded APK signing certificate SHA-256 mismatch"
}
# v3 is the requirement, matching rebuild.sh's own check on the main artifact. v2 is not
# asserted: apksigner picks the schemes from the apk's minSdkVersion, and the loader raises
# it to 28, above the v2-only range -- so demanding v2 here would fail a build that installs
# perfectly well. Both may be absent only if the file is effectively unsigned.
grep -Fq 'Verified using v3 scheme (APK Signature Scheme v3): true' "$REPORT/embed-signature.txt" || {
  signature_failure "Embedded APK v3 signature verification is missing"
}
grep -Fq 'Number of signers: 1' "$REPORT/embed-signature.txt" || {
  signature_failure "Embedded APK must have exactly one signer"
}

"$BT/aapt2" dump badging "$EMBED_OUT_DIR/$EMBED_APK_NAME" > "$REPORT/embed-badging.txt"
"$BT/aapt2" dump xmltree "$EMBED_OUT_DIR/$EMBED_APK_NAME" --file AndroidManifest.xml \
  > "$REPORT/embed-manifest.txt"

# Three things have to be true of the wrapped APK, and all three are checked against the
# finished file rather than against the patch log:
#   - our r38 helper dex still rides along, byte-for-byte as it was signed;
#   - the vivo Atomic action still reaches the head unit, which is the whole reason this
#     direction was chosen -- the shell rewrites appComponentFactory only;
#   - the shell and the module are actually in there.
python3 - "$EMBED_OUT_DIR/$EMBED_APK_NAME" "$SRC_APK" <<'PY'
import json
import sys
import zipfile

embed_path, src_path = sys.argv[1:]
ATOMIC_ACTION = "com.vivo.musicwidgetmix.support.service"
PROXY_FACTORY = "org.lsposed.lspatch.metaloader.LSPAppComponentFactoryStub"

src = zipfile.ZipFile(src_path)
src_helpers = sorted(n for n in src.namelist()
                     if n.startswith("classes") and n.endswith(".dex"))
if not src_helpers:
    raise SystemExit("Source APK has no dex entries")

with zipfile.ZipFile(embed_path) as apk:
    names = set(apk.namelist())
    for entry in ("assets/lspatch/origin.apk",
                  "assets/lspatch/loader.dex",
                  "assets/lspatch/config.json",
                  "assets/lspatch/modules/dev.amenhancer.module.apk"):
        if entry not in names:
            raise SystemExit("Embedded APK is missing %s" % entry)
    if "classes.dex" not in names:
        raise SystemExit("Embedded APK has no metaloader classes.dex")

    config = json.loads(apk.read("assets/lspatch/config.json").decode("utf-8"))
    if config.get("useManager"):
        raise SystemExit("Embedded APK was patched in manager mode")
    if config.get("sigBypassLevel") != 2:
        raise SystemExit("Unexpected signature bypass level: %r" % config.get("sigBypassLevel"))
    # config records the *original* factory, which the loader restores after it brings the
    # framework up; the proxy stub is what the manifest points at instead (checked below).
    # Seeing the proxy here would mean the original was lost and the app would never resume.
    if config.get("appComponentFactory") == PROXY_FACTORY:
        raise SystemExit("config recorded the proxy factory instead of the app's own")
    if "originalSignature" not in config or not config["originalSignature"]:
        raise SystemExit("Embedded APK did not record the original signature")

    # The whole APK is kept intact inside the shell, so the vivo action must still be
    # findable in the nested copy -- that is what the manifest check below then confirms
    # is still declared on MediaPlaybackService.
    origin = apk.read("assets/lspatch/origin.apk")
    if ATOMIC_ACTION.encode("utf-8") not in origin:
        raise SystemExit("Nested origin.apk lost the vivo Atomic service action")

    # The helper dex the source APK carried must survive into the nested copy unchanged.
    origin_zip = zipfile.ZipFile(__import__("io").BytesIO(origin))
    origin_names = set(origin_zip.namelist())
    for helper in src_helpers:
        if helper not in origin_names:
            raise SystemExit("Nested origin.apk is missing %s" % helper)
        if origin_zip.read(helper) != src.read(helper):
            raise SystemExit("Nested %s differs from the source APK's" % helper)

    print("nested origin.apk carries %d dex entries unchanged" % len(src_helpers))
PY

for marker in 'com.vivo.musicwidgetmix.support.service' \
              'LSPAppComponentFactoryStub' \
              'com.apple.android.music.player.MediaPlaybackService'; do
  grep -Fq "$marker" "$REPORT/embed-manifest.txt" || {
    echo "Missing embedded manifest marker: $marker" >&2
    exit 1
  }
done

# The Atomic action must still sit on the same intent-filter the main pipeline verified,
# otherwise the head unit will not find the service through the shell.
python3 - "$REPORT/embed-manifest.txt" <<'PY'
import sys

text = open(sys.argv[1], encoding="utf-8").read()
if text.count('com.vivo.musicwidgetmix.support.service') != 1:
    raise SystemExit("Atomic action must occur exactly once in the embedded manifest")
if text.count('A: android:name(0x01010003)="com.apple.android.music.player.MediaPlaybackService"') != 1:
    raise SystemExit("Expected exactly one MediaPlaybackService declaration")
PY

# LSPatch stores the nested original and the native library page-aligned so they can be
# mapped straight out of the apk. Re-signing must not have moved them: a shifted origin.apk
# is a straightforward install-time or first-launch failure, but one that looks like a bad
# module rather than a broken shell, so it is worth catching here.
python3 - "$EMBED_OUT_DIR/$EMBED_APK_NAME" <<'PY'
import struct
import sys
import zipfile

path = sys.argv[1]
ALIGNED = ("assets/lspatch/origin.apk", "assets/lspatch/so/arm64-v8a/liblspatch.so")

with open(path, "rb") as f:
    data = f.read()

zf = zipfile.ZipFile(path)
for name in ALIGNED:
    info = zf.getinfo(name)
    offset = info.header_offset
    if data[offset:offset + 4] != b"PK\x03\x04":
        raise SystemExit("Bad local header for %s" % name)
    name_len, extra_len = struct.unpack("<HH", data[offset + 26:offset + 30])
    data_start = offset + 30 + name_len + extra_len
    if data_start % 4096 != 0:
        raise SystemExit("%s is not 4096-byte aligned (offset %d)" % (name, data_start))
    if info.compress_type != zipfile.ZIP_STORED:
        raise SystemExit("%s must be stored, not deflated" % name)
    print("%s stored at aligned offset %d" % (name, data_start))
PY

sha256sum "$EMBED_OUT_DIR/$EMBED_APK_NAME" > "$EMBED_OUT_DIR/$EMBED_APK_NAME.sha256"
sha256sum "$LSPATCH_JAR" "$AMPP_MODULE_APK" > "$REPORT/embed-inputs.sha256"
printf '%s\n' "$SIG_BYPASS_LEVEL" > "$REPORT/embed-sigbypass-level.txt"
echo "Embedded AM++ APK written: $EMBED_OUT_DIR/$EMBED_APK_NAME"
