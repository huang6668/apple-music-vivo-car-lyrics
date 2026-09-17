#!/usr/bin/env bash
#
# Optional, opt-in artifact: take the APK the main pipeline already rebuilt and signed,
# and wrap it in an NPatch shell with the AM++ module baked in.
#
# This exists because the two projects want opposite things. Our patch lives in the APK's
# own smali/dex, so it must be inside the app. AM++ is an LSPosed module, so it must load
# *around* the app, which is exactly what an NPatch shell provides. Wrapping ours with
# theirs is a shell around a patched app; patching theirs would mean reaching into the
# nested origin.apk of a 3-split APK Set, which is the harder direction and was rejected.
#
# Use the same framework family as the author's embedded release. This pinned NPatch
# build is not identical to the author's build, and successful packaging does not prove
# runtime compatibility. Title correction needs device-side module/Hook diagnostics.
# Keep outputLog enabled for that investigation; do not pass its boolean toggle.
#
# Nothing here feeds back into the main build. The input is the finished, verified
# artifact, the output is a separate file, and a failure in this path cannot change what
# the main Release produces.
set -Eeuo pipefail

SRC_APK="out/apple-music-vivo-car-lyrics-debug.apk"
EMBED_OUT_DIR="out/embed-ampp"
EMBED_APK_NAME="apple-music-vivo-car-lyrics-ampp-npatched.apk"
BT="${ANDROID_SDK_ROOT:-$ANDROID_HOME}/build-tools/${BUILD_TOOLS_VERSION}"
SIGNING_KEY="$RUNNER_TEMP/apple-music-vivo-car-lyrics-signing.p12"
SIGNING_KEY_ALIAS=apple-music-vivo-car-lyrics
SIGNING_CERT_SHA256_FILE="$(dirname "${BASH_SOURCE[0]}")/../config/signing-cert-sha256.txt"
NPATCH_JAR="${NPATCH_JAR:-$RUNNER_TEMP/tools/npatch.jar}"
AMPP_MODULE_APK="${AMPP_MODULE_APK:-$RUNNER_TEMP/tools/AM-plus-plus-v1.5.5.apk}"
SIG_BYPASS_LEVEL="${SIG_BYPASS_LEVEL:-2}"
PATCH_WORK="$RUNNER_TEMP/embed-ampp-work"
REPORT="$EMBED_OUT_DIR/report"

# NPatch's proxy appComponentFactory: the manifest points at this after patching, and the
# loader restores the app's own factory at runtime. Its presence in the outer manifest is
# how we prove the shell was actually applied.
PROXY_FACTORY="top.nkbe.npatch.metaloader.LSPAppComponentFactoryStub"
# The embedded module always lands here, keyed by its own package name.
MODULE_ASSET="assets/npatch/modules/dev.amenhancer.module.apk"

# The workflow treats this optional build as non-fatal. Do not leave a signed-but-unverified
# APK behind when a later structural check fails, or the artifact upload step could mistake
# that partial output for a successful embed.
cleanup_incomplete_output() {
  local status=$?
  if (( status != 0 )); then
    rm -f "$EMBED_OUT_DIR/$EMBED_APK_NAME" \
      "$EMBED_OUT_DIR/$EMBED_APK_NAME.sha256"
  fi
  return "$status"
}
trap cleanup_incomplete_output EXIT

[[ -f "$SRC_APK" ]] || { echo "Patched APK is missing: $SRC_APK" >&2; exit 1; }
[[ -f "$BT/apksigner" ]] || { echo "apksigner is missing at $BT" >&2; exit 1; }
[[ -f "$NPATCH_JAR" ]] || { echo "npatch.jar is missing: $NPATCH_JAR" >&2; exit 1; }
[[ -f "$AMPP_MODULE_APK" ]] || { echo "AM++ module APK is missing: $AMPP_MODULE_APK" >&2; exit 1; }
[[ -f "$SIGNING_CERT_SHA256_FILE" ]] || { echo "Signing certificate pin is missing" >&2; exit 1; }
[[ -n "${SIGNING_KEY_BASE64:-}" ]] || { echo "ANDROID_SIGNING_KEY_BASE64 secret is missing" >&2; exit 1; }
[[ -n "${SIGNING_PASSWORD:-}" ]] || { echo "ANDROID_SIGNING_PASSWORD secret is missing" >&2; exit 1; }

