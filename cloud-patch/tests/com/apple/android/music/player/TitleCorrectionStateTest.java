package com.apple.android.music.player;

public final class TitleCorrectionStateTest {
    public static void main(String[] args) {
        testCatalogIdentity();
        testMissingIdentityAndEnrichment();
        testRequestSuppression();
        testIdentityGuards();
        testLateResults();
        testNegativeResults();
        testSameTitleCache();
        testLru();
        testLatestResponseWinsCache();
        testPlaybackErrorReset();
        testTransientFailure();
        testStaleAndForeignFailure();
        testForeignTokens();
        testSnapshotIdentity();
        testInvalidQueueKeys();
        testResetCompletedState();
        testCacheNamespaces();
        testNamespaceLateResults();
        testNamespaceRequestSuppression();
        testNamespaceLruBound();
        testSnapshotsAndStockPreservation();
        testInvalidCacheSize();
        testPersistentRestoreGuards();
        testDirectCacheAccess();
        testTrackCompletionIdentity();
        testLateArtistResults();
        testLegacyTrackBackfill();
        testTrackRestoreGuards();
        testTrackCacheBoundsAndNamespace();
        testPrefetchRefreshPreservesRequest();
        testCacheRequestOrdering();
        testCacheDiskRestoreBarrier();
        testCacheTicketNamespaceAndTargets();
        testCacheTicketEvictionAndNegativeHits();
        System.out.println("TitleCorrectionState tests passed");
    }

    private static void testTrackCompletionIdentity() {
        Object manager = new Object();
        TitleCorrectionState state = started(manager, "1", "10", "Stock title");
        CharSequence stock = new StringBuilder("Stock artist");
        check(state.currentArtist(manager, "1", "10", stock) == stock,
                "unresolved artist preserves the original object");
        TitleCorrectionState.Snapshot before = state.snapshot();
        check(state.completeTrack(state.request(), " Mainland title ", " Mainland artist "),
                "current full track accepted");
        equal("Mainland title", state.correctedTitle(manager, "1", "10"));
        equal("Mainland artist", state.currentArtist(manager, "001", "0010", stock));
        equal("Mainland artist", state.snapshot().correctedArtist);
        check(state.snapshot().artistResolved, "full query records artist resolution");
        equal(null, before.correctedArtist);
        check(!before.artistResolved, "old snapshot remains immutable");
        check(state.currentArtist(new Object(), "1", "10", stock) == stock,
                "another manager cannot receive the correction");
        check(state.currentArtist(manager, "2", "10", stock) == stock,
                "another queue cannot receive the correction");
        check(state.currentArtist(manager, "1", "20", stock) == stock,
                "another catalog item cannot receive the correction");
        CharSequence same = new StringBuilder("Mainland artist");
        check(state.currentArtist(manager, "1", "10", same) == same,
                "same artist preserves the original CharSequence");
        equal("Mainland artist", state.currentArtist(manager, "1", "10", null));
        state.begin(manager, "2", "10", "", "Stock title");
        check(state.request() == null, "full cached track avoids another query");
        equal("Mainland artist", state.currentArtist(manager, "2", "10", stock));
    }

    private static void testLateArtistResults() {
        Object manager = new Object();
        TitleCorrectionState state = started(manager, "1", "10", "Original");
        TitleCorrectionState.Request old = state.request();
        state.begin(manager, "2", "10", "", "Same catalog, new play");
        TitleCorrectionState.Request current = state.request();
        check(state.completeTrack(current, "New title", "New artist"), "new track result");
        check(!state.acceptsResult(old), "late older result cannot be persisted over newer artist");
        check(!state.completeTrack(old, "Old title", "Old artist"), "old generation cannot apply");
        equal("New artist", state.snapshot().correctedArtist);
        equal("New artist", state.getCachedArtist("10"));
        state.begin(manager, "3", "10", "", "Replay");
        check(state.request() == null, "newest full track remains cached");
        equal("New artist", state.snapshot().correctedArtist);

        state.begin(manager, "4", "20", "", "Another song");
        old = state.request();
        state.begin(manager, "5", "30", "", "Current song");
        current = state.request();
        check(!state.completeTrack(old, "Late title", "Late artist"),
                "other catalog late result only warms its own cache");
        equal(null, state.snapshot().correctedArtist);
        check(state.completeTrack(current, "Current title", "Current artist"), "current succeeds");
        equal("Late artist", state.getCachedArtist("20"));
        equal("Current artist", state.snapshot().correctedArtist);
        check(!state.completeTrack(old, "Duplicate", "Duplicate"), "duplicate callback ignored");

        state.begin(manager, "6", "40", "", "Timeout");
        TitleCorrectionState.Request timedOut = state.request();
        check(state.fail(timedOut), "timeout settles its generation");
        check(!state.completeTrack(timedOut, "Too late", "Too late"),
                "timeout result cannot poison title or artist cache");
        equal(null, state.getCachedTrack("40"));
    }

