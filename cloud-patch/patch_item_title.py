#!/usr/bin/env python3
"""Pin the 1607 BaseContentItem title getter seam to enable mainland title correction across all list and detail surfaces."""
import argparse
import hashlib
from pathlib import Path
import re
import sys

TARGET = "com/apple/android/music/model/BaseContentItem.smali"
METHOD = "public getTitle()Ljava/lang/String;"
HELPER = (
    "    invoke-static {p0, v0}, "
    "Lcom/apple/android/music/player/VivoCarLyrics;->resolveItemTitle"
    "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/String;\n"
    "\n"
    "    move-result-object v0\n"
)


def code(text: str) -> str:
    return "\n".join(
        line.strip()
        for line in text.splitlines()
        if line.strip() and not line.strip().startswith(("#", ".line "))
    ) + "\n"


def target_path(root: Path) -> Path:
    paths = sorted(root.glob("smali*/" + TARGET))
    assert len(paths) == 1, f"Expected BaseContentItem in exactly one dex, found {len(paths)}: {paths}"
    return paths[0]


def patch_text(text: str) -> str:
    assert ".class public Lcom/apple/android/music/model/BaseContentItem;" in text or \
           ".class public abstract Lcom/apple/android/music/model/BaseContentItem;" in text, (
        "BaseContentItem class header missing"
    )

    pattern = r"(?ms)(\.method public getTitle\(\)Ljava/lang/String;\s*\n)(.*?)(\.end method)"
    match = re.search(pattern, text)
    assert match, "BaseContentItem getTitle method missing"

    header, body, footer = match.groups()
    if "VivoCarLyrics;->resolveItemTitle" in body:
        return text  # Already patched

    # The stock method accesses ->name:Ljava/lang/String; and returns it.
    assert "->name:Ljava/lang/String;" in body, "Expected ->name title field access in getTitle"
    assert "return-object" in body, "Expected return-object in getTitle"

    patched_body = (
        "    .locals 1\n\n"
        "    iget-object v0, p0, Lcom/apple/android/music/model/BaseContentItem;->name:Ljava/lang/String;\n\n"
        + HELPER + "\n"
        "    return-object v0\n"
    )

    return text[:match.start()] + header + patched_body + footer + text[match.end():]


def verify_text(text: str, patched: bool = False):
    assert "BaseContentItem" in text, "Wrong class content"
    marker = "VivoCarLyrics;->resolveItemTitle"
    if patched:
        assert marker in text, "Expected title resolution hook in patched text"
        assert ".locals 1" in text, "Expected .locals 1 in patched getTitle method"
        assert "->name:Ljava/lang/String;" in text, "Expected field access preserved"
    else:
        assert marker not in text, "Unexpected title resolution hook in unpatched text"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", type=Path, help="Apktool root directory")
    parser.add_argument("--apply", action="store_true", help="Apply patch in-place")
    parser.add_argument("--unpatched", action="store_true", help="Assert target is in stock state")
    args = parser.parse_args()

    path = target_path(args.root)
    text = path.read_text(encoding="utf-8")

    if args.unpatched:
        verify_text(text, patched=False)
        return

    if args.apply:
        patched = patch_text(text)
        verify_text(patched, patched=True)
        path.write_text(patched, encoding="utf-8")
        print(f"Patched {path}")
    else:
        verify_text(text, patched=True)
        print(f"Verified {path}")


if __name__ == "__main__":
    main()
