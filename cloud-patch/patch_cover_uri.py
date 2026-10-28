#!/usr/bin/env python3
"""Pin 1611 media-metadata factory: 480px cover URL, no inline bitmap.

Why both hooks (verified against CarNetworking 6.0.8.3):

* The launcher card and the Atomic widget fetch the artwork URL in their own
  process and only accept http(s). Any inline bitmap makes CarNetworking put the
  compressed result (a permission-guarded content:// URI) into the launcher JSON,
  which the card cannot read - the previous cover stays on screen.
* Without a bitmap, jg/e.Y() -> R() runs the URL path, which sets the cached URL
  to our http URL and force-notifies CarMusicInfo, so HudManager (the instrument
  cluster) gets the bitmap that s(url) just downloaded.

A 1000x1000 original (~200-400 kB) makes that download miss its 5 s budget, which
is why the URL is rewritten to the 480x480 variant first - the same rule the
7tattoo/vivo-car-atomic-lyrics skill pins for the card side.
"""
import argparse
import hashlib
from pathlib import Path
import re


TARGET = "J4/t.smali"
METHOD = "public static n(Lz3/y;Ljava/lang/String;Landroid/net/Uri;JLandroid/graphics/Bitmap;)LK4/z;"
STOCK_SHA256 = "0bba3016802fe23977679ab36b768a06e5db412fd2e7a2781735b35c247dd7fe"
STOCK_CODE_SHA256 = "97e0740d04928e1ab862beabd7ec99c3a78a5ccb6f4b168f15c970fc42bf51bb"

HOOK = (
    "    # Car cover: no inline bitmaps (the card cannot read CarNetworking's\n"
    "    # compressed content:// URI), and a card-sized https URL instead.\n"
    "    const/4 p5, 0x0\n"
    "\n"
    "    invoke-static {p2}, Lcom/apple/android/music/player/VivoCarLyrics;"
    "->coverUri(Landroid/net/Uri;)Landroid/net/Uri;\n"
    "\n"
    "    move-result-object p2\n"
    "\n"
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
    marker = "->coverUri(Landroid/net/Uri;)Landroid/net/Uri;"
    strip = "const/4 p5, 0x0"
    assert block.count(marker) == (1 if patched else 0), "Unexpected cover-url hook count"
    assert block.count(strip) == (1 if patched else 0), "Unexpected bitmap-strip hook count"
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
            "1611 MediaMetadataCompat factory source drifted; re-analyze rather than patching"
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
        print("Car cover hook applied")
    else:
        verify_text(text, patched=True)
        print("Car cover hook verified")


if __name__ == "__main__":
    main()