    private static void testLegacyTrackBackfill() {
        Object manager = new Object();
        TitleCorrectionState state = started(manager, "1", "10", "Stock title");
        check(state.restoreTrack(state.snapshot(),
                new TitleCorrectionState.TrackInfo("Cached title", "", false)), "partial disk hit");
        equal("Cached title", state.correctedTitle(manager, "1", "10"));
        check(!state.snapshot().completed && !state.snapshot().artistResolved,
                "legacy disk hit displays the title without declaring the artist complete");
        TitleCorrectionState.Request request = state.request();
        check(request != null && state.snapshot().pending, "partial track queries the artist");
        check(state.request() == null, "partial track has only one active query");
        equal("Cached title", state.correctedTitle(manager, "1", "10"));
        check(state.completeTrack(request, "Resolved title", "Resolved artist"), "backfill succeeds");
        equal("Resolved artist", state.snapshot().correctedArtist);
        state.begin(manager, "2", "10", "", "Replay");
        check(state.request() == null, "backfilled track does not requery on replay");
        equal("Resolved artist", state.snapshot().correctedArtist);

        state.begin(manager, "3", "20", "", "Unknown artist");
        request = state.request();
        check(state.completeTrack(request, "Resolved without artist", ""),
                "successful response may have no artist");
        check(state.getCachedTrack("20").artistResolved, "empty artist is still resolved");
        state.begin(manager, "4", "20", "", "Replay without artist");
        check(state.request() == null, "missing artist in a new success does not loop");
        equal(null, state.snapshot().correctedArtist);

        state.putCachedTitle("30", "Old batch title");
        check(!state.getCachedTrack("30").artistResolved, "legacy title batch remains partial");
        state.begin(manager, "5", "30", "", "Stock title");
        request = state.request();
        check(request != null, "legacy memory hit also triggers one artist backfill");
        equal("Old batch title", state.snapshot().correctedTitle);
        check(state.fail(request), "failed backfill settles the current generation");
        equal("Old batch title", state.snapshot().correctedTitle);
        check(state.request() == null, "failed backfill does not repeat in the same generation");
        state.begin(manager, "6", "30", "", "Stock title");
        check(state.request() != null, "transient backfill failure may retry on a later play");
    }

    private static void testTrackRestoreGuards() {
        Object manager = new Object();
        TitleCorrectionState state = started(manager, "1", "10", "Stock");
        TitleCorrectionState.TrackInfo track =
                new TitleCorrectionState.TrackInfo("Disk title", "Disk artist");
        TitleCorrectionState.Snapshot stale = state.snapshot();
        state.begin(manager, "1", "10", "", "Another generation");
        check(!state.restoreTrack(stale, track), "same catalog old disk generation rejected");
        TitleCorrectionState.Snapshot current = state.snapshot();
        TitleCorrectionState.Request request = state.request();
        check(!state.restoreTrack(current, track), "disk cannot overtake current network result");
        check(state.completeTrack(request, "Network title", "Network artist"), "network accepted");
        check(!state.restoreTrack(current, track), "completed track cannot be replaced by disk");

        state.begin(manager, "2", "20", "", "Other");
        state.putCachedTrack("20", "Prefetch title", "Prefetch artist");
        check(state.restoreTrack(state.snapshot(),
                new TitleCorrectionState.TrackInfo("Old disk title", "", false)),
                "existing complete memory result wins over partial disk");
        equal("Prefetch title", state.snapshot().correctedTitle);
        equal("Prefetch artist", state.snapshot().correctedArtist);
        check(state.request() == null, "partial disk cannot downgrade a complete cache hit");
        state.putCachedTitle("20", "Updated legacy title");
        check(state.getCachedTrack("20").artistResolved,
                "legacy title refresh cannot downgrade artist completion");
        equal("Prefetch artist", state.getCachedArtist("20"));
        state.begin(manager, "3", "20", "", "Replay");
        check(state.restoreTrack(state.snapshot(),
                new TitleCorrectionState.TrackInfo("Old full disk title", "Old full disk artist")),
                "existing complete memory result also wins over complete disk restoration");
        equal("Prefetch title", state.snapshot().correctedTitle);
        equal("Prefetch artist", state.snapshot().correctedArtist);
    }

    private static void testTrackCacheBoundsAndNamespace() {
        TitleCorrectionState state = new TitleCorrectionState(2);
        state.putCachedTrack("10", "Ten", "Artist ten");
        state.putCachedTrack("20", "Twenty", "Artist twenty");
        state.putCachedTrack("30", "Thirty", "Artist thirty");
        equal(null, state.getCachedTitle("10"));
        equal(null, state.getCachedArtist("10"));
        equal(null, state.getCachedTrack("10"));
        equal("Artist twenty", state.getCachedArtist("20"));

        Object manager = new Object();
        state = started(manager, "1", "10", "Stock");
        state.setCacheNamespace("en");
        TitleCorrectionState.Request english = state.request();
        state.setCacheNamespace("cn");
        TitleCorrectionState.Request chinese = state.request();
        check(!state.completeTrack(english, "English", "English artist"),
                "late other-namespace track cannot apply");
        equal(null, state.getCachedArtist("10"));
        state.completeTrack(chinese, "Chinese", "Chinese artist");
        equal("Chinese artist", state.getCachedArtist("10"));
        equal("Chinese artist", state.snapshot().correctedArtist);
        state.setCacheNamespace("en");
        equal("English artist", state.getCachedArtist("10"));
        check(state.request() == null, "complete result retained within its namespace");
        equal("English artist", state.snapshot().correctedArtist);
        state.setCacheNamespace("cn");
        equal("Chinese artist", state.getCachedArtist("10"));

        state.begin(manager, "2", "20", "", "Missing");
        state.completeTrack(state.request(), "", "");
        TitleCorrectionState.TrackInfo negative = state.getCachedTrack("20");
        check(negative != null && negative.artistResolved, "negative cache is a complete track hit");
        equal("", negative.title);
        equal("", negative.artist);
        state.begin(manager, "3", "20", "", "Missing replay");
        check(state.request() == null && state.snapshot().completed,
                "negative track replay does not start another request");
    }