# The module is an LSPosed module in the libxposed layout; its own metadata declares the
# API level it was built against. These checks validate the pinned module's metadata and
# required assets, not successful runtime loading. libdexkit.so is required when title
# correction falls back to DexKit resolution.
python3 - "$AMPP_MODULE_APK" "$NPATCH_JAR" <<'PY'
import sys
import zipfile

module_path, jar_path = sys.argv[1:]
with zipfile.ZipFile(module_path) as apk:
    names = set(apk.namelist())
    for required in ("META-INF/xposed/module.prop", "META-INF/xposed/scope.list",
                     "META-INF/xposed/java_init.list"):
        if required not in names:
            raise SystemExit("%s is not an LSPosed module: %s is absent" % (module_path, required))
    prop = apk.read("META-INF/xposed/module.prop").decode("utf-8")
    scope = apk.read("META-INF/xposed/scope.list").decode("utf-8")
    entries = apk.read("META-INF/xposed/java_init.list").decode("utf-8").splitlines()
    if "dev.amenhancer.module.hook.HookEntry" not in [line.strip() for line in entries]:
        raise SystemExit("Module is missing the expected modern Xposed entry point")
    if "lib/arm64-v8a/libdexkit.so" not in names:
        raise SystemExit("Module is missing lib/arm64-v8a/libdexkit.so; title correction cannot resolve symbols")

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
for entry in ("assets/npatch/loader.bin", "assets/npatch/metaloader.dex",
              "assets/npatch/so/arm64-v8a/libnpatch.so"):
    if entry not in jar_names:
        raise SystemExit("npatch.jar is missing %s" % entry)

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
# manager app, and the two are mutually exclusive by construction. -npa signs with NPatch's
# built-in keystore (its -k custom-keystore path only accepts a BKS store, not our PKCS12),
# which is fine because we re-sign with our pinned key below. Level 2 signature bypass is
# what NPatch's own embedded build ships with; it keeps the original signature readable at
# runtime so AM++ cannot notice the rewrite. NPatch v1.0.7 defaults outputLog to true;
# JCommander's boolean flag handling flips a true default when --outputLog is supplied, so
# deliberately omit the flag and verify the serialized config after patching instead.
#
# NPatch signs with a BKS keystore (both its built-in key and any -k key), and BKS is a
# BouncyCastle type that a stock temurin JDK does not provide -- so a plain `java -jar` dies
# with "BKS not found" at signer registration. The jar bundles BouncyCastle but never
# registers the provider, so a one-line launcher installs it before delegating to NPatch's
# real entry point. Compiled against the jar with the runner's own javac.
mkdir -p "$PATCH_WORK/launcher"
cat > "$PATCH_WORK/launcher/NPatchLauncher.java" <<'JAVA'
import java.security.Security;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

public final class NPatchLauncher {
    public static void main(String[] args) throws Exception {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
        top.nkbe.npatch.patch.NPatch.main(args);
    }
}
JAVA
javac -cp "$NPATCH_JAR" -d "$PATCH_WORK/launcher" "$PATCH_WORK/launcher/NPatchLauncher.java"

java -Xmx4g -cp "$PATCH_WORK/launcher:$NPATCH_JAR" NPatchLauncher \
  -m "$PATCH_WORK/in/AM-plus-plus.apk" \
  -npa \
  -l "$SIG_BYPASS_LEVEL" \
  -f -o "$PATCH_WORK/out" \
  "$PATCH_WORK/in/apple-music-vivo-car-lyrics-debug.apk" \
  2>&1 | tee "$REPORT/npatch.log"

# NPatch's main() catches its own PatchError, prints the stack trace, and still exits 0 --
# and it creates the output zip before the signer runs, so a failed patch can leave a broken
# stub that a bare existence check would wave through. Fail loudly if the log carries the
# stack trace, and validate the produced file is a real APK below.
if grep -q 'PatchError' "$REPORT/npatch.log"; then
  echo "NPatch reported a PatchError; see $REPORT/npatch.log" >&2
  exit 1
fi

mapfile -t produced < <(find "$PATCH_WORK/out" -maxdepth 1 -type f -name '*-npatched.apk' -print)
[[ "${#produced[@]}" == 1 ]] || {
  echo "Expected exactly one npatched APK, found ${#produced[@]}" >&2
  exit 1
}
NPATCHED_APK="${produced[0]}"

