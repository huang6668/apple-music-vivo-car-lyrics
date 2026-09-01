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

# Republishing the MediaItem rebuilds session MediaMetadata and resets the native PlaybackState.
# Doing it per lyric line is what removed Atomic Player's progress bar and reloaded cluster cover
# art. It is permitted in exactly one place: republishForDuration, at most once per track, to give
# Media3 a chance to emit a legacy METADATA_KEY_DURATION that Atomic Player can latch.
republish = method_body("private static void republishForDuration(")

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

# The republish must be gated per track and must verify the item genuinely lacks a duration.
assert "durationRepublishedGeneration == generation" in republish, (
    "republishForDuration must not fire twice for the same track"
)
assert "durationRepublishedGeneration = generation" in republish, (
    "republishForDuration must record that it fired"
)
assert "extractDurationFromItem(mediaItem) > 0L" in republish, (
    "republishForDuration must only rescue items that carry no duration of their own"
)
assert "matchesExpectedMedia(" in republish, (
    "republishForDuration must confirm the item still matches the expected track"
)
assert 'invokeRequired(manager, "I", newMediaItem' in republish, (
    "republishForDuration is the one place allowed to republish"
)

# Republication must appear nowhere else, and must never be reachable from a per-line publish.
assert text.count('invokeRequired(manager, "I", newMediaItem') == 1, (
    "MediaItem republication must exist in exactly one place"
)
assert text.count("setFieldValue(") == 3, (
    "setFieldValue must only be used by republishForDuration (2 calls + 1 definition)"
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