    private static void testPrefetchRefreshPreservesRequest() {
        Object manager = new Object();
        TitleCorrectionState state = started(manager, "1", "10", "Stock");
        check(state.restore(state.snapshot(), "Legacy settled title"), "legacy title-only API settles");
        TitleCorrectionState.Snapshot settled = state.snapshot();
        state.putCachedTrack("10", "Prefetched title", "Prefetched artist");
        check(state.refreshCachedTrack(settled), "prefetch enriches an already-settled legacy title");
        equal("Prefetched artist", state.snapshot().correctedArtist);
        check(state.snapshot().completed && !state.snapshot().pending, "settled status preserved");

        state.begin(manager, "2", "20", "", "Stock");
        TitleCorrectionState.Request request = state.request();
        TitleCorrectionState.Snapshot pending = state.snapshot();
        state.putCachedTrack("20", "", "Artist-only prefetch");
        check(state.refreshCachedTrack(pending), "prefetch can enrich an active single-track query");
        equal("Artist-only prefetch", state.snapshot().correctedArtist);
        check(state.snapshot().pending && !state.snapshot().completed,
                "prefetch does not consume or settle the active request");
        check(!state.acceptsResult(request), "newer full prefetch protects persistence from the older query");
        check(state.completeTrack(request, "Network title", "Network artist"),
                "same-generation network callback can still settle its active request");
        equal("Artist-only prefetch", state.snapshot().correctedArtist);

        state.begin(manager, "2", "20", "", "Another generation");
        check(!state.refreshCachedTrack(pending), "old same-ID snapshot cannot refresh the new play");
        pending = state.snapshot();
        state.setCacheNamespace("Other");
        check(!state.refreshCachedTrack(pending), "other namespace cannot refresh the active track");

        state = new TitleCorrectionState(1);
        state.begin(manager, "1", "10", "", "Stock");
        TitleCorrectionState.Request old = state.request();
        state.begin(manager, "2", "10", "", "New play");
        request = state.request();
        state.completeTrack(request, "Current title", "Current artist");
        settled = state.snapshot();
        state.putCachedTrack("20", "Evict title", "Evict artist");
        check(!state.acceptsResult(old), "old callback stays rejected even after cache eviction");
        check(!state.completeTrack(old, "Old title", "Old artist"),
                "eviction does not let an old result replace the current resolved track");
        equal(null, state.getCachedTrack("10"));
        equal("Current artist", state.snapshot().correctedArtist);
        state.putCachedTrack("10", new TitleCorrectionState.TrackInfo("Partial title", "", false));
        check(!state.refreshCachedTrack(settled), "partial cache cannot downgrade the current full track");
        equal("Current title", state.snapshot().correctedTitle);
        equal("Current artist", state.snapshot().correctedArtist);
    }

    private static void testCacheRequestOrdering() {
        Object manager = new Object();
        TitleCorrectionState state = started(manager, "1", "10", "Stock");
        TitleCorrectionState.CacheRequest oldBatch = state.beginCacheRequest(
                java.util.Collections.singletonList("10"));
        TitleCorrectionState.Request newerSingle = state.request();
        check(state.completeTrack(newerSingle, "New single title", "New single artist"),
                "newer single request completes");
        check(state.putCachedTracks(oldBatch, trackMap("10", "Old batch title", "Old batch artist")).isEmpty(),
                "late batch cannot overwrite a newer-started single request");
        equal("New single artist", state.getCachedArtist("10"));
        equal("New single artist", state.snapshot().correctedArtist);

        TitleCorrectionState.CacheRequest first = state.beginCacheRequest(
                java.util.Collections.singletonList("10"));
        TitleCorrectionState.CacheRequest second = state.beginCacheRequest(
                java.util.Collections.singletonList("10"));
        equal(1, state.putCachedTracks(first, trackMap("10", "First title", "First artist")).size());
        equal(1, state.putCachedTracks(second, trackMap("10", "Second title", "Second artist")).size());
        check(state.refreshCachedTrack(state.snapshot()), "newer same-generation batch refreshes the UI");
        equal("Second artist", state.snapshot().correctedArtist);

        first = state.beginCacheRequest(java.util.Collections.singletonList("10"));
        second = state.beginCacheRequest(java.util.Collections.singletonList("10"));
        equal(1, state.putCachedTracks(second, trackMap("10", "Latest title", "Latest artist")).size());
        check(state.putCachedTracks(first, trackMap("10", "Out-of-order title", "Out-of-order artist")).isEmpty(),
                "out-of-order batch callbacks follow request starts, not response arrival");
        equal("Latest artist", state.getCachedArtist("10"));

        state.begin(manager, "2", "20", "", "Stock");
        TitleCorrectionState.Request olderSingle = state.request();
        TitleCorrectionState.CacheRequest newerBatch = state.beginCacheRequest(
                java.util.Collections.singletonList("20"));
        equal(1, state.putCachedTracks(newerBatch, trackMap("20", "Prefetch title", "Prefetch artist")).size());
        check(state.refreshCachedTrack(state.snapshot()), "newer full prefetch applies while query is pending");
        check(state.snapshot().pending, "prefetch retains the original active query token");
        check(!state.acceptsResult(olderSingle), "older single cannot persist over a newer full batch result");
        check(state.completeTrack(olderSingle, "Older single title", "Older single artist"),
                "older single result still consumes and settles the active token");
        check(state.snapshot().completed && !state.snapshot().pending, "active query is settled normally");
        equal("Prefetch title", state.snapshot().correctedTitle);
        equal("Prefetch artist", state.snapshot().correctedArtist);
        equal("Prefetch artist", state.getCachedArtist("20"));
    }

    private static void testCacheDiskRestoreBarrier() {
        Object manager = new Object();
        TitleCorrectionState state = started(manager, "1", "10", "Stock");
        TitleCorrectionState.Request network = state.request();
        TitleCorrectionState.CacheRequest disk = state.beginCacheRestore(
                java.util.Collections.singletonList("10"));
        check(state.completeTrack(network, "Fresh title", "Fresh artist"), "network finishes during disk read");
        check(state.putCachedTracks(disk, trackMap("10", "Old disk title", "Old disk artist")).isEmpty(),
                "a later-started disk read cannot replace a network write that occurred during it");
        equal("Fresh artist", state.getCachedArtist("10"));
        disk = state.beginCacheRestore(java.util.Collections.singletonList("10"));
        check(state.putCachedTracks(disk, trackMap("10", "Old disk title", "Old disk artist")).isEmpty(),
                "complete in-memory data always takes priority over disk restoration");

        state.begin(manager, "2", "20", "", "Stock");
        disk = state.beginCacheRestore(java.util.Collections.singletonList("20"));
        state.putCachedTitle("20", "New partial title");
        java.util.Map<String, TitleCorrectionState.TrackInfo> oldPartial =
                trackMap("20", new TitleCorrectionState.TrackInfo("Old partial title", "", false));
        check(state.putCachedTracks(disk, oldPartial).isEmpty(),
                "disk read barrier also protects newer partial writes");
        equal("New partial title", state.getCachedTitle("20"));
        disk = state.beginCacheRestore(java.util.Collections.singletonList("20"));
        equal(1, state.putCachedTracks(disk, trackMap("20", "Disk full title", "Disk full artist")).size());
        check(state.refreshCachedTrack(state.snapshot()), "fresh disk full entry may upgrade an unchanged partial");
        equal("Disk full artist", state.snapshot().correctedArtist);
    }

