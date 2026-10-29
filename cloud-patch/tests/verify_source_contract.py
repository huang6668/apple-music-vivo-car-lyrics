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
title_write = method_body("private static boolean writeCorrectedTrack(")
native_title = method_body("private static boolean applyTitleCorrection(Object mediaItem)")
title_refresh = method_body("private static void applyTitleCorrectionFromState(")
title_binding = method_body("public static CharSequence correctPlayerTitle(")
artist_binding = method_body("public static CharSequence correctPlayerArtist(")

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
    "Never invoke an old or 1609 MediaItem publish method"
)

# Display title/artist fields are the sole, type-checked exception.
assert text.count("field.set(") == title_write.count("field.set(") == 1
for required in ('"z3.y"', '"a"', '"b"', "CharSequence.class",
                 '"com.apple.android.music.playback.model.StoreMediaItem"',
                 '"title"', '"artistName"', "String.class", "Modifier.isStatic",
                 "field.getType() != expected"):
    assert required in title_write, f"Title write must remain narrowly typed: {required}"
assert "TITLE_STATE.snapshot()" not in title_write, "Writes must use the captured result pair"
for body in (metadata, capability, session_extras):
    assert ".set(" not in body and "writeCorrectedTrack(" not in body
for required in ("isCurrentTitle(state)", "APPLE_QUEUE_ID", "APPLE_MEDIA_ID"):
    assert required in native_title, f"Native title requires its own identity: {required}"
for required in ("isCurrentTitle(state)", "titleBindingGeneration == state.generation",
                 "titleItemMatches(state, item)", "!reboundArtist.equals(artist)",
                 'invokeOptional(binding, "q0", item)'):
    assert required in title_refresh
for body in (title_binding, artist_binding):
    assert "titleItemMatches(state, item)" in body
    assert "return stock;" in body
    assert "writeCorrectedTrack(" not in body, "UI hook must substitute, not mutate a model"
assert "TITLE_STATE.currentArtist(" in artist_binding
notification = method_body("private static void refreshTitleNotification(")
for required in ("isCurrentTitle(state)", 'getFieldValue(service, "y") != session',
                 'invokeOptional(session, "c") != state.manager', '"J4.b3"', '"J4.k2"'):
    assert required in notification, "Notification refresh must validate its exact owner: " + required
assert "writeCorrectedTrack(" not in notification
assert "getPersistentId" not in method_body("private static String titleCatalogId(")
assert '"getId"' not in method_body("private static String titleCatalogId(")
assert "TITLE_STATE.completeTrack(request, title, artist)" in text
assert "CatalogTitleResolver.queryTrack(" in method_body("private static void startTitleQuery(")
assert "TITLE_STATE.restoreTrack(state, trackInfo)" in method_body("private static void loadPersistedTitle(")
assert "TITLE_STATE.fail(request)" in text
assert "public static String resolveItemTitle(Object item, String stockTitle)" in text
assert "TITLE_STATE.getCachedTrack(catalogId)" in text
assert "ChineseConverter.toSimplified(" not in method_body(
    "public static String resolveItemTitle(Object item, String stockTitle)"
), "Cached titles are already simplified; the getter must not convert again"
assert "WeakReference<Object>" in text
assert 'private static final String TITLE_PROFILE = "cn_v1";' in text
assert 'private static final String TITLE_LANGUAGE = "zh-CN";' in text
for body in (
    title_binding,
    artist_binding,
    method_body("public static String resolveItemTitle("),
    method_body("public static String resolveItemArtist("),
    method_body("private static void beginTitleCorrection("),
    method_body("private static boolean isCurrentTitle("),
    method_body("private static void requestTitleCorrection("),
    method_body("private static void startTitleQuery("),
):
    assert "Locale.getDefault()" not in body, "Fixed mainland titles must not follow system locale"
assert "TITLE_IO.execute(" in method_body("private static void loadPersistedTitle(")
assert "persistTracks(Collections.singletonMap(catalogId, track))" in method_body(
    "private static void persistTrack(")
