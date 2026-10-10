#!/usr/bin/env python3
"""Pin the 1607 title seams to enable mainland title correction across all list, detail, and playlist surfaces."""
import argparse
import hashlib
from pathlib import Path
import re
import sys

TARGET = "com/apple/android/music/model/BaseContentItem.smali"
TARGET_ATTR = "com/apple/android/music/mediaapi/models/internals/Attributes.smali"
TARGET_PLAYLIST = "com/apple/android/music/collection/mediaapi/controller/PlaylistPageController.smali"
TARGET_ALBUM = "com/apple/android/music/collection/mediaapi/controller/AlbumPageController.smali"

METHOD = "public getTitle()Ljava/lang/String;"
HELPER = (
    "    invoke-static {p0, v0}, "
    "Lcom/apple/android/music/player/VivoCarLyrics;->resolveItemTitle"
    "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/String;\n"
    "\n"
    "    move-result-object v0\n"
)

ATTR_HELPER = (
    "    invoke-static {p0, v0}, "
    "Lcom/apple/android/music/player/VivoCarLyrics;->resolveItemTitle"
    "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/String;\n\n"
    "    move-result-object v0\n\n"
    "    iput-object v0, p0, Lcom/apple/android/music/mediaapi/models/internals/Attributes;->name:Ljava/lang/String;\n"
)

PLAYLIST_HOOK = (
    "\n    invoke-static {p0, p1}, "
    "Lcom/apple/android/music/player/VivoCarLyrics;->onPlaylistTrack"
    "(Ljava/lang/Object;Ljava/lang/Object;)V\n"
)


def code(text: str) -> str:
    return "\n".join(
        line.strip()
        for line in text.splitlines()
        if line.strip() and not line.strip().startswith(("#", ".line "))
    ) + "\n"


def target_path(root: Path, target: str = TARGET) -> Path:
    paths = sorted(root.glob("smali*/" + target))
    assert len(paths) == 1, f"Expected {target} in exactly one dex, found {len(paths)}: {paths}"
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


def patch_attributes_text(text: str) -> str:
    assert "Attributes" in text, "Attributes class header missing"
    pattern = r"(?ms)(\.method public final getName\(\)Ljava/lang/String;\s*\n)(.*?)(\.end method)"
    match = re.search(pattern, text)
    assert match, "Attributes getName method missing"
    header, body, footer = match.groups()
    if "VivoCarLyrics;->resolveItemTitle" in body:
        return text

    assert "->name:Ljava/lang/String;" in body, "Expected ->name field access in getName"
    assert "return-object" in body, "Expected return-object in getName"

    patched_body = (
        "    .locals 1\n\n"
        "    iget-object v0, p0, Lcom/apple/android/music/mediaapi/models/internals/Attributes;->name:Ljava/lang/String;\n\n"
        + ATTR_HELPER + "\n"
        "    return-object v0\n"
    )
    return text[:match.start()] + header + patched_body + footer + text[match.end():]


def verify_attributes_text(text: str, patched: bool = False):
    assert "Attributes" in text, "Wrong class content"
    marker = "VivoCarLyrics;->resolveItemTitle"
    if patched:
        assert marker in text, "Expected title resolution hook in patched Attributes"
        assert "iput-object v0, p0, Lcom/apple/android/music/mediaapi/models/internals/Attributes;->name:Ljava/lang/String;" in text
    else:
        assert marker not in text, "Unexpected title resolution hook in unpatched Attributes"


def patch_playlist_text(text: str) -> str:
    assert "PlaylistPageController" in text, "PlaylistPageController class header missing"
    pattern = r"(?ms)(\.method private final addTrackModel\(Lcom/apple/android/music/mediaapi/models/MediaEntity;I\)Lcom/airbnb/epoxy/w;\s*\n)(.*?)(\.end method)"
    match = re.search(pattern, text)
    assert match, "PlaylistPageController addTrackModel method missing"
    header, body, footer = match.groups()
    marker = "VivoCarLyrics;->onPlaylistTrack"
    if marker in body:
        return text

    loc_match = re.search(r"(\.(?:locals|registers)\s+\d+\s*\n)", body)
    assert loc_match, "addTrackModel registers/locals directive missing"
    idx = loc_match.end()
    patched_body = body[:idx] + PLAYLIST_HOOK + body[idx:]
    return text[:match.start()] + header + patched_body + footer + text[match.end():]


def verify_playlist_text(text: str, patched: bool = False):
    assert "PlaylistPageController" in text, "Wrong class content"
    marker = "VivoCarLyrics;->onPlaylistTrack"
    if patched:
        assert marker in text, "Expected onPlaylistTrack hook in patched PlaylistPageController"
    else:
        assert marker not in text, "Unexpected onPlaylistTrack hook in unpatched PlaylistPageController"


def patch_album_text(text: str) -> str:
    assert "AlbumPageController" in text, "AlbumPageController class header missing"
    pattern = r"(?ms)(\.method private final getItemTitle\(Lcom/apple/android/music/mediaapi/models/MediaEntity;\)Ljava/lang/String;\s*\n)(.*?)(\.end method)"
    match = re.search(pattern, text)
    assert match, "AlbumPageController getItemTitle method missing"
    header, body, footer = match.groups()
    marker = "VivoCarLyrics;->onPlaylistTrack"
    if marker in body:
        return text

    loc_match = re.search(r"(\.(?:locals|registers)\s+\d+\s*\n)", body)
    assert loc_match, "getItemTitle registers/locals directive missing"
    idx = loc_match.end()
    patched_body = body[:idx] + PLAYLIST_HOOK + body[idx:]
    return text[:match.start()] + header + patched_body + footer + text[match.end():]


def verify_album_text(text: str, patched: bool = False):
    assert "AlbumPageController" in text, "Wrong class content"
    marker = "VivoCarLyrics;->onPlaylistTrack"
    if patched:
        assert marker in text, "Expected onPlaylistTrack hook in patched AlbumPageController"
    else:
        assert marker not in text, "Unexpected onPlaylistTrack hook in unpatched AlbumPageController"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", type=Path, help="Apktool root directory")
    parser.add_argument("--apply", action="store_true", help="Apply patch in-place")
    parser.add_argument("--unpatched", action="store_true", help="Assert target is in stock state")
    args = parser.parse_args()

    targets = [
        (TARGET, patch_text, verify_text),
        (TARGET_ATTR, patch_attributes_text, verify_attributes_text),
        (TARGET_PLAYLIST, patch_playlist_text, verify_playlist_text),
        (TARGET_ALBUM, patch_album_text, verify_album_text),
    ]

    for rel_path, patch_fn, verify_fn in targets:
        path = target_path(args.root, rel_path)
        text = path.read_text(encoding="utf-8")

        if args.unpatched:
            verify_fn(text, patched=False)
        elif args.apply:
            patched = patch_fn(text)
            verify_fn(patched, patched=True)
            path.write_text(patched, encoding="utf-8")
            print(f"Patched {path}")
        else:
            verify_fn(text, patched=True)
            print(f"Verified {path}")


if __name__ == "__main__":
    main()
