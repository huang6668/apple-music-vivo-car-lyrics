#!/usr/bin/env python3
"""Validate the Apple Music 1609 title and artist data binding hooks."""

import argparse
import hashlib
from pathlib import Path
import re


TARGET = "q8/na.smali"
METHOD = "l()V"
STOCK_CODE_SHA256 = "8083423c6c9cb2743854b750a5d3eeef9c02e573d4c8393e367a85ca45ff9d30"
HELPER = (
    "Lcom/apple/android/music/player/VivoCarLyrics;"
    "->correctPlayerTitle(Ljava/lang/Object;Ljava/lang/Object;"
    "Ljava/lang/CharSequence;)Ljava/lang/CharSequence;"
)
ITEM = "iget-object v8, v1, Lq8/ma;->r0:Lcom/apple/android/music/model/PlaybackItem;"
RECEIVER = "move-object/from16 v1, p0"
TITLE = (
    "invoke-interface {v8}, Lcom/apple/android/music/model/CollectionItemView;"
    "->getTitle()Ljava/lang/String;"
)
HOOK = "invoke-static/range {v39 .. v41}, " + HELPER
SEAM = (
    TITLE,
    "move-result-object v39",
    "move-object/16 v41, v39",
    "move-object/from16 v39, v1",
    "move-object/from16 v40, v8",
    HOOK,
    "move-result-object v39",
    "move/from16 v41, v38",
    "move-object/from16 v42, v39",
)
VIEW = "iget-object v0, v1, Lq8/ma;->o0:Lcom/apple/android/music/common/views/CustomTextView;"
ASSIGN = (
    "invoke-static {v0, v2}, LI2/e;"
    "->a(Landroid/widget/TextView;Ljava/lang/CharSequence;)V"
)
EXPLICIT_ASSIGN = (
    "invoke-static {v0, v3, v2}, Lq8/T1;"
    "->b(Lcom/apple/android/music/common/views/CustomTextView;ZLjava/lang/CharSequence;)V"
)
ARTIST_HELPER = (
    "Lcom/apple/android/music/player/VivoCarLyrics;"
    "->correctPlayerArtist(Ljava/lang/Object;Ljava/lang/Object;"
    "Ljava/lang/CharSequence;)Ljava/lang/CharSequence;"
)
ARTIST = (
    "invoke-static {v8}, Lcom/apple/android/music/player/a1;"
    "->y0(Lcom/apple/android/music/model/PlaybackItem;)Ljava/lang/String;"
)
ARTIST_HOOK = "invoke-static/range {v52 .. v54}, " + ARTIST_HELPER
ARTIST_SEAM = (
    ARTIST,
    "move-result-object v52",
    "move-object/16 v54, v52",
    "move-object/from16 v52, v1",
    "move-object/from16 v53, v8",
    ARTIST_HOOK,
    "move-result-object v52",
    "move-object/from16 v54, v52",
    "goto :goto_27",
)
ARTIST_VIEW = "iget-object v0, v1, Lq8/ma;->n0:Lcom/apple/android/music/common/views/CustomTextView;"
ARTIST_ASSIGN = (
    "invoke-static {v0, v3}, LI2/e;"
    "->a(Landroid/widget/TextView;Ljava/lang/CharSequence;)V"
)


def code(text):
    return "\n".join(
        line.strip() for line in text.splitlines()
        if line.strip() and not line.strip().startswith(("#", ".line "))
    ) + "\n"


def instructions(text):
    return [
        line.strip() for line in text.splitlines()
        if line.strip() and not line.strip().startswith((".", "#", ":"))
    ]