persist = method_body("private static void persistTracks(")
assert "TITLE_IO.execute(" in persist
assert "flushTrackWrites(directory)" in persist, "The queued write must drain the pending batch"
assert "PENDING_TRACK_WRITES.putAll(tracks)" in persist
assert "TITLE_STATE.snapshot()" not in persist, "Disk writes must not borrow the next track's artist"
prefetch = method_body("private static void drainPrefetchQueue(")
assert "hit == null || !hit.artistResolved" in prefetch, "Legacy disk hits must still fetch artist"
assert "refreshCurrentCachedTrack()" in method_body("private static void queryCatalogBatch(")
batch_query = method_body("private static void queryCatalogBatch(")
assert "TITLE_STATE.beginCacheRequest(catalogIds)" in batch_query
assert "TITLE_STATE.putCachedTracks(cacheRequest, tracks)" in batch_query
assert "persistBatchTracks(accepted)" in batch_query, "Do not persist rejected stale batches"
assert "settled.compareAndSet(false, true)" in batch_query, "Timed-out batches must reject late updates"
assert "TITLE_STATE.beginCacheRestore(batch)" in prefetch
assert "TITLE_STATE.putCachedTracks(diskRequest, diskHits)" in prefetch
assert "TITLE_STATE.refreshCachedTrack(state)" in method_body("private static void refreshCurrentCachedTrack(")

# Atomic capability mutation remains in-place and independent of title correction.
assert "ATOMIC_SUPPORT_EVENTS" in capability
assert "ATOMIC_LYRIC_SUPPORT_EVENT" in capability
assert "ATOMIC_BASELINE_SUPPORT_EVENTS" in capability
# Atomic's SeekBarLayout needs (support_event & 16) or it renders "--:--" regardless of duration.
assert "ATOMIC_SEEK_SUPPORT_EVENT" in capability, (
    "Seek/time-info bit 16 must be ORed in, Atomic hides the progress bar without it"
)
# Atomic's control-panel list entry (MusicControlPanelView / f5/w0.java) only reacts when bit 64
# is advertised; the cooperation controller reads the app value verbatim.
assert "ATOMIC_LIST_SUPPORT_EVENT" in capability, (
    "List-entry bit 64 must be ORed in, or the 播放列表 entry is a silent no-op"
)
for forbidden in ("new Bundle(", "constructCompatible(", 'invokeRequired(manager, "I"'):
    assert forbidden not in capability, (
        f"Capability bit must be ORed in place, not republished: {forbidden}"
    )

# 车联 6.0.8.3 reads only the Atomic lrc_change event; the legacy head-unit keys were dropped
# in r46. r37 stopped publishing the ucar instrument-cluster keys; neither may come back.
assert "music.media.extras" not in text, "Legacy music.media.extras.* keys were removed in r46"
assert "extras.putString(ATOMIC_ACTION_KEY, ATOMIC_LRC_CHANGE)" in session_extras
assert "ucar.media.metadata" not in session_extras, (
    "Instrument-cluster keys were removed in r37; do not publish them from session Extras"
)

for forbidden in (
    'extras.remove("android.media.metadata.ART")',
    'extras.remove("android.media.metadata.ALBUM_ART")',
    'extras.remove("android.media.metadata.DISPLAY_ICON")',
):
    assert forbidden not in session_extras, f"Native artwork must stay intact: {forbidden}"

assert "publishLineExtras" not in text, "r51 removed the per-line session-Extras heartbeat"
assert "dispatchSessionExtras" not in line_publish, (
    "Line updates must not touch session Extras; only the track change, the whole-LRC event and "
    "the 25s keepalive write them"
)
assert "publishMetadata" not in line_publish
assert "metadataLine" not in text
assert "ClusterLyricsPaginator.paginate" in text

# The head unit parses the whole LRC payload itself, so every row this helper ships must stay
# a single, machine-readable [mm:ss.cc] line. 车联's reader (ub.e) only accepts 1-2 digit
# minutes; a three-digit stamp yields no time token, the row is dropped, and an all-dropped
# payload falls back to raw rows whose prefixes then reach the screen.
assert "LRC_MAX_MILLIS = 99L * 60000L" in text, "Keep every LRC stamp inside two minute digits"
assert "Math.min(Math.max(0L, millis), LRC_MAX_MILLIS)" in method_body(
    "private static String formatTime("), "formatTime must clamp to the two-digit ceiling"
