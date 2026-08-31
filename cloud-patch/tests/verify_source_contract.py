#!/usr/bin/env python3
from pathlib import Path


SOURCE = Path("cloud-patch/java/com/apple/android/music/player/VivoCarLyrics.java")
text = SOURCE.read_text(encoding="utf-8")


def method_body(signature: str) -> str:
    start = text.index(signature)
    opening = text.index("{", start)
    depth = 0
    for index in range(opening, len(text)):
        value = text[index]
        if value == "{":
            depth += 1
        elif value == "}":
            depth -= 1
            if depth == 0:
                return text[opening:index + 1]
    raise AssertionError(f"Unclosed method: {signature}")


metadata = method_body("private static boolean publishMetadata(")
session_extras = method_body("private static void publishSessionExtras(")
line_publish = method_body(
    "private static void requestLinePublish(Object manager, String line, long generation,"
)

for required in (
    "extras.putString(META_LINE",
    "extras.putString(META_WHOLE",
    "extras.putLong(META_STATUS",
    'invokeRequired(manager, "I", newMediaItem',
):
    assert required in metadata, f"Missing MediaMetadata contract: {required}"

for forbidden in (
    'extras.remove("android.media.metadata.ART")',
    'extras.remove("android.media.metadata.ALBUM_ART")',
    'extras.remove("android.media.metadata.DISPLAY_ICON")',
):
    assert forbidden not in metadata, f"Native artwork must stay intact: {forbidden}"

assert "META_LINE" not in session_extras
assert "META_WHOLE" not in session_extras
assert "META_STATUS" not in session_extras
assert "publishLineExtras" in line_publish
assert "publishMetadata" not in line_publish
assert "metadataLine" not in text
assert "latestStatus != STATUS_LOADING" in text
assert "!safeEquals(latestClusterWhole, metadataWhole)" in text
assert "ClusterLyricsPaginator.paginate" in text

print("VivoCarLyrics source contract passed")
