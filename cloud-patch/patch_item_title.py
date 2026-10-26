#!/usr/bin/env python3
"""Pin 1611 title/artist getters and Catalog-backed list-controller entry hooks."""

import argparse
import hashlib
from pathlib import Path
import re


TARGET = "com/apple/android/music/model/BaseContentItem.smali"
TARGET_ATTR = "com/apple/android/music/mediaapi/models/internals/Attributes.smali"
TARGET_PLAYBACK = "com/apple/android/music/model/BasePlaybackItem.smali"
TARGET_SONG = "com/apple/android/music/model/Song.smali"
TARGET_PLAYLIST = "com/apple/android/music/collection/mediaapi/controller/PlaylistPageController.smali"
TARGET_ALBUM = "com/apple/android/music/collection/mediaapi/controller/AlbumPageController.smali"
HELPER = "Lcom/apple/android/music/player/VivoCarLyrics;"
PLAYLIST_METHOD = (
    "private final addTrackModel(Lcom/apple/android/music/mediaapi/models/MediaEntity;I)"
    "Lcom/airbnb/epoxy/w;"
)
ALBUM_METHOD = (
    "private final getItemTitle(Lcom/apple/android/music/mediaapi/models/MediaEntity;)"
    "Ljava/lang/String;"
)
ENTRY_PINS = {
    TARGET_PLAYLIST: "ede9d565cfc2fd944ec99420ec73349cfdf055c837793fa3def397cbe95aaf1b",
    TARGET_ALBUM: "efb388985ab53300e5dd0d2e757d8da89384de1d0271806bf76f8d952336d952",
}
PLAYLIST_HOOK = (
    "\n    invoke-static/range {p0 .. p1}, " + HELPER
    + "->onPlaylistTrack(Ljava/lang/Object;Ljava/lang/Object;)V\n"
)
ARTIST_RETURN_SPECS = {
    TARGET_PLAYBACK: (
        "public getNowPlayingSubtitle()Ljava/lang/String;", 2, 3, "v2",
        "73efc5a75fb083758e8ce003a08de21c12391cde09d85e3e6f3acea276eb7190",
    ),
    TARGET_SONG: (
        "public getSubTitle()Ljava/lang/String;", 1, 2, "v1",
        "88d4f6f55a320b1a529588f5d870d2ef7aba263eb5c4452c7d88f418af135f72",
    ),
}


def code(text):
    return "\n".join(
        line.strip() for line in text.splitlines()
        if line.strip() and not line.strip().startswith(("#", ".line "))
    ) + "\n"


def target_path(root, target=TARGET):
    paths = sorted(root.glob("smali*/" + target))
    assert len(paths) == 1, f"Expected {target} in exactly one dex, found {len(paths)}: {paths}"
    assert paths[0].relative_to(root).parts[0] == "smali_classes2", f"Target moved dex: {target}"
    return paths[0]


def method_block(text, signature):
    blocks = list(re.finditer(
        r"(?ms)^\.method " + re.escape(signature) + r"\n.*?^\.end method$", text
    ))
    assert len(blocks) == 1, "Expected one stock method: " + signature
    return blocks[0]


def verify_class(text, target):
    descriptor = "L" + target.removesuffix(".smali") + ";"
    assert re.search(r"^\.class [^\n]* " + re.escape(descriptor) + r"$", text, re.M), (
        "Wrong case-sensitive class: " + target
    )


def getter_spec(target, artist=False):
    prefix = "public final " if target == TARGET_ATTR else "public "
    method = "getArtistName" if artist else ("getName" if target == TARGET_ATTR else "getTitle")
    field = "artistName" if artist else "name"
    helper = "resolveItemArtist" if artist else "resolveItemTitle"
    descriptor = "L" + target.removesuffix(".smali") + ";"
    signature = prefix + method + "()Ljava/lang/String;"
    owner = "L" + TARGET.removesuffix(".smali") + ";" if target == TARGET_PLAYBACK else descriptor
    load = "iget-object p0, p0, " + owner + "->" + field + ":Ljava/lang/String;"
    capture = "move-object v0, p0\n"
    call = (
        "invoke-static {v0, p0}, " + HELPER + "->" + helper
        + "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/String;\n"
        "move-result-object p0\n"
    )
    if target == TARGET_ATTR:
        call += "iput-object p0, v0, " + descriptor + "->" + field + ":Ljava/lang/String;\n"
    return signature, load, capture, call, helper


