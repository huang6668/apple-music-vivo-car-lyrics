#!/usr/bin/env python3
from pathlib import Path
import re


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
    "private static void requestLinePublish(Object manager, String line, String clusterLine,"
)
title_write = method_body("private static boolean writeCorrectedTitle(")
native_title = method_body("private static boolean applyTitleCorrection(Object mediaItem)")
title_refresh = method_body("private static void applyTitleCorrectionFromState(")
title_binding = method_body("public static CharSequence correctPlayerTitle(")

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
assert not re.search(r'invoke(?:Required|Optional)\([^;\n]*,\s*"[PI]"\s*[,)]', text), (
    "Never invoke an old or 1607 MediaItem publish method"
)

# Title fields are the sole, type-checked exception. Do not relax Atomic or session code.
assert text.count("field.set(") == title_write.count("field.set(") == 1
for required in ('"z3.x"', '"a"', "CharSequence.class",
                 '"com.apple.android.music.playback.model.StoreMediaItem"',
                 '"title"', "String.class", "Modifier.isStatic", "field.getType() != expected"):
    assert required in title_write, f"Title write must remain narrowly typed: {required}"
for body in (metadata, capability, session_extras):
    assert ".set(" not in body and "writeCorrectedTitle(" not in body
for required in ("isCurrentTitle(state)", "APPLE_QUEUE_ID", "APPLE_MEDIA_ID"):
    assert required in native_title, f"Native title requires its own identity: {required}"
for required in ("isCurrentTitle(state)", "titleBindingGeneration == state.generation",
                 "titleItemMatches(state, item)"):
    assert required in title_refresh
assert "titleItemMatches(state, item)" in title_binding
assert "return stock;" in title_binding
assert "writeCorrectedTitle(" not in title_binding, "UI hook must substitute, not mutate a model"
notification = method_body("private static void refreshTitleNotification(")
for required in ("isCurrentTitle(state)", 'getFieldValue(service, "y") != session',
                 'invokeOptional(session, "c") != state.manager', '"J4.Y2"', '"J4.f2"'):
    assert required in notification, "Notification refresh must validate its exact owner: " + required
assert "writeCorrectedTitle(" not in notification
assert "getPersistentId" not in method_body("private static String titleCatalogId(")
assert '"getId"' not in method_body("private static String titleCatalogId(")
assert "TITLE_STATE.complete(request, title)" in text
assert "TITLE_STATE.fail(request)" in text
assert "WeakReference<Object>" in text

# Atomic capability mutation remains in-place and independent of title correction.
assert "ATOMIC_SUPPORT_EVENTS" in capability
assert "ATOMIC_LYRIC_SUPPORT_EVENT" in capability
assert "ATOMIC_BASELINE_SUPPORT_EVENTS" in capability
# Atomic's SeekBarLayout needs (support_event & 16) or it renders "--:--" regardless of duration.
assert "ATOMIC_SEEK_SUPPORT_EVENT" in capability, (
    "Seek/time-info bit 16 must be ORed in, Atomic hides the progress bar without it"
)
for forbidden in ("new Bundle(", "constructCompatible(", 'invokeRequired(manager, "I"'):
    assert forbidden not in capability, (
        f"Capability bit must be ORed in place, not republished: {forbidden}"
    )

# Only the car head unit is served through session Extras. r37 stopped publishing the ucar
# instrument-cluster keys entirely; they must not come back through this path.
for required in (
    "extras.putString(EXTRA_LINE",
    "extras.putBoolean(EXTRA_ALLOWED",
):
    assert required in session_extras, f"Missing session Extras contract: {required}"
assert "ucar.media.metadata" not in session_extras, (
    "Instrument-cluster keys were removed in r37; do not publish them from session Extras"
)

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