def verify_text(text, unpatched=False):
    assert re.search(r"^\.class [^\n]* Lq8/na;\s*$", text, re.MULTILINE), (
        "Expected lowercase q8/na class (case-sensitive archive extraction required)"
    )
    methods = re.findall(
        r"^\.method [^\n]*\b" + re.escape(METHOD)
        + r"\n(.*?)^\.end method\s*$", text, re.MULTILINE | re.DOTALL
    )
    assert len(methods) == 1, "Title binding method must exist exactly once"
    expected_hooks = 0 if unpatched else 1
    assert text.count(HELPER) == expected_hooks, (
        "Unexpected title helper count for " + ("stock preflight" if unpatched else "patched validation")
    )
    assert text.count(ARTIST_HELPER) == expected_hooks, "Unexpected artist helper count"
    body = methods[0]
    assert re.search(r"^\s*\.locals 58\s*$", body, re.MULTILINE), (
        "1609 display hooks must preserve the original 58 locals"
    )
    ops = instructions(body)
    assert ops.count(TITLE) == 1, "Expected one stock title computation"
    start = ops.index(TITLE)
    seam = (TITLE, "move-result-object v39", "move/from16 v41, v38",
            "move-object/from16 v42, v39") if unpatched else SEAM
    assert ops[start:start + len(seam)] == list(seam), (
        "Title hook must preserve identity, stock title and register/assignment order"
    )
    assert RECEIVER in ops[:start] and ITEM in ops[:start], (
        "Missing stock binding receiver or item identity load"
    )
    assert VIEW in ops[start:], "Missing stock title TextView assignment"
    view_start = ops.index(VIEW, start)
    assert ops[view_start:view_start + 3] == [
        VIEW, "move-object/from16 v2, v42", ASSIGN
    ], "Corrected title must reach the stock title TextView adapter"
    assert EXPLICIT_ASSIGN in ops[view_start:], (
        "Explicit-title adapter must still receive the same corrected value"
    )
    # v41 is immediately overwritten above; the next v40 use must also overwrite it.
    next_v40 = next(
        (op for op in ops[start + len(seam):] if re.search(r"\bv40\b", op)), ""
    )
    assert next_v40 == "cmp-long v40, v43, v22", (
        "Unexpected live v40 use after the range-call scratch registers"
    )
    assert ops.count(ARTIST) == 1, "Expected one stock subtitle computation"
    artist_start = ops.index(ARTIST)
    artist_seam = (
        ARTIST, "move-result-object v52", "move-object/from16 v54, v52",
        "goto :goto_27"
    ) if unpatched else ARTIST_SEAM
    assert ops[artist_start:artist_start + len(artist_seam)] == list(artist_seam), (
        "Artist hook must preserve the binding, item, stock subtitle and result copy"
    )
    next_artist_scratch = next(
        (op for op in ops[artist_start + len(artist_seam):]
         if re.search(r"\bv(?:52|53)\b", op)), ""
    )
    assert next_artist_scratch == "and-long v52, v35, v28", (
        "Artist scratch v52/v53 must be overwritten before the next use"
    )
    assert ARTIST_VIEW in ops[artist_start:], "Missing stock artist TextView assignment"
    artist_view_start = ops.index(ARTIST_VIEW, artist_start)
    assert ops[artist_view_start:artist_view_start + 3] == [
        ARTIST_VIEW, "move-object/from16 v3, v54", ARTIST_ASSIGN
    ], "Corrected artist must reach the stock subtitle TextView adapter"
    canonical = code(text)
    if not unpatched:
        for seam_ops in (SEAM, ARTIST_SEAM):
            hook_code = "\n".join(seam_ops[2:7]) + "\n"
            assert canonical.count(hook_code) == 1, "Unexpected inserted binding instructions"
            canonical = canonical.replace(hook_code, "", 1)
    assert hashlib.sha256(canonical.encode()).hexdigest() == STOCK_CODE_SHA256, (
        "Display hooks must preserve every stock instruction, label and register directive"
    )


def verify_tree(root, unpatched=False):
    paths = list(root.glob("smali*/" + TARGET))
    assert len(paths) == 1, "Title binding target must exist in exactly one dex"
    verify_text(paths[0].read_text(encoding="utf-8"), unpatched=unpatched)
    return paths[0]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("decoded_root", type=Path)
    parser.add_argument("--unpatched", action="store_true", help="Validate original stock seam before patching")
    args = parser.parse_args()
    mode = "stock preflight" if args.unpatched else "patched hook"
    print("1609 title/artist binding " + mode + " verified: "
          + str(verify_tree(args.decoded_root, unpatched=args.unpatched)))


if __name__ == "__main__":
    main()