    private static void testCacheTicketNamespaceAndTargets() {
        Object manager = new Object();
        TitleCorrectionState state = started(manager, "1", "10", "Stock");
        state.setCacheNamespace("cn");
        TitleCorrectionState.CacheRequest china = state.beginCacheRequest(
                java.util.Arrays.asList("0010", "invalid"));
        state.setCacheNamespace("other");
        java.util.Map<String, TitleCorrectionState.TrackInfo> response =
                trackMap("10", "China title", "China artist");
        response.put("20", new TitleCorrectionState.TrackInfo("Unrequested title", "Unrequested artist"));
        response.put("invalid", new TitleCorrectionState.TrackInfo("Invalid", "Invalid"));
        equal(1, state.putCachedTracks(china, response).size());
        equal(null, state.getCachedTrack("10"));
        equal(null, state.getCachedTrack("20"));
        equal(null, state.snapshot().correctedArtist);
        state.setCacheNamespace("cn");
        equal("China artist", state.getCachedArtist("10"));
        equal(null, state.getCachedTrack("20"));
        check(state.request() == null, "late batch safely warms only its captured namespace");
        equal("China artist", state.snapshot().correctedArtist);

        TitleCorrectionState foreign = started(manager, "1", "10", "Stock");
        check(foreign.putCachedTracks(china, response).isEmpty(), "foreign cache ticket rejected");
        equal(null, foreign.getCachedTrack("10"));
        check(state.putCachedTracks(null, response).isEmpty(), "null cache ticket rejected");
        check(state.putCachedTracks(china, null).isEmpty(), "null track map rejected");
    }

    private static void testCacheTicketEvictionAndNegativeHits() {
        Object manager = new Object();
        TitleCorrectionState state = new TitleCorrectionState(1);
        state.begin(manager, "1", "10", "", "Stock");
        TitleCorrectionState.CacheRequest oldBatch = state.beginCacheRequest(
                java.util.Collections.singletonList("10"));
        TitleCorrectionState.CacheRequest oldDisk = state.beginCacheRestore(
                java.util.Collections.singletonList("10"));
        TitleCorrectionState.Request newer = state.request();
        state.completeTrack(newer, "Current title", "Current artist");
        state.putCachedTrack("20", "Evict title", "Evict artist");
        check(state.putCachedTracks(oldBatch, trackMap("10", "Old batch title", "Old batch artist")).isEmpty(),
                "eviction does not let a late batch replace the current newer track");
        check(state.putCachedTracks(oldDisk, trackMap("10", "Old disk title", "Old disk artist")).isEmpty(),
                "eviction does not let a late disk read replace the current newer track");
        equal(null, state.getCachedTrack("10"));
        equal("Current artist", state.snapshot().correctedArtist);

        state.begin(manager, "2", "30", "", "Missing");
        TitleCorrectionState.CacheRequest miss = state.beginCacheRequest(
                java.util.Collections.singletonList("30"));
        java.util.Map<String, TitleCorrectionState.TrackInfo> empty =
                trackMap("30", "", "");
        empty.put("40", new TitleCorrectionState.TrackInfo("", ""));
        equal(1, state.putCachedTracks(miss, empty).size());
        check(state.getCachedTrack("30").artistResolved, "authorized miss is a complete negative cache entry");
        equal(null, state.getCachedTrack("40"));
        check(state.request() == null && state.snapshot().completed,
                "a complete negative batch result suppresses a repeated single-track query");
        state.begin(manager, "3", "30", "", "Missing replay");
        check(state.request() == null, "negative batch cache also survives playback replay");
    }

    private static java.util.Map<String, TitleCorrectionState.TrackInfo> trackMap(
            String id, String title, String artist) {
        return trackMap(id, new TitleCorrectionState.TrackInfo(title, artist));
    }

    private static java.util.Map<String, TitleCorrectionState.TrackInfo> trackMap(
            String id, TitleCorrectionState.TrackInfo info) {
        java.util.Map<String, TitleCorrectionState.TrackInfo> result =
                new java.util.LinkedHashMap<String, TitleCorrectionState.TrackInfo>();
        result.put(id, info);
        return result;
    }

    private static void testPersistentRestoreGuards() {
        Object manager = new Object();
        TitleCorrectionState state = started(manager, "1", "10", "Original");
        TitleCorrectionState.Snapshot first = state.snapshot();
        check(!state.restore(first, ""), "empty disk entries are ignored");
        check(state.restore(first, "Mainland"), "matching disk entry settles generation");
        equal("Mainland", state.correctedTitle(manager, "1", "10"));
        check(state.request() == null, "disk hit skips network");
        check(!state.restore(first, "Overwrite"), "settled generation is immutable");
        state.begin(manager, "2", "20", "", "Second");
        check(!state.restore(first, "Wrong"), "late disk hit cannot change another track");
        TitleCorrectionState.Snapshot second = state.snapshot();
        TitleCorrectionState.Request request = state.request();
        check(!state.restore(second, "Overtake network"), "disk hit cannot supersede active query");
        check(state.acceptsResult(request), "active completion may persist");
        state.fail(request);
        check(!state.acceptsResult(request), "timed out result must not persist");
        TitleCorrectionState other = started(new Object(), "2", "20", "Other");
        check(!other.acceptsResult(request), "foreign result cannot persist");
    }

