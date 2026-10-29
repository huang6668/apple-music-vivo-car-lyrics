#!/usr/bin/env python3
"""Pin 1611 media3 legacy BrowserService: answer the vivo playlist browse.

Atomic Player (com.vivo.musicwidgetmix) selects Apple Music's cooperation controller via the
"com.vivo.musicwidgetmix.support.service" action, then MediaBrowserCompat-connects to the same
service. The connection is only accepted when onGetRoot returns a non-null BrowserRoot, and
Apple Music's media3 library returns null for Atomic (no onGetLibraryRoot override), so the
browser never connects and the playlist page is a dead control.

Once connected, Atomic subscribes to parentId "vivomusicmix_current_list" with the paging
option "vivomusicmix_key_media_page" and renders whatever MediaItems come back (mediaId/title/
subtitle, last item's extras "vivomusicmix_key_has_more"/"vivomusicmix_key_media_page").

r56 hooked onLoadChildren to answer the list but did not fix onGetRoot, so the browse never
reached it. r57 also accepts "com.vivo.musicwidgetmix" at onGetRoot (K4/g$d$a, inherited by the
real stub g$e$a) with rootId "vivomusicmix_current_list"; every other client falls through to
the stock root dispatch untouched.
"""
import argparse
import hashlib
from pathlib import Path
import re


HELPER = "Lcom/apple/android/music/player/VivoCarLyrics;"

HOOK_CHILDREN = (
    '    # vivo browse: answer "vivomusicmix_current_list" from the playback queue instead of\n'
    '    # the media3 library session, which returns nothing for that root.\n'
    '    const-string v0, "vivomusicmix_"\n'
    '\n'
    '    invoke-virtual {p1, v0}, Ljava/lang/String;->startsWith(Ljava/lang/String;)Z\n'
    '\n'
    '    move-result v0\n'
    '\n'
    '    if-eqz v0, :L_vivo_browse_stock\n'
    '\n'
    '    invoke-static {p1}, ' + HELPER
    + '->vivoBrowseChildren(Ljava/lang/String;)Ljava/util/List;\n'
    '\n'
    '    move-result-object v0\n'
    '\n'
    '    invoke-virtual {p2, v0}, Landroid/service/media/MediaBrowserService$Result;'
    '->sendResult(Ljava/lang/Object;)V\n'
    '\n'
    '    return-void\n'
    '\n'
    '    :L_vivo_browse_stock'
)

HOOK_ROOT = (
    '    # vivo browse: accept Atomic Player so its subscribe reaches onLoadChildren; the\n'
    '    # media3 library session returns a null root for this client.\n'
    '    const-string v0, "com.vivo.musicwidgetmix"\n'
    '\n'
    '    invoke-virtual {p1, v0}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z\n'
    '\n'
    '    move-result v0\n'
    '\n'
    '    if-eqz v0, :L_vivo_root_stock\n'
    '\n'
    '    new-instance v0, Landroid/service/media/MediaBrowserService$BrowserRoot;\n'
    '\n'
    '    const-string v1, "vivomusicmix_current_list"\n'
    '\n'
    '    const/4 v2, 0x0\n'
    '\n'
    '    invoke-direct {v0, v1, v2}, Landroid/service/media/MediaBrowserService$BrowserRoot;'
    '-><init>(Ljava/lang/String;Landroid/os/Bundle;)V\n'
    '\n'
    '    return-object v0\n'
    '\n'
    '    :L_vivo_root_stock'
)

SEAM_NORMALIZE = "    invoke-static {p3}, LC3/e0;->n(Landroid/os/Bundle;)Landroid/os/Bundle;"

SPECS = (
    ("K4/g$e$a.smali",
     "public final onLoadChildren(Ljava/lang/String;"
     "Landroid/service/media/MediaBrowserService$Result;Landroid/os/Bundle;)V",
     SEAM_NORMALIZE,
     "b1c583b50790562ecf797b347e5619b48765c6004d13ff7fe8796b580e155685",
     HOOK_CHILDREN,
     HELPER + "->vivoBrowseChildren(Ljava/lang/String;)Ljava/util/List;"),
    ("K4/g$d$a.smali",
     "public final onLoadChildren(Ljava/lang/String;"
     "Landroid/service/media/MediaBrowserService$Result;)V",
     "    new-instance v0, LK4/g$h;",
     "5e50df003a2c24e65dc8b753a72defab51832a7ec7076d728ea1be1dfbea4bdc",
     HOOK_CHILDREN,
     HELPER + "->vivoBrowseChildren(Ljava/lang/String;)Ljava/util/List;"),
    ("K4/g$d$a.smali",
     "public final onGetRoot(Ljava/lang/String;ILandroid/os/Bundle;)"
     "Landroid/service/media/MediaBrowserService$BrowserRoot;",
     SEAM_NORMALIZE,
     "cb1f48a895ab57866cf765f75e15789ec43d3e1daa2bf6253e789912a6e6d362",
     HOOK_ROOT,
     "const-string v1, \"vivomusicmix_current_list\""),
)


def code(text):
    return "\n".join(
        line.strip() for line in text.splitlines()
        if line.strip() and not line.strip().startswith(("#", ".line "))
    ) + "\n"


def verify(text, target, method, seam, stock_sha, hook, marker, patched):
    expected_class = "L" + target.removesuffix(".smali") + ";"
    assert re.search(r"^\.class [^\n]* " + re.escape(expected_class) + r"$", text, re.M), (
        "Wrong case-sensitive class: " + target
    )
    blocks = re.findall(r"(?ms)^\.method " + re.escape(method) + r"\n.*?^\.end method$", text)
    assert len(blocks) == 1, "Expected unique method: " + target + " / " + method
    block = blocks[0]
    assert text.count(marker) == (1 if patched else 0), "Unexpected hook count: " + marker
    if patched:
        assert hook in block, "Hook body drifted: " + target
        restored = block.replace(hook, "", 1)
        assert hashlib.sha256(code(restored).encode()).hexdigest() == stock_sha, (
            "Patched method must preserve all stock instructions: " + target
        )
    else:
        assert hook not in block, "Hook already present: " + target
        assert seam in block, "Seam drifted: " + target
        assert hashlib.sha256(code(block).encode()).hexdigest() == stock_sha, (
            "1611 method drifted; re-analyze rather than patching: " + target
        )


def target_path(root, target):
    paths = sorted(root.glob("smali*/" + target))
    assert len(paths) == 1, f"Expected {target} in exactly one dex, found {len(paths)}: {paths}"
    assert paths[0].relative_to(root).parts[0] == "smali", f"Target moved dex: {target}"
    return paths[0]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("decoded", type=Path)
    parser.add_argument("--unpatched", action="store_true")
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    for target, method, seam, stock_sha, hook, marker in SPECS:
        path = target_path(args.decoded, target)
        text = path.read_text(encoding="utf-8")
        if args.unpatched:
            verify(text, target, method, seam, stock_sha, hook, marker, patched=False)
        elif args.apply:
            verify(text, target, method, seam, stock_sha, hook, marker, patched=False)
            text = text.replace(seam, hook + "\n" + seam, 1)
            verify(text, target, method, seam, stock_sha, hook, marker, patched=True)
            path.write_text(text, encoding="utf-8")
        else:
            verify(text, target, method, seam, stock_sha, hook, marker, patched=True)
    mode = "verified unpatched" if args.unpatched else ("applied" if args.apply else "verified")
    print("vivo browse hook " + mode)


if __name__ == "__main__":
    main()
