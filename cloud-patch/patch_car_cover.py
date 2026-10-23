#!/usr/bin/env python3
"""Pin 1609 MediaMetadataCompat factory method to strip inline bitmaps.

CarNetworking 6.0.8.3 has a race condition in MusicCoverStore (wb.f):
When a song changes, MusicCoverStore's delayed (2000ms) background compression
finishes for the PREVIOUS song. Its callback in jg.e$d blindly compares only
the package name ("com.apple.android.music"), not the songId, and pushes the
old compressed PNG to CarLauncher via ILauncherManager.A3, overwriting the
correct cover that CarLauncher had just displayed from METADATA_KEY_ALBUM_ART_URI.

If MediaMetadataCompat has NO inline Bitmap (METADATA_KEY_ALBUM_ART /
METADATA_KEY_DISPLAY_ICON are null), jg.e:j0 completely skips MusicCoverStore
(jg.e:X returns false) and executes Y(aVar) -> directly uses the HTTP URL
(METADATA_KEY_ALBUM_ART_URI) for CarLauncher, and downloads it directly via
s(d10) for HUD (x()). This completely eliminates the 1-song-behind cover desync.
"""
import argparse
import hashlib
from pathlib import Path
import re


TARGET = "J4/t.smali"
METHOD = "public static n(Lz3/y;Ljava/lang/String;Landroid/net/Uri;JLandroid/graphics/Bitmap;)LK4/z;"
STOCK_SHA256 = "a36804c8addb1932935298311b6a6507d6569cb7acc7561cec2b46972b03aeb9"
STOCK_CODE_SHA256 = "97e0740d04928e1ab862beabd7ec99c3a78a5ccb6f4b168f15c970fc42bf51bb"

HOOK = (
    "    # Vivo car / CarNetworking cover desync fix:\n"
    "    # Strip inline bitmap from MediaMetadataCompat to prevent CarNetworking 6.0.8.3\n"
    "    # MusicCoverStore's async 2s delayed compressor from overwriting CarLauncher\n"
    "    # with the previous song's cover. CarNetworking still reads METADATA_KEY_ALBUM_ART_URI\n"
    "    # immediately and downloads via HTTP for HUD without desync.\n"
    "    const/4 p5, 0x0\n\n"
)
SEAM = "    .locals 3\n\n    .line 1\n    new-instance v0, LK4/z$b;"
PATCHED_SEAM = "    .locals 3\n\n" + HOOK + "    .line 1\n    new-instance v0, LK4/z$b;"


def code(text):
    return "\n".join(
        line.strip() for line in text.splitlines()
        if line.strip() and not line.strip().startswith(("#", ".line "))
    ) + "\n"


def verify_text(text, patched=False):
    assert text.startswith(".class public final LJ4/t;"), "Wrong case-sensitive class"
    blocks = re.findall(
        r"(?ms)^\.method " + re.escape(METHOD) + r"\n.*?^\.end method$", text
    )
    assert len(blocks) == 1, "Expected unique MediaMetadataCompat factory method"
    block = blocks[0]
    marker = "const/4 p5, 0x0"
    assert block.count(marker) == (1 if patched else 0), "Unexpected cover hook count"
    if patched:
        hook_code = code(HOOK)
        canonical = code(block)
        entry = ".method " + METHOD + "\n.locals 3\n" + hook_code + "new-instance v0, LK4/z$b;"
        assert entry in canonical, "Cover hook entry registers or seam drifted"
        stock_code = canonical.replace(hook_code, "", 1)
        assert hashlib.sha256(stock_code.encode()).hexdigest() == STOCK_CODE_SHA256, (
            "Patched factory method must preserve all stock code"
        )
    else:
        assert SEAM in block, "Factory method entry registers or seam drifted"
        assert hashlib.sha256(text.encode("utf-8")).hexdigest() == STOCK_SHA256, (
            "1609 MediaMetadataCompat factory source drifted; re-analyze rather than patching"
        )


def target_path(root):
    paths = sorted(root.glob("smali*/" + TARGET))
    assert len(paths) == 1, f"Expected {TARGET} in exactly one dex, found {len(paths)}: {paths}"
    assert paths[0].relative_to(root).parts[0] == "smali", f"Target moved dex: {TARGET}"
    return paths[0]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("decoded", type=Path)
    parser.add_argument("--unpatched", action="store_true")
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    path = target_path(args.decoded)
    text = path.read_text(encoding="utf-8")
    if args.unpatched:
        verify_text(text, patched=False)
        print("Stock MediaMetadataCompat factory method verified")
        return
    if args.apply:
        verify_text(text, patched=False)
        text = text.replace(SEAM, PATCHED_SEAM, 1)
        verify_text(text, patched=True)
        path.write_text(text, encoding="utf-8")
        print("MediaMetadataCompat cover fix applied")
    else:
        verify_text(text, patched=True)
        print("MediaMetadataCompat cover fix verified")


if __name__ == "__main__":
    main()