    private static void testCatalogIdentity() {
        equal("", TitleCorrectionState.normalizeCatalogId(null));
        equal("", TitleCorrectionState.normalizeCatalogId(""));
        equal("", TitleCorrectionState.normalizeCatalogId("0"));
        equal("", TitleCorrectionState.normalizeCatalogId("0000"));
        equal("", TitleCorrectionState.normalizeCatalogId("-12"));
        equal("", TitleCorrectionState.normalizeCatalogId("+12"));
        equal("", TitleCorrectionState.normalizeCatalogId("12.0"));
        equal("", TitleCorrectionState.normalizeCatalogId("i.123"));
        equal("", TitleCorrectionState.normalizeCatalogId("12 34"));
        equal("", TitleCorrectionState.normalizeCatalogId("\uff11\uff12"));
        equal("12", TitleCorrectionState.normalizeCatalogId(" 0012 "));
        equal("123456789012345678901", TitleCorrectionState.normalizeCatalogId(
                "123456789012345678901"));
        equal("21", TitleCorrectionState.selectCatalogId("i.local", "0", "0021", "22"));
        equal("", TitleCorrectionState.selectCatalogId((String[]) null));
        equal("", TitleCorrectionState.selectCatalogId("i.local", "-4"));
    }

    private static void testMissingIdentityAndEnrichment() {
        Object manager = new Object();
        TitleCorrectionState state = new TitleCorrectionState();
        state.begin(manager, "12", "", "987654321", "Original");
        check(state.request() == null, "persistentId must not become a catalog ID");
        equal("987654321", state.snapshot().persistentId);
        check(!state.enrich(manager, "13", "42", "", ""), "reject stale queue enrichment");
        check(state.enrich(manager, "0012", "0042", "", "Changed"), "accept current metadata");
        equal("Original", state.snapshot().originalTitle);
        equal("42", state.request().catalogId);

        state.begin(manager, "0", "42", "", "Unknown queue");
        check(state.request() == null, "unknown queue cannot request");
        check(!state.enrich(manager, "12", "42", "", ""), "unknown queue cannot adopt a callback");
        state.begin(null, "12", "42", "", "No manager");
        check(state.request() == null, "unknown manager cannot request");

        state.begin(manager, "12", "", "", "");
        check(state.enrich(manager, "12", "42", "library-id", "Recovered"), "fill missing metadata");
        equal("library-id", state.snapshot().persistentId);
        equal("Recovered", state.snapshot().originalTitle);
        check(!state.enrich(manager, "12", "43", "other", "Wrong"), "reject conflicting catalog ID");
        equal("42", state.snapshot().catalogId);
        equal("library-id", state.snapshot().persistentId);
    }

    private static void testRequestSuppression() {
        Object manager = new Object();
        TitleCorrectionState state = started(manager, "1", "10", "Original");
        TitleCorrectionState.Request request = state.request();
        check(request != null, "first request");
        check(state.snapshot().pending, "request is pending");
        check(state.request() == null, "pending duplicate suppressed");
        check(state.enrich(manager, "1", "10", "pid", "Original"), "same identity metadata");
        check(state.request() == null, "metadata duplicate suppressed");
        check(state.complete(request, "Corrected"), "active completion accepted");
        check(state.snapshot().completed && !state.snapshot().pending, "completion state");
        check(state.request() == null, "completed duplicate suppressed");
        check(!state.complete(request, "Wrong"), "double callback ignored");
        equal("Corrected", state.correctedTitle(manager, "1", "10"));
    }

    private static void testIdentityGuards() {
        Object manager = new Object();
        Object otherManager = new Object();
        TitleCorrectionState state = started(manager, "1", "10", "Original");
        state.complete(state.request(), "Corrected");
        equal(null, state.correctedTitle(otherManager, "1", "10"));
        equal(null, state.correctedTitle(manager, "2", "10"));
        equal(null, state.correctedTitle(manager, "1", "11"));
        equal(null, state.correctedTitle(manager, "1", ""));
        equal("Corrected", state.correctedTitle(manager, "001", "0010"));
        check(!state.enrich(otherManager, "1", "10", "", ""), "manager identity enrichment");

        Object equalManager = new Object() {
            @Override
            public boolean equals(Object other) {
                return true;
            }
        };
        equal(null, state.correctedTitle(equalManager, "1", "10"));
    }

    private static void testLateResults() {
        Object manager = new Object();
        TitleCorrectionState state = started(manager, "1", "10", "One");
        TitleCorrectionState.Request old = state.request();
        long oldGeneration = old.generation;
        state.begin(manager, "2", "20", "", "Two");
        TitleCorrectionState.Request current = state.request();
        check(current.generation > oldGeneration, "generation must increase");
        check(!state.complete(old, "Corrected one"), "late result cannot apply to new track");
        check(state.snapshot().pending, "late result cannot settle current request");
        equal(null, state.correctedTitle(manager, "2", "20"));
        check(state.complete(current, "Corrected two"), "current completion");
        state.begin(manager, "3", "10", "", "One");
        check(state.request() == null, "late result cached");
        equal("Corrected one", state.correctedTitle(manager, "3", "10"));

        state.begin(manager, "3", "30", "", "Three");
        old = state.request();
        state.begin(manager, "3", "30", "", "Three again");
        check(!state.complete(old, "Old generation"), "same identity still needs generation guard");
        equal(null, state.correctedTitle(manager, "3", "30"));

        Object otherManager = new Object();
        state.begin(manager, "4", "40", "", "Four");
        old = state.request();
        state.begin(otherManager, "4", "40", "", "Other manager");
        check(!state.complete(old, "From old manager"), "manager replacement guard");
        equal(null, state.correctedTitle(otherManager, "4", "40"));
    }

