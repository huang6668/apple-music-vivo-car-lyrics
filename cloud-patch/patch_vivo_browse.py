#!/usr/bin/env python3
"""Pin 1611 media3 legacy BrowserService: answer vivo playlist browse roots.

Atomic Player (com.vivo.musicwidgetmix) selects Apple Music's cooperation controller via the
"com.vivo.musicwidgetmix.support.service" action, then subscribes its MediaBrowser to the
parentId "vivomusicmix_current_list" with the paging option
"vivomusicmix_key_media_page". The app answers with a list of MediaBrowser.MediaItem whose
MediaDescription carries mediaId/title/subtitle; that list is what the widget renders as the
playlist. Apple Music's media3 library implements no such root and returns an empty list, so
the playlist page has always been blank (the user sees "no reaction").

"K4/g$e$a" (API 26+) overrides "onLoadChildren(String, Result, Bundle)"; "K4/g$d$a"
carries the API<26 overload "onLoadChildren(String, Result)". Both dispatch into the media3
library session. r56 intercepts both: any parentId with the "vivomusicmix_" prefix is answered
directly by "VivoCarLyrics.vivoBrowseChildren(parentId)"; every other parentId falls through
to the stock dispatch untouched.
"""
import argparse
import hashlib
from pathlib import Path
import re


HELPER = "Lcom/apple/android/music/player/VivoCarLyrics;"

HOOK = (
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

SPECS = (
    ("K4/g$e$a.smali",
     "public final onLoadChildren(Ljava/lang/String;"
     "Landroid/service/media/MediaBrowserService$Result;Landroid/os/Bundle;)V",
     "    invoke-static {p3}, LC3/e0;->n(Landroid/os/Bundle;)Landroid/os/Bundle;",
     "b1c583b50790562ecf797b347e5619b48765c6004d13ff7fe8796b580e155685"),
    ("K4/g$d$a.smali",
     "public final onLoadChildren(Ljava/lang/String;"
     "Landroid/service/media/MediaBrowserService$Result;)V",
     "    new-instance v0, LK4/g$h;",
     "5e50df003a2c24e65dc8b753a72defab51832a7ec7076d728ea1be1dfbea4bdc"),
)


def code(text):
    return "\n".join(
        line.strip() for line in text.splitlines()
        if line.strip() and not line.strip().startswith(("#", ".line "))
    ) + "\n"


def verify(text, target, method, seam, stock_sha, patched):
    expected_class = "L" + target.removesuffix(".smali") + ";"
    assert re.search(r"^\.class [^\n]* " + re.escape(expected_class) + r"$", text, re.M), (
        "Wrong case-sensitive class: " + target
    )
    blocks = re.findall(r"(?ms)^\.method " + re.escape(method) + r"\n.*?^\.end method$", text)
    assert len(blocks) == 1, "Expected unique onLoadChildren method: " + target
    block = blocks[0]
    marker = HELPER + "->vivoBrowseChildren(Ljava/lang/String;)Ljava/util/List;"
    assert text.count(marker) == (1 if patched else 0), "Unexpected vivo browse hook count"
    if patched:
        assert HOOK in block, "vivo browse hook body drifted: " + target
        restored = block.replace(HOOK, "", 1)
        assert hashlib.sha256(code(restored).encode()).hexdigest() == stock_sha, (
            "onLoadChildren must preserve all stock instructions: " + target
        )
    else:
        assert HOOK not in block, "vivo browse hook already present: " + target
        assert seam in block, "onLoadChildren seam drifted: " + target
        assert hashlib.sha256(code(block).encode()).hexdigest() == stock_sha, (
            "1611 onLoadChildren drifted; re-analyze rather than patching: " + target
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
    for target, method, seam, stock_sha in SPECS:
        path = target_path(args.decoded, target)
        text = path.read_text(encoding="utf-8")
        if args.unpatched:
            verify(text, target, method, seam, stock_sha, patched=False)
        elif args.apply:
            verify(text, target, method, seam, stock_sha, patched=False)
            text = text.replace(seam, HOOK + "\n" + seam, 1)
            verify(text, target, method, seam, stock_sha, patched=True)
            path.write_text(text, encoding="utf-8")
        else:
            verify(text, target, method, seam, stock_sha, patched=True)
    mode = "verified unpatched" if args.unpatched else ("applied" if args.apply else "verified")
    print("vivo browse hook " + mode)


if __name__ == "__main__":
    main()