# A swallowed PatchError can leave a truncated or near-empty zip. Prove the file is a real
# NPatch output -- a valid zip carrying the nested origin and the metaloader -- before we
# spend an apksigner pass on it.
python3 - "$NPATCHED_APK" <<'PY'
import sys
import zipfile

path = sys.argv[1]
if not zipfile.is_zipfile(path):
    raise SystemExit("NPatch output is not a valid zip: %s" % path)
with zipfile.ZipFile(path) as apk:
    names = set(apk.namelist())
for entry in ("assets/npatch/origin.apk", "assets/npatch/config.json", "classes.dex"):
    if entry not in names:
        raise SystemExit("NPatch output is missing %s -- patch did not complete" % entry)
print("NPatch produced a valid embed with %d entries" % len(names))
PY

# NPatch signs as it writes with its own key; re-signing with our fixed key puts the output
# on exactly the footing the main artifact is on: our pinned certificate, one signer. Which
# schemes survive is governed by the apk's minSdkVersion, which the loader may raise above
# the v2 threshold, so v2 is not asserted below -- only v3, matching the main artifact.
"$BT/apksigner" sign \
  --ks "$SIGNING_KEY" --ks-type PKCS12 --ks-key-alias "$SIGNING_KEY_ALIAS" \
  --ks-pass env:SIGNING_PASSWORD --key-pass env:SIGNING_PASSWORD \
  --v4-signing-enabled false \
  --out "$EMBED_OUT_DIR/$EMBED_APK_NAME" "$NPATCHED_APK"

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
# asserted: apksigner picks the schemes from the apk's minSdkVersion, and the loader may
# raise it above the v2-only range -- so demanding v2 here would fail a build that installs
# perfectly well.
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
python3 - "$EMBED_OUT_DIR/$EMBED_APK_NAME" "$SRC_APK" "$MODULE_ASSET" "$PROXY_FACTORY" <<'PY'
import io
import json
import sys
import zipfile

embed_path, src_path, module_asset, proxy_factory = sys.argv[1:]

src = zipfile.ZipFile(src_path)
src_helpers = sorted(n for n in src.namelist()
                     if n.startswith("classes") and n.endswith(".dex"))
if not src_helpers:
    raise SystemExit("Source APK has no dex entries")

with zipfile.ZipFile(embed_path) as apk:
    names = set(apk.namelist())
    for entry in ("assets/npatch/origin.apk",
                  "assets/npatch/loader.bin",
                  "assets/npatch/config.json",
                  module_asset):
        if entry not in names:
            raise SystemExit("Embedded APK is missing %s" % entry)
    # NPatch, in embed mode with the original nested, injects its metaloader as classes.dex.
    if "classes.dex" not in names:
        raise SystemExit("Embedded APK has no metaloader classes.dex")

    config = json.loads(apk.read("assets/npatch/config.json").decode("utf-8"))
    if config.get("useManager"):
        raise SystemExit("Embedded APK was patched in manager mode")
    if config.get("sigBypassLevel") != 2:
        raise SystemExit("Unexpected signature bypass level: %r" % config.get("sigBypassLevel"))
    # config records the *original* factory, which the loader restores after it brings the
    # framework up; the proxy stub is what the manifest points at instead (checked below).
    # Seeing the proxy here would mean the original was lost and the app would never resume.
    if config.get("appComponentFactory") == proxy_factory:
        raise SystemExit("config recorded the proxy factory instead of the app's own")
    if "originalSignature" not in config or not config["originalSignature"]:
        raise SystemExit("Embedded APK did not record the original signature")
    # The whole point of this rebuild: the framework log must be mirrored to media so a
    # degraded feature is diagnosable without adb.
    if config.get("outputLog") is not True:
        raise SystemExit("Embedded APK did not enable outputLog")

    # The whole APK is kept intact inside the shell as a nested zip. Its own
    # AndroidManifest.xml is deflated binary AXML, so the vivo action cannot be found by a
    # raw byte scan here -- that survives in the *outer*, rewritten manifest, which the
    # aapt2 xmltree grep below reads. What this block proves instead is that the nested copy
    # is byte-for-byte our r38 build: same dex set, same bytes.
    origin = apk.read("assets/npatch/origin.apk")
    origin_zip = zipfile.ZipFile(io.BytesIO(origin))
    origin_names = set(origin_zip.namelist())
    for helper in src_helpers:
        if helper not in origin_names:
            raise SystemExit("Nested origin.apk is missing %s" % helper)
        if origin_zip.read(helper) != src.read(helper):
            raise SystemExit("Nested %s differs from the source APK's" % helper)

    print("nested origin.apk carries %d dex entries unchanged" % len(src_helpers))
