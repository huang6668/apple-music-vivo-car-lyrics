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
capability = method_body("private static boolean advertiseAtomicLyricSupport(")
line_publish = method_body(
    "private static void requestLinePublish(Object manager, String line, long generation,"
)

# The playback manager's MediaItem publish path rebuilds session MediaMetadata and resets the
# native PlaybackState, which removes Atomic Player's progress bar and reloads cluster cover art.
# publishMetadata must stay an inert no-op.
for forbidden in (
    'invokeRequired(manager, "I", newMediaItem',
    "constructCompatible(",
    "setFieldValue(",
    "extras.putString(META_LINE",
    "extras.putString(META_WHOLE",
    "extras.putLong(META_STATUS",
):
    assert forbidden not in metadata, f"publishMetadata must not republish MediaMetadata: {forbidden}"
assert "return false;" in metadata, "publishMetadata must remain a no-op"

# Nothing anywhere may republish the MediaItem or write cluster keys into MediaMetadata.
assert 'invokeRequired(manager, "I", newMediaItem' not in text, (
    "MediaItem republication resets the native progress bar"
)
assert "setFieldValue(" not in text, (
    "Mutating MediaItem/Metadata builder fields reintroduces MediaMetadata override"
)

# The only permitted MediaMetadata mutation is the in-place Atomic capability bit.
assert "ATOMIC_SUPPORT_EVENTS" in capability
assert "ATOMIC_LYRIC_SUPPORT_EVENT" in capability
for forbidden in ("new Bundle(", "constructCompatible(", 'invokeRequired(manager, "I"'):
    assert forbidden not in capability, (
        f"Capability bit must be ORed in place, not republished: {forbidden}"
    )

# Both consumers are served through session Extras: car head unit keys and ucar cluster keys.
for required in (
    "extras.putString(EXTRA_LINE",
    "extras.putBoolean(EXTRA_ALLOWED",
    "extras.putString(META_LINE",
    "extras.putString(META_WHOLE",
    "extras.putLong(META_STATUS",
    "clusterState.clusterWhole",
    "clusterState.clusterTimes",
):
    assert required in session_extras, f"Missing session Extras contract: {required}"

for forbidden in (
    'extras.remove("android.media.metadata.ART")',
    'extras.remove("android.media.metadata.ALBUM_ART")',
    'extras.remove("android.media.metadata.DISPLAY_ICON")',
):
    assert forbidden not in session_extras, f"Native artwork must stay intact: {forbidden}"

assert "publishLineExtras" in line_publish
assert "publishMetadata" not in line_publish
assert "metadataLine" not in text
assert "ClusterLyricsPaginator.paginate" in text

print("VivoCarLyrics source contract passed")