    private static void testNegativeResults() {
        Object manager = new Object();
        for (String result : new String[]{null, "", "   "}) {
            TitleCorrectionState state = started(manager, "1", "10", "Original");
            check(state.complete(state.request(), result), "empty result settles current");
            check(state.snapshot().completed, "empty result is completed");
            equal(null, state.correctedTitle(manager, "1", "10"));
            check(state.request() == null, "empty result suppresses repeated requests");
            state.begin(manager, "2", "10", "", "Original");
            check(state.request() == null, "negative result cached across tracks");
            check(state.snapshot().completed, "negative cache hit settles current");
            equal(null, state.correctedTitle(manager, "2", "10"));
        }
    }

    private static void testSameTitleCache() {
        Object manager = new Object();
        TitleCorrectionState state = started(manager, "1", "10", "Localized");
        state.complete(state.request(), " Localized ");
        equal("Localized", state.correctedTitle(manager, "1", "10"));
        CharSequence stock = new StringBuilder("Localized");
        check(state.currentTitle(manager, "1", "10", stock) == stock,
                "same visible title preserves its stock object");
        state.begin(manager, "2", "10", "", "English");
        check(state.request() == null, "same-name response must be cached");
        equal("Localized", state.correctedTitle(manager, "2", "10"));
        state.begin(manager, "3", "10", "", " Localized ");
        state.request();
        equal("Localized", state.correctedTitle(manager, "3", "10"));
    }

    private static void testLru() {
        Object manager = new Object();
        TitleCorrectionState state = new TitleCorrectionState(2);
        state.begin(manager, "1", "10", "", "Ten");
        state.complete(state.request(), "Corrected ten");
        state.begin(manager, "2", "20", "", "Twenty");
        state.complete(state.request(), null);
        state.begin(manager, "3", "10", "", "Ten");
        check(state.request() == null, "positive cache access");
        state.begin(manager, "4", "30", "", "Thirty");
        state.complete(state.request(), "Corrected thirty");
        state.begin(manager, "5", "10", "", "Ten");
        check(state.request() == null, "recent positive retained");
        state.begin(manager, "6", "20", "", "Twenty");
        check(state.request() != null, "old negative evicted");

        state = new TitleCorrectionState(2);
        state.begin(manager, "1", "10", "", "Ten");
        state.complete(state.request(), null);
        state.begin(manager, "2", "20", "", "Twenty");
        state.complete(state.request(), "Corrected twenty");
        state.begin(manager, "3", "10", "", "Ten");
        check(state.request() == null, "negative cache access updates recency");
        state.begin(manager, "4", "30", "", "Thirty");
        state.complete(state.request(), "Corrected thirty");
        state.begin(manager, "5", "20", "", "Twenty");
        check(state.request() != null, "old positive evicted after negative access");
    }

    private static void testLatestResponseWinsCache() {
        Object manager = new Object();
        TitleCorrectionState state = started(manager, "1", "10", "Original");
        TitleCorrectionState.Request old = state.request();
        state.begin(manager, "2", "10", "", "Original");
        TitleCorrectionState.Request newer = state.request();
        state.complete(newer, "New title");
        check(!state.complete(old, "Old title"), "old callback cache only");
        equal("New title", state.correctedTitle(manager, "2", "10"));
        state.begin(manager, "3", "10", "", "Original");
        check(state.request() == null, "latest response cached");
        equal("New title", state.correctedTitle(manager, "3", "10"));
    }

    private static void testPlaybackErrorReset() {
        Object manager = new Object();
        TitleCorrectionState state = started(manager, "1", "10", "Original");
        TitleCorrectionState.Request request = state.request();
        check(!state.reset(new Object()), "retired manager error ignored");
        check(state.snapshot().pending, "foreign error leaves pending state");
        check(state.reset(manager), "current playback error resets");
        check(state.snapshot().generation > request.generation, "reset increments generation");
        equal(null, state.snapshot().manager);
        equal("", state.snapshot().queueId);
        equal("", state.snapshot().catalogId);
        equal("", state.snapshot().persistentId);
        equal("", state.snapshot().originalTitle);
        check(!state.snapshot().pending && !state.snapshot().completed, "reset request status");
        check(state.request() == null, "reset cannot request");
        check(!state.complete(request, "Cached after error"), "error makes completion stale");
        equal(null, state.correctedTitle(manager, "1", "10"));
        state.begin(manager, "2", "10", "", "Original");
        check(state.request() == null, "error retains safe catalog cache");
        equal("Cached after error", state.correctedTitle(manager, "2", "10"));
    }

    private static void testSnapshotsAndStockPreservation() {
        Object manager = new Object();
        TitleCorrectionState state = started(manager, "1", "10", "Original");
        TitleCorrectionState.Snapshot before = state.snapshot();
        CharSequence stock = new StringBuilder("Original");
        check(state.currentTitle(manager, "1", "10", stock) == stock, "cache miss keeps stock object");
        state.complete(state.request(), "Corrected");
        equal(null, before.correctedTitle);
        check(!before.pending && !before.completed, "snapshot immutable");
        equal("Corrected", state.currentTitle(manager, "1", "10", stock));
        check(state.currentTitle(manager, "2", "10", stock) == stock, "wrong queue keeps stock");
        check(state.currentTitle(manager, "1", "20", stock) == stock, "wrong catalog keeps stock");
        CharSequence corrected = new StringBuilder("Corrected");
        check(state.currentTitle(manager, "1", "10", corrected) == corrected,
                "identical title keeps stock CharSequence");
        equal("Corrected", state.currentTitle(manager, "1", "10", null));
    }

    private static void testTransientFailure() {
        Object manager = new Object();
        TitleCorrectionState state = started(manager, "1", "10", "Original");
        TitleCorrectionState.Request request = state.request();
        check(state.fail(request), "active transient failure settles");
        check(!state.snapshot().pending && state.snapshot().completed, "failed request settled");
        equal(null, state.correctedTitle(manager, "1", "10"));
        check(state.request() == null, "failed generation does not loop on metadata");
        check(!state.fail(request), "duplicate failure ignored");
        check(!state.complete(request, "Too late"), "success after timeout ignored");
        state.begin(manager, "2", "10", "", "Original");
        check(state.request() != null, "transient failure not cached across plays");
    }