PY

for marker in 'com.vivo.musicwidgetmix.support.service' \
              "$PROXY_FACTORY" \
              'com.apple.android.music.player.MediaPlaybackService'; do
  grep -Fq "$marker" "$REPORT/embed-manifest.txt" || {
    echo "Missing embedded manifest marker: $marker" >&2
    exit 1
  }
done

# The Atomic action must still sit in the wrapped manifest exactly once, matching what the
# main pipeline asserts on the unwrapped one; otherwise the head unit will not find the
# service through the shell. Matched by resource id (0x01010003 = android:name) rather than
# by aapt2's namespace-prefix spelling, which varies between build-tools versions.
python3 - "$REPORT/embed-manifest.txt" <<'PY'
import re
import sys

text = open(sys.argv[1], encoding="utf-8").read()

# aapt2 prints each attribute on one line as:  A: <ns>:name(0x01010003)="value" (Raw: "value")
# -- the value twice. Counting occurrences of the string therefore reports 2 for a single
# declaration, so count the *lines* that declare it and require exactly one.
declarations = [line for line in text.splitlines()
                if "com.vivo.musicwidgetmix.support.service" in line]
if len(declarations) != 1:
    raise SystemExit("Atomic action is declared on %d manifest lines, expected 1:\n  %s"
                     % (len(declarations),
                        "\n  ".join(line.strip() for line in declarations[:8])))
if "android:name" not in declarations[0] and ":name(0x01010003)=" not in declarations[0]:
    raise SystemExit("Atomic action is not declared as an android:name attribute")
name_attr = re.compile(
    r':name\(0x01010003\)="com\.apple\.android\.music\.player\.MediaPlaybackService"')
if not name_attr.search(text):
    raise SystemExit("MediaPlaybackService is not declared in the embedded manifest")
PY

# NPatch stores the nested original 4096-aligned and its native library 16384-aligned so
# they can be mapped straight out of the apk. Re-signing must not have moved them: a shifted
# origin.apk is a straightforward install-time or first-launch failure, but one that looks
# like a bad module rather than a broken shell, so it is worth catching here.
python3 - "$EMBED_OUT_DIR/$EMBED_APK_NAME" <<'PY'
import struct
import sys
import zipfile

path = sys.argv[1]
ALIGNED = {
    "assets/npatch/origin.apk": 4096,
    "assets/npatch/so/arm64-v8a/libnpatch.so": 16384,
}

with open(path, "rb") as f:
    data = f.read()

zf = zipfile.ZipFile(path)
for name, alignment in ALIGNED.items():
    info = zf.getinfo(name)
    offset = info.header_offset
    if data[offset:offset + 4] != b"PK\x03\x04":
        raise SystemExit("Bad local header for %s" % name)
    name_len, extra_len = struct.unpack("<HH", data[offset + 26:offset + 30])
    data_start = offset + 30 + name_len + extra_len
    if data_start % alignment != 0:
        raise SystemExit("%s is not %d-byte aligned (offset %d)" % (name, alignment, data_start))
    if info.compress_type != zipfile.ZIP_STORED:
        raise SystemExit("%s must be stored, not deflated" % name)
    print("%s stored at %d-byte-aligned offset %d" % (name, alignment, data_start))
PY

sha256sum "$EMBED_OUT_DIR/$EMBED_APK_NAME" > "$EMBED_OUT_DIR/$EMBED_APK_NAME.sha256"
sha256sum "$NPATCH_JAR" "$AMPP_MODULE_APK" > "$REPORT/embed-inputs.sha256"
printf '%s\n' "$SIG_BYPASS_LEVEL" > "$REPORT/embed-sigbypass-level.txt"
echo "Embedded AM++ APK written: $EMBED_OUT_DIR/$EMBED_APK_NAME"
