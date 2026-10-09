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
        testSnapshotsAndStockPreservation();
        testInvalidCacheSize();
        System.out.println("TitleCorrectionState tests passed");
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
        equal(null, state.correctedTitle(manager, "1", "10"));
        state.begin(manager, "2", "10", "", "English");
        check(state.request() == null, "same-name response must be cached");
        equal("Localized", state.correctedTitle(manager, "2", "10"));
        state.begin(manager, "3", "10", "", " Localized ");
        state.request();
        equal(null, state.correctedTitle(manager, "3", "10"));
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