    private static void testStaleAndForeignFailure() {
        Object manager = new Object();
        TitleCorrectionState state = started(manager, "1", "10", "One");
        TitleCorrectionState.Request old = state.request();
        state.begin(manager, "2", "20", "", "Two");
        TitleCorrectionState.Request current = state.request();
        check(!state.fail(old), "stale failure cannot settle current");
        check(state.snapshot().pending && !state.snapshot().completed, "current remains pending");
        check(!state.complete(old, "Ignored after failure"), "stale failed token consumed");
        check(state.complete(current, "Corrected two"), "current completion after stale failure");
        state.begin(manager, "3", "10", "", "One");
        check(state.request() != null, "stale failure did not cache");

        state = started(manager, "1", "10", "One");
        old = state.request();
        state.reset(manager);
        check(!state.fail(old), "failure after reset is stale");
        state.begin(manager, "2", "10", "", "One");
        check(state.request() != null, "failure after reset not cached");
        check(!state.fail(null), "null failure ignored");
    }

    private static void testForeignTokens() {
        Object manager = new Object();
        TitleCorrectionState first = started(manager, "1", "10", "Original");
        TitleCorrectionState second = started(manager, "1", "10", "Original");
        TitleCorrectionState.Request foreign = first.request();
        check(!second.complete(foreign, "Foreign cache poison"), "foreign result rejected");
        check(!second.fail(foreign), "foreign failure rejected");
        check(second.request() != null, "foreign completion did not populate cache");
        check(first.complete(foreign, "Corrected"), "foreign state did not consume token");
        equal("Corrected", first.correctedTitle(manager, "1", "10"));
    }

    private static void testSnapshotIdentity() {
        Object manager = new Object();
        TitleCorrectionState state = started(manager, "1", "10", "Original");
        TitleCorrectionState.Snapshot snapshot = state.snapshot();
        check(state.matchesSnapshot(snapshot), "current snapshot accepted");
        state.complete(state.request(), "Corrected");
        check(state.matchesSnapshot(snapshot), "completion does not change identity");
        check(!state.matchesSnapshot(null), "null snapshot rejected");
        TitleCorrectionState other = started(manager, "1", "10", "Original");
        check(!state.matchesSnapshot(other.snapshot()), "foreign state snapshot rejected");
        state.begin(manager, "1", "10", "", "Original");
        check(!state.matchesSnapshot(snapshot), "same IDs in new generation reject old snapshot");
        snapshot = state.snapshot();
        state.begin(new Object(), "1", "10", "", "Original");
        check(!state.matchesSnapshot(snapshot), "new manager rejects old snapshot");
        snapshot = state.snapshot();
        state.reset(state.snapshot().manager);
        check(!state.matchesSnapshot(snapshot), "reset rejects old snapshot");
        check(!state.matchesSnapshot(state.snapshot()), "reset identity cannot be applied");

        state.begin(manager, "1", "", "", "Original");
        snapshot = state.snapshot();
        check(!state.matchesSnapshot(snapshot), "incomplete catalog identity fails closed");
        state.enrich(manager, "1", "10", "", "Original");
        check(!state.matchesSnapshot(snapshot), "catalog enrichment invalidates incomplete snapshot");
        check(state.matchesSnapshot(state.snapshot()), "enriched snapshot accepted");
    }

    private static void testInvalidQueueKeys() {
        Object manager = new Object();
        for (String queue : new String[]{"queue:1", "item:1", "title:Original", "-1", "0", ""}) {
            TitleCorrectionState state = started(manager, queue, "10", "Original");
            check(state.request() == null, "non-queue identity must not request: " + queue);
            check(!state.enrich(manager, "1", "10", "", "Original"),
                    "invalid queue must not adopt metadata: " + queue);
            equal(null, state.correctedTitle(manager, queue, "10"));
            check(!state.matchesSnapshot(state.snapshot()), "invalid queue snapshot rejected");
        }
    }

    private static void testResetCompletedState() {
        Object manager = new Object();
        TitleCorrectionState state = started(manager, "1", "10", "Original");
        state.complete(state.request(), "Corrected");
        TitleCorrectionState.Snapshot completed = state.snapshot();
        check(!state.reset(new Object()), "foreign reset cannot clear completed title");
        equal("Corrected", state.correctedTitle(manager, "1", "10"));
        check(state.reset(manager), "completed title reset");
        check(!state.matchesSnapshot(completed), "completed snapshot invalidated");
        equal(null, state.correctedTitle(manager, "1", "10"));
        state.begin(manager, "2", "10", "", "Original");
        check(state.request() == null, "reset preserves confirmed positive cache");
        equal("Corrected", state.correctedTitle(manager, "2", "10"));

        state.begin(manager, "3", "20", "", "No match");
        state.complete(state.request(), null);
        state.reset(manager);
        state.begin(manager, "4", "20", "", "No match");
        check(state.request() == null && state.snapshot().completed,
                "reset preserves confirmed negative cache");
    }

    private static void testInvalidCacheSize() {
        try {
            new TitleCorrectionState(0);
            throw new AssertionError("zero capacity accepted");
        } catch (IllegalArgumentException expected) {
        }
        try {
            new TitleCorrectionState(-1);
            throw new AssertionError("negative capacity accepted");
        } catch (IllegalArgumentException expected) {
        }
    }