def verify_getter(text, target, artist=False, patched=False):
    verify_class(text, target)
    signature, load, capture, call, helper = getter_spec(target, artist)
    block = code(method_block(text, signature).group())
    marker = HELPER + "->" + helper
    assert block.count(marker) == (1 if patched else 0), "Unexpected getter hook count: " + helper
    if patched:
        assert ".locals 1\n" in block, "Getter must preserve receiver with exactly one new local"
        assert capture + load + "\n" in block, "Getter receiver identity must survive stock field access"
        assert block.count(call) == 1, "Getter helper registers/result/write drifted"
        assert call + "return-object p0\n" in block, "Getter must return the corrected stock value"
        block = block.replace(capture, "", 1).replace(call, "", 1)
        block = block.replace(".locals 1\n", ".locals 0\n", 1)
    expected = (
        ".method " + signature + "\n.locals 0\n" + load + "\n"
        "return-object p0\n.end method\n"
    )
    assert block == expected, "Getter must preserve every stock instruction: " + signature


def patch_getter(text, target, artist=False):
    signature, load, capture, call, helper = getter_spec(target, artist)
    match = method_block(text, signature)
    if HELPER + "->" + helper in match.group():
        verify_getter(text, target, artist=artist, patched=True)
        return text
    verify_getter(text, target, artist=artist)
    body = match.group().replace(".locals 0", ".locals 1", 1)
    body = body.replace("    " + load, "    " + capture + "\n    " + load, 1)
    body = body.replace("    return-object p0",
                        "\n".join("    " + op for op in call.rstrip().splitlines())
                        + "\n\n    return-object p0", 1)
    changed = text[:match.start()] + body + text[match.end():]
    verify_getter(changed, target, artist=artist, patched=True)
    return changed


def patch_text(text):
    return patch_getter(patch_getter(text, TARGET), TARGET, artist=True)


def verify_text(text, patched=False):
    verify_getter(text, TARGET, patched=patched)
    verify_getter(text, TARGET, artist=True, patched=patched)
    verify_marker_count(text, "resolveItemTitle", 1 if patched else 0)
    verify_marker_count(text, "resolveItemArtist", 1 if patched else 0)


def patch_attributes_text(text):
    return patch_getter(patch_getter(text, TARGET_ATTR), TARGET_ATTR, artist=True)


def verify_attributes_text(text, patched=False):
    verify_getter(text, TARGET_ATTR, patched=patched)
    verify_getter(text, TARGET_ATTR, artist=True, patched=patched)
    verify_marker_count(text, "resolveItemTitle", 1 if patched else 0)
    verify_marker_count(text, "resolveItemArtist", 1 if patched else 0)


def verify_marker_count(text, helper, expected):
    assert text.count(HELPER + "->" + helper) == expected, "Unexpected class hook count: " + helper


def artist_return_call(receiver):
    return (
        "invoke-static {" + receiver + ", p0}, " + HELPER
        + "->resolveItemArtist(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/String;\n"
        "move-result-object p0\n"
    )


def verify_artist_return(text, target, patched=False):
    verify_class(text, target)
    signature, before, after, receiver, pin = ARTIST_RETURN_SPECS[target]
    block = code(method_block(text, signature).group())
    marker = HELPER + "->resolveItemArtist"
    assert block.count(marker) == (1 if patched else 0), "Unexpected subtitle helper count"
    if patched:
        capture = "move-object " + receiver + ", p0\n"
        call = artist_return_call(receiver)
        assert block.startswith(".method " + signature + "\n.locals " + str(after) + "\n" + capture), (
            "Subtitle getter must capture the original receiver in its only new local"
        )
        assert block.count(call) == 1 and call + "return-object p0\n" in block, (
            "Subtitle helper must return the corrected raw artist with the original receiver"
        )
        if target == TARGET_SONG:
            fallback = (
                "iget-object p0, p0, Lcom/apple/android/music/model/BaseContentItem;"
                "->artistName:Ljava/lang/String;\n"
            )
            assert fallback + call + "return-object p0\n" in block, (
                "Song hook must only correct the raw artist fallback, not its explicit subtitle"
            )
        block = block.replace(capture, "", 1).replace(call, "", 1)
        block = block.replace(".locals " + str(after) + "\n", ".locals " + str(before) + "\n", 1)
    assert hashlib.sha256(block.encode()).hexdigest() == pin, (
        "Subtitle getter must preserve all 1611 stock instructions, labels and branches"
    )