publish_lines = method_body("private static void publishLines(")
assert "clampTimeline" not in text, (
    "Squeezing the timeline distorts the cluster paging that reads the same times"
)
assert "mergeEqualTimes(times, texts)" in publish_lines
assert "sortTimeline(times, texts)" in publish_lines
assert "hasIncreasingTime" not in publish_lines, (
    "One out-of-order line must not flatten the whole song onto a synthetic grid"
)

# Raw LRC: metadata rows are dropped, untimed rows keep one line each.
raw_lyrics = method_body("private static void consumeRawLyrics(")
assert "LRC_META_TAG.matcher(row.trim()).matches()" in raw_lyrics, (
    "Only a row that is nothing but ID tags may be dropped"
)
assert "addLine(times, texts, 0L, clean)" in raw_lyrics, (
    "Untimed rows must not merge: every one of them arrives at time 0"
)
assert "times.add(Long.valueOf(0L))" not in raw_lyrics
add_line = method_body("private static void addLine(")
assert "times.add(Long.valueOf(time))" in add_line
assert " / " not in add_line, "addLine must not fold two rows into one"

sanitise = method_body("private static String sanitizeLineText(")
for required in ("'\\n'", "'\\r'", "'\\u2028'", "'\\u2029'", "INLINE_TIME",
                 "WHITESPACE_RUN"):
    assert required in sanitise, "sanitizeLineText must flatten one row per line: " + required
assert ".replace('^', '\\uff3e')" in sanitise, (
    "车联 turns '^' into a line break; map it to the fullwidth caret, do not delete it"
)
assert r'[ \\t]+' in text, "Only spaces and tabs collapse; NBSP is lyric content"
assert "sanitizeLineText(text)" in method_body("private static void addLine(")

# Every row - the last one included - must be CRLF terminated. Verified against the smali of
# 车联's reader (ub.e.e, classes3.dex): the row-text extractor takes substring(start, length-1)
# whenever the row's last character is neither '\n' nor '\r'. With LF-only rows it therefore
# eats the last character of every line, a one-character line becomes empty and is dropped, and
# a payload whose rows all end up empty falls back to "every raw row" - which is how a literal
# [mm:ss.cc] prefix reaches a screen. For a CRLF row the same code strips exactly the trailing
# '\r', which is the shape this reader was written for.
assert 'private static final String LRC_ROW_SEPARATOR = "\\r\\n";' in text
assert ".append(LRC_ROW_SEPARATOR)" in publish_lines, (
    "Every LRC row must be CRLF terminated, not LF separated"
)
assert "lrc.append('\\n')" not in publish_lines, "Rows are terminated, never merely separated"
assert "index + 1 < texts.length" not in publish_lines, (
    "Terminate the last row too: a missing trailing CR eats its last character"
)

# r51: the empty per-line Bundle is gone. It could only re-broadcast the previous lrc_change
# (Media3 merges stored extras into every setExtras: l0.j / C2786g2.m4607e), 车联 drops that on
# exact-string dedup, and some component versions read an empty Bundle as "no lyrics" - the
# 7tattoo/vivo-car-atomic-lyrics skill reports exactly that symptom. Session Extras are now
# written only for the track-change clear, the whole-LRC event and its 25s keepalive.
assert "setExtras MERGES here" not in text
assert "setExtras replaces wholesale" not in text
assert "ATOMIC_KEEPALIVE_MS" in text
for required in ("publishAtomicClear(", "publishAtomicExtras("):
    assert required in text, "The two whole-payload writers must stay: " + required

# Delaying our first whole-LRC publish does not prevent 车联's stale cover: the compress callback
# pushes the previous track's art itself and only self-heals on the next encode. Deferring the
# lyric only cost the opening lines of every track, so the delay must stay out.
for forbidden in ("COVER_SETTLE_MS", "DeferredPublish", "trackChangeUptime"):
    assert forbidden not in text, f"The cover-settle deferral was removed in r47: {forbidden}"

assert 'BUILD_MARKER = "vivo-car-cn-track-r55-1611-2026-10-29"' in text

print("VivoCarLyrics source contract passed")