    private static void testCacheNamespaces() {
        Object manager = new Object();
        TitleCorrectionState state = started(manager, "1", "10", "Stock");
        equal("", state.snapshot().cacheNamespace);
        check(state.setCacheNamespace("en-US"), "language namespace initialized");
        state.complete(state.request(), "English");
        equal("English", state.correctedTitle(manager, "1", "10"));
        check(state.setCacheNamespace("zh-CN"), "language namespace changed");
        equal(null, state.correctedTitle(manager, "1", "10"));
        TitleCorrectionState.Request chinese = state.request();
        check(chinese != null, "different locale must query independently");
        equal("zh-CN", chinese.cacheNamespace);
        state.complete(chinese, "Chinese");
        equal("Chinese", state.correctedTitle(manager, "1", "10"));
        state.setCacheNamespace("en-US");
        check(state.request() == null, "English cached independently");
        equal("English", state.correctedTitle(manager, "1", "10"));

        state.begin(manager, "2", "20", "", "No English match");
        state.complete(state.request(), null);
        state.setCacheNamespace("zh-CN");
        chinese = state.request();
        check(chinese != null, "negative English result cannot suppress Chinese query");
        state.complete(chinese, "Chinese match");
        state.setCacheNamespace("en-US");
        check(state.request() == null && state.snapshot().completed, "negative locale cache retained");
        equal(null, state.correctedTitle(manager, "2", "20"));
        state.setCacheNamespace("zh-CN");
        check(state.request() == null, "positive locale cache retained");
        equal("Chinese match", state.correctedTitle(manager, "2", "20"));
    }

    private static void testNamespaceLateResults() {
        Object manager = new Object();
        TitleCorrectionState state = started(manager, "1", "10", "Stock");
        state.setCacheNamespace("en-US");
        TitleCorrectionState.Request english = state.request();
        TitleCorrectionState.Snapshot englishSnapshot = state.snapshot();
        state.setCacheNamespace("zh-CN");
        check(!state.matchesSnapshot(englishSnapshot), "language change invalidates old snapshot");
        check(state.snapshot().generation > english.generation, "language change increments generation");
        equal(manager, state.snapshot().manager);
        equal("1", state.snapshot().queueId);
        equal("10", state.snapshot().catalogId);
        equal("Stock", state.snapshot().originalTitle);
        check(!state.snapshot().pending && !state.snapshot().completed, "language change resets result");
        TitleCorrectionState.Request chinese = state.request();
        check(!state.complete(english, "Late English"), "old language result cannot apply");
        check(state.snapshot().pending, "old language result cannot settle current query");
        equal(null, state.correctedTitle(manager, "1", "10"));
        state.complete(chinese, "Chinese");
        state.setCacheNamespace("en-US");
        check(state.request() == null, "late result cached in original language");
        equal("Late English", state.correctedTitle(manager, "1", "10"));
        state.setCacheNamespace("zh-CN");
        check(state.request() == null, "late result did not overwrite other language");
        equal("Chinese", state.correctedTitle(manager, "1", "10"));

        state.begin(manager, "2", "20", "persistent", "Other");
        TitleCorrectionState.Request old = state.request();
        state.setCacheNamespace("en-US");
        check(!state.fail(old), "old namespace failure cannot settle new namespace");
        equal("persistent", state.snapshot().persistentId);
        check(state.request() != null, "old namespace failure not cached in new namespace");
    }

    private static void testNamespaceRequestSuppression() {
        Object manager = new Object();
        TitleCorrectionState state = started(manager, "1", "10", "Stock");
        state.setCacheNamespace("en-US");
        TitleCorrectionState.Request english = state.request();
        long generation = english.generation;
        check(!state.setCacheNamespace(" en-US "), "same normalized namespace is a no-op");
        equal(generation, state.snapshot().generation);
        check(state.snapshot().pending, "same namespace preserves in-flight request");
        check(state.request() == null, "same namespace suppresses duplicate request");
        state.complete(english, "English");
        check(!state.setCacheNamespace("en-US"), "same completed namespace is a no-op");
        check(state.request() == null, "same completed namespace suppresses duplicate");
        equal("English", state.correctedTitle(manager, "1", "10"));
        state.setCacheNamespace(null);
        equal("", state.snapshot().cacheNamespace);
        check(state.request() != null, "default namespace remains separate");
    }

    private static void testNamespaceLruBound() {
        Object manager = new Object();
        TitleCorrectionState state = new TitleCorrectionState(2);
        state.begin(manager, "1", "10", "", "Stock");
        state.setCacheNamespace("en-US");
        state.complete(state.request(), "English");
        state.setCacheNamespace("zh-CN");
        state.complete(state.request(), "Chinese");
        state.setCacheNamespace("en-US");
        check(state.request() == null, "locale cache access updates LRU");
        state.setCacheNamespace("fr-FR");
        state.complete(state.request(), "French");
        state.setCacheNamespace("en-US");
        check(state.request() == null, "recent locale retained");
        state.setCacheNamespace("zh-CN");
        check(state.request() != null, "bounded cache evicts old locale entry");
    }

    private static void testDirectCacheAccess() {
        TitleCorrectionState state = new TitleCorrectionState();
        equal(null, state.getCachedTitle("101"));
        state.putCachedTitle(" 00101 ", "Direct Hit");
        equal("Direct Hit", state.getCachedTitle("101"));
        equal("Direct Hit", state.getCachedTitle("00101"));

        java.util.Map<String, String> batch = new java.util.HashMap<String, String>();
        batch.put("102", "Batch 102");
        batch.put("103", "Batch 103");
        state.putCachedTitles(batch);
        equal("Batch 102", state.getCachedTitle("102"));
        equal("Batch 103", state.getCachedTitle("103"));

        state.putCachedTitle("", "Ignore");
        equal(null, state.getCachedTitle(""));
        equal(null, state.getCachedTitle("invalid"));

        state.putCachedTrack("104", "Song 104", "Artist 104");
        equal("Song 104", state.getCachedTitle("104"));
        equal("Artist 104", state.getCachedArtist("104"));
    }

    private static TitleCorrectionState started(Object manager, String queueId,
                                                String catalogId, String originalTitle) {
        TitleCorrectionState state = new TitleCorrectionState();
        state.begin(manager, queueId, catalogId, "", originalTitle);
        return state;
    }

    private static void equal(Object expected, Object actual) {
        check(expected == null ? actual == null : expected.equals(actual),
                "expected <" + expected + "> but was <" + actual + ">");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