def patch_artist_return(text, target):
    signature, before, after, receiver, _ = ARTIST_RETURN_SPECS[target]
    match = method_block(text, signature)
    if HELPER + "->resolveItemArtist" in match.group():
        verify_artist_return(text, target, patched=True)
        return text
    verify_artist_return(text, target)
    body = match.group().replace(".locals " + str(before), ".locals " + str(after), 1)
    first_line = re.search(r"^[ \t]*\.line \d+\n", body, re.M)
    assert first_line, "Expected pinned first stock instruction"
    capture = "    move-object " + receiver + ", p0\n\n"
    body = body[:first_line.start()] + capture + body[first_line.start():]
    assert body.count("    return-object p0") == 1, "Expected unique raw artist return branch"
    call = artist_return_call(receiver)
    body = body.replace("    return-object p0",
                        "\n".join("    " + op for op in call.rstrip().splitlines())
                        + "\n\n    return-object p0", 1)
    changed = text[:match.start()] + body + text[match.end():]
    verify_artist_return(changed, target, patched=True)
    return changed


def patch_playback_text(text):
    return patch_artist_return(patch_getter(text, TARGET_PLAYBACK, artist=True), TARGET_PLAYBACK)


def verify_playback_text(text, patched=False):
    verify_getter(text, TARGET_PLAYBACK, artist=True, patched=patched)
    verify_artist_return(text, TARGET_PLAYBACK, patched=patched)
    verify_marker_count(text, "resolveItemArtist", 2 if patched else 0)


def patch_song_text(text):
    return patch_artist_return(text, TARGET_SONG)


def verify_song_text(text, patched=False):
    verify_artist_return(text, TARGET_SONG, patched=patched)
    verify_marker_count(text, "resolveItemArtist", 1 if patched else 0)


def verify_entry(text, target, signature, patched=False):
    verify_class(text, target)
    block = code(method_block(text, signature).group())
    marker = HELPER + "->onPlaylistTrack"
    assert text.count(marker) == (1 if patched else 0), "Unexpected controller hook count"
    if patched:
        inserted = code(PLAYLIST_HOOK)
        assert block.count(inserted) == 1, "Controller hook argument registers drifted"
        assert re.match(r"\.method " + re.escape(signature)
                        + r"\n\.locals \d+\n(?:\.annotation [^\n]*\n.*?\.end annotation\n)*"
                        + re.escape(inserted), block, re.S), (
            "Controller hook must run at entry without changing any stock locals"
        )
        block = block.replace(inserted, "", 1)
    assert hashlib.sha256(block.encode()).hexdigest() == ENTRY_PINS[target], (
        "Controller hook must preserve every 1611 stock instruction/label: " + signature
    )


def patch_entry(text, target, signature):
    match = method_block(text, signature)
    if HELPER + "->onPlaylistTrack" in match.group():
        verify_entry(text, target, signature, patched=True)
        return text
    verify_entry(text, target, signature)
    body = match.group()
    first_line = re.search(r"^[ \t]*\.line \d+\n", body, re.M)
    assert first_line, "Expected pinned first instruction line after method metadata"
    body = body[:first_line.start()] + PLAYLIST_HOOK + body[first_line.start():]
    changed = text[:match.start()] + body + text[match.end():]
    verify_entry(changed, target, signature, patched=True)
    return changed


def patch_playlist_text(text):
    return patch_entry(text, TARGET_PLAYLIST, PLAYLIST_METHOD)


def verify_playlist_text(text, patched=False):
    verify_entry(text, TARGET_PLAYLIST, PLAYLIST_METHOD, patched=patched)


def patch_album_text(text):
    return patch_entry(text, TARGET_ALBUM, ALBUM_METHOD)


def verify_album_text(text, patched=False):
    verify_entry(text, TARGET_ALBUM, ALBUM_METHOD, patched=patched)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", type=Path, help="Apktool root directory")
    parser.add_argument("--apply", action="store_true", help="Apply patch in-place")
    parser.add_argument("--unpatched", action="store_true", help="Assert stock state")
    args = parser.parse_args()
    targets = [
        (TARGET, patch_text, verify_text),
        (TARGET_ATTR, patch_attributes_text, verify_attributes_text),
        (TARGET_PLAYBACK, patch_playback_text, verify_playback_text),
        (TARGET_SONG, patch_song_text, verify_song_text),
        (TARGET_PLAYLIST, patch_playlist_text, verify_playlist_text),
        (TARGET_ALBUM, patch_album_text, verify_album_text),
    ]
    for target, patch_fn, verify_fn in targets:
        path = target_path(args.root, target)
        text = path.read_text(encoding="utf-8")
        if args.unpatched:
            verify_fn(text)
        elif args.apply:
            changed = patch_fn(text)
            verify_fn(changed, patched=True)
            path.write_text(changed, encoding="utf-8")
            print("Patched " + str(path))
        else:
            verify_fn(text, patched=True)
            print("Verified " + str(path))


if __name__ == "__main__":
    main()
