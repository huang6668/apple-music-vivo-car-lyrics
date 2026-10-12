package com.apple.android.music.player;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import kotlin.coroutines.Continuation;
import kotlin.coroutines.CoroutineContext;
import kotlin.coroutines.EmptyCoroutineContext;

public final class CatalogTitleResolverTest {
    enum Suspended { COROUTINE_SUSPENDED, RESUMED }

    public static final class Response {
        private final Object[] data;
        Response(Object... entities) { data = entities; }
        public Object[] getData() { return data; }
    }

    public static final class ErrorResponse {
        private final Object[] errors;
        ErrorResponse(Object[] errors) { this.errors = errors; }
        public Object[] getErrors() { return errors; }
        public Object[] getData() {
            return new Object[]{new Entity("123", "songs", "Must not apply on error")};
        }
    }

    public static class Entity {
        final String id;
        final String type;
        final Attributes attributes;
        Entity(String id, String type, String name) {
            this(id, type, name, "");
        }
        Entity(String id, String type, String name, String artist) {
            this.id = id; this.type = type; attributes = new Attributes(name, artist);
        }
        public String getId() { return id; }
        public String getType() { return type; }
        public Attributes getAttributes() { return attributes; }
    }

    public static final class Attributes {
        final String name;
        final String artistName;
        Attributes(String name, String artist) { this.name = name; this.artistName = artist; }
        public String getName() { return name; }
        public String getArtistName() { return artistName; }
    }

    public static final class RawEntity {
        final String id;
        final Object attributes;
        RawEntity(String id, Object attributes) { this.id = id; this.attributes = attributes; }
        public String getId() { return id; }
        public String getType() { return "songs"; }
        public Object getAttributes() { return attributes; }
    }

    public static final class HookLikeAttributes {
        private String name;
        private String artistName;
        private final String isrc;
        int displayGetterCalls;
        HookLikeAttributes(String name, String artist, String isrc) {
            this.name = name;
            artistName = artist;
            this.isrc = isrc;
        }
        public String getName() {
            displayGetterCalls++;
            name = "Stale cached title";
            return name;
        }
        public String getArtistName() {
            displayGetterCalls++;
            artistName = "Stale cached artist";
            return artistName;
        }
        public String getIsrc() { return isrc; }
    }

    public static final class GetterOnlyAttributes {
        int displayGetterCalls;
        public String getName() { displayGetterCalls++; return "Unsafe cached title"; }
        public String getArtistName() { displayGetterCalls++; return "Unsafe cached artist"; }
    }

    public static final class Catalog {
        Object result = new Response(new Entity("123", "songs", "  Corrected  "));
        Object early;
        RuntimeException thrown;
        Continuation pending;
        Map<?, ?> parameters;
        boolean omitHook;
        public Object F(String path, Map<?, ?> params, Continuation continuation) {
            equal("songs", path);
            equal("123", params.get("ids"));
            equal("android", params.get("platform"));
            if (params.containsKey(CatalogTitleResolver.REQUEST_TOKEN_PARAMETER)) {
                equal("zh-CN", params.get("l"));
                check(params.get(CatalogTitleResolver.REQUEST_TOKEN_PARAMETER) instanceof String,
                        "resolver owns a request token");
                equal("artists", params.get("include[songs]"));
                if (!omitHook) {
                    LinkedHashMap<String, Object> localized = new LinkedHashMap<String, Object>();
                    for (Map.Entry<?, ?> entry : params.entrySet()) {
                        localized.put((String) entry.getKey(), entry.getValue());
                    }
                    equal("/v1/catalog/cn/songs", CatalogTitleResolver.correctCatalogRequest(
                            "/v1/catalog/us/songs", new LinkedHashMap<String, String>(), localized));
                }
            } else {
                equal(null, params.get("l"));
                equal(null, params.get("include[songs]"));
            }
            parameters = params;
            CoroutineContext context = continuation.getContext();
            check(context == EmptyCoroutineContext.INSTANCE, "use genuine empty context");
            check(context.get(new Object()) == null, "empty get returns null");
            Object initial = new Object();
            check(context.fold(initial, new Object()) == initial, "fold returns accumulator");
            check(context.minusKey(new Object()) == context, "minusKey returns self");
            check(context.plus(fi.f.a) == fi.f.a, "plus returns argument");
            check(continuation.equals(continuation), "proxy reflexive");
            check(!continuation.equals(new Object()), "proxy identity");
            equal("CatalogTitleContinuation", continuation.toString());
            pending = continuation;
            if (early != null) continuation.resumeWith(early);
            if (thrown != null) throw thrown;
            return result;
        }
    }

    static final class Recorder implements CatalogTitleResolver.Callback {
        int calls;
        String title;
        Throwable error;
        public void onTitle(String value) { calls++; title = value; }
        public void onError(Throwable value) { calls++; error = value; }
    }

    static final class TrackRecorder implements CatalogTitleResolver.TrackCallback {
        int calls;
        CatalogTitleResolver.TrackInfo track;
        Throwable error;
        public void onTrack(CatalogTitleResolver.TrackInfo value) { calls++; track = value; }
        public void onError(Throwable value) { calls++; error = value; }
    }

    static final class BatchTrackRecorder implements CatalogTitleResolver.BatchTrackCallback {
        int calls;
        Map<String, CatalogTitleResolver.TrackInfo> tracks;
        Throwable error;
        public void onTracks(Map<String, CatalogTitleResolver.TrackInfo> value) { calls++; tracks = value; }
        public void onError(Throwable value) { calls++; error = value; }
    }

    public static void main(String[] args) throws Exception {
        testRequest();
        testSchema();
        testTrackSchema();
        testCatalogMetadataBypassesDisplayGetters();
        testSyncAndSuspend();
        testTrackSyncAndSuspend();
        testErrorEnvelopes();
        testFailuresAndDuplicates();
        testFinalRequestIsolation();
        testMissingLocalizationFailsClosed();
        testIsrcFallback();
        testTrackIsrcFallback();
        testTrackLocalizationFailsClosed();
        testSharedDeadline();
        testTrackLateArtist();
        check(CatalogTitleResolver.emptyContext(fi.e.class) == fi.f.a,
                "obfuscated empty context singleton");
        try {
            CatalogTitleResolver.emptyContext(Runnable.class);
            throw new AssertionError("unknown context accepted");
        } catch (ClassNotFoundException expected) {
        }
        System.out.println("CatalogTitleResolver tests passed");
    }

    private static void testMissingLocalizationFailsClosed() {
        Catalog catalog = new Catalog();
        catalog.omitHook = true;
        Recorder result = new Recorder();
        query(catalog, result);
        equal(1, result.calls);
        equal(null, result.title);
        check(result.error instanceof IllegalStateException, "unlocalized title rejected");
    }

    public static final class ScriptedCatalog {
        final Object[] responses;
        final java.util.List<Map<?, ?>> calls = new java.util.ArrayList<Map<?, ?>>();
        Continuation pending;
        boolean suspended;
        int omitHookAt = -1;
        ScriptedCatalog(Object... responses) { this.responses = responses; }
        public Object F(String path, Map<?, ?> parameters, Continuation continuation) {
            equal("songs", path);
            int step = calls.size();
            calls.add(parameters);
            if (parameters.containsKey(CatalogTitleResolver.REQUEST_TOKEN_PARAMETER)) {
                LinkedHashMap<String, Object> fresh = new LinkedHashMap<String, Object>();
                for (Map.Entry<?, ?> entry : parameters.entrySet()) {
                    fresh.put((String) entry.getKey(), entry.getValue());
                }
                fresh.put("l", "en-US");
                if (step != omitHookAt) {
                    equal("/v1/catalog/cn/songs", CatalogTitleResolver.correctCatalogRequest(
                            "/v1/catalog/us/songs", new LinkedHashMap<String, String>(), fresh));
                    equal("zh-CN", fresh.get("l"));
                    check(!fresh.containsKey(CatalogTitleResolver.REQUEST_TOKEN_PARAMETER),
                            "internal token stays off network");
                }
            } else {
                equal("123", parameters.get("ids"));
                equal(null, parameters.get("l"));
                equal(null, parameters.get("filter[isrc]"));
            }
            pending = continuation;
            if (suspended) return Suspended.COROUTINE_SUSPENDED;
            return responses[step];
        }
    }

    private static void testIsrcFallback() {
        String isrc = "TWABC1200001";
        Object identity = map("data", Arrays.asList(map(
                "id", "123", "type", "songs",
                "attributes", map("name", "Account romanization", "isrc", isrc))));
        Object regional = map("data", Arrays.asList(map(
                "id", "987", "type", "songs",
                "attributes", map("name", "Mainland result", "isrc", isrc))));
        for (Object miss : new Object[]{
                new Response(),
                map("errors", Arrays.asList(map("status", 404)))}) {
            ScriptedCatalog catalog = new ScriptedCatalog(miss, identity, regional);
            Recorder result = new Recorder();
            check(CatalogTitleResolver.query(catalog, "123", "zh-CN", "F", result),
                    "ISRC fallback dispatch");
            equal(1, result.calls);
            equal("Mainland result", result.title);
            equal(null, result.error);
            equal(3, catalog.calls.size());
            equal(isrc, catalog.calls.get(2).get("filter[isrc]"));
            equal(null, catalog.calls.get(2).get("ids"));
            check(!catalog.calls.get(1).containsKey(CatalogTitleResolver.REQUEST_TOKEN_PARAMETER),
                    "account identity stays account scoped");
        }
        for (Object invalidRegional : new Object[]{
                new Response(),
                map("data", Arrays.asList(map("id", "987", "type", "songs",
                        "attributes", map("name", "Wrong recording", "isrc", "JPABC1200001")))),
                map("data", Arrays.asList(map("id", "i.987", "type", "songs",
                        "attributes", map("name", "Wrong identity", "isrc", isrc)))),
                map("data", Arrays.asList(map("id", "987", "type", "albums",
                        "attributes", map("name", "Wrong entity", "isrc", isrc)))),
                map("data", Arrays.asList(map("id", "987", "type", "songs",
                        "attributes", map("name", " ", "isrc", isrc)))),
                map("data", Arrays.asList(
                        map("id", "987", "type", "songs",
                                "attributes", map("name", "One", "isrc", isrc)),
                        map("id", "988", "type", "songs",
                                "attributes", map("name", "Two", "isrc", isrc))))}) {
            ScriptedCatalog catalog = new ScriptedCatalog(new Response(), identity, invalidRegional);
            Recorder result = new Recorder();
            CatalogTitleResolver.query(catalog, "123", "zh-CN", "F", result);
            equal(1, result.calls);
            equal(null, result.error);
            equal(null, result.title);
        }
        for (Object invalidIdentity : new Object[]{
                new Response(), new Response(new Entity("123", "songs", "No ISRC")),
                map("data", Arrays.asList(map("id", "555", "type", "songs",
                        "attributes", map("isrc", isrc)))),
                map("data", Arrays.asList(map("id", "123", "type", "songs",
                        "attributes", map("isrc", "invalid"))))}) {
            ScriptedCatalog catalog = new ScriptedCatalog(new Response(), invalidIdentity);
            Recorder result = new Recorder();
            CatalogTitleResolver.query(catalog, "123", "zh-CN", "F", result);
            equal(2, catalog.calls.size());
            equal(null, result.title);
            equal(null, result.error);
        }

        ScriptedCatalog failure = new ScriptedCatalog(new Response(), identity,
                map("errors", Arrays.asList(map("status", 403))));
        Recorder failed = new Recorder();
        CatalogTitleResolver.query(failure, "123", "zh-CN", "F", failed);
        equal(1, failed.calls);
        check(failed.error instanceof IllegalStateException, "regional auth error not cached as miss");

        final java.util.List<Runnable> tasks = new java.util.ArrayList<Runnable>();
        java.util.concurrent.Executor hostExecutor = new java.util.concurrent.Executor() {
            public void execute(Runnable task) { tasks.add(task); }
        };
        ScriptedCatalog async = new ScriptedCatalog();
        async.suspended = true;
        Recorder result = new Recorder();
        CatalogTitleResolver.query(async, "123", "zh-CN", "F", result, hostExecutor);
        equal(0, async.calls.size());
        tasks.remove(0).run();
        Continuation first = async.pending;
        first.resumeWith(new Response());
        first.resumeWith(regional);
        equal(1, tasks.size());
        equal(0, result.calls);
        tasks.remove(0).run();
        Continuation second = async.pending;
        second.resumeWith(identity);
        second.resumeWith(identity);
        equal(1, tasks.size());
        tasks.remove(0).run();
        async.pending.resumeWith(regional);
        async.pending.resumeWith(regional);
        equal(1, result.calls);
        equal("Mainland result", result.title);
    }

    private static void testTrackIsrcFallback() {
        String isrc = "TWABC1200001";
        Object identity = map("data", Arrays.asList(map(
                "id", "123", "type", "songs",
                "attributes", map("name", "Account title", "artistName", "Account artist",
                        "isrc", isrc))));
        Object regional = map("data", Arrays.asList(map(
                "id", "987", "type", "songs",
                "attributes", map("name", "  \u7a3b\u9999  ",
                        "artistName", "  \u5468\u6770\u502b  ", "isrc", isrc))));
        for (boolean suspended : new boolean[]{false, true}) {
            ScriptedCatalog catalog = new ScriptedCatalog(new Response(), identity, regional);
            catalog.suspended = suspended;
            TrackRecorder result = new TrackRecorder();
            check(CatalogTitleResolver.queryTrack(catalog, "123", "zh-CN", "F", result),
                    "track ISRC chain dispatch");
            if (suspended) {
                Continuation initial = catalog.pending;
                initial.resumeWith(new Response());
                initial.resumeWith(regional);
                equal(0, result.calls);
                Continuation account = catalog.pending;
                account.resumeWith(identity);
                account.resumeWith(identity);
                equal(0, result.calls);
                catalog.pending.resumeWith(regional);
                catalog.pending.resumeWith(regional);
            }
            equal(3, catalog.calls.size());
            equal(1, result.calls);
            equal(null, result.error);
            trackEquals("\u7a3b\u9999", "\u5468\u6770\u4f26", result.track);
            check(!catalog.calls.get(1).containsKey(CatalogTitleResolver.REQUEST_TOKEN_PARAMETER),
                    "identity metadata remains account scoped, never the track result");
            equal(isrc, catalog.calls.get(2).get("filter[isrc]"));
        }
        for (Object invalidRegional : new Object[]{
                map("data", Arrays.asList(map("id", "987", "type", "songs",
                        "attributes", map("name", "Wrong recording", "artistName", "Wrong artist",
                                "isrc", "JPABC1200001")))),
                map("data", Arrays.asList(
                        map("id", "987", "type", "songs",
                                "attributes", map("name", "One", "artistName", "Artist one",
                                        "isrc", isrc)),
                        map("id", "988", "type", "songs",
                                "attributes", map("name", "One", "artistName", "Artist two",
                                        "isrc", isrc))))}) {
            ScriptedCatalog catalog = new ScriptedCatalog(new Response(), identity, invalidRegional);
            TrackRecorder result = new TrackRecorder();
            CatalogTitleResolver.queryTrack(catalog, "123", "zh-CN", "F", result);
            equal(1, result.calls);
            equal(null, result.track);
            equal(null, result.error);
        }
    }

    private static void testTrackLocalizationFailsClosed() {
        Catalog catalog = new Catalog();
        catalog.result = new Response(new Entity("123", "songs", "Account title", "Account artist"));
        catalog.omitHook = true;
        TrackRecorder result = new TrackRecorder();
        queryTrack(catalog, result);
        equal(1, result.calls);
        equal(null, result.track);
        check(result.error instanceof IllegalStateException, "unlocalized track rejected");

        String isrc = "TWABC1200001";
        Object identity = map("data", Arrays.asList(map("id", "123", "type", "songs",
                "attributes", map("name", "Account title", "artistName", "Account artist",
                        "isrc", isrc))));
        Object regional = map("data", Arrays.asList(map("id", "987", "type", "songs",
                "attributes", map("name", "Regional title", "artistName", "Regional artist",
                        "isrc", isrc))));
        ScriptedCatalog fallback = new ScriptedCatalog(new Response(), identity, regional);
        fallback.omitHookAt = 2;
        result = new TrackRecorder();
        CatalogTitleResolver.queryTrack(fallback, "123", "zh-CN", "F", result);
        equal(3, fallback.calls.size());
        equal(1, result.calls);
        equal(null, result.track);
        check(result.error instanceof IllegalStateException, "unlocalized ISRC artist rejected");
    }

    private static void testTrackLateArtist() throws Exception {
        String isrc = "TWABC1200001";
        Object identity = map("data", Arrays.asList(map("id", "123", "type", "songs",
                "attributes", map("name", "Account title", "artistName", "Account artist",
                        "isrc", isrc))));
        Object regional = map("data", Arrays.asList(map("id", "987", "type", "songs",
                "attributes", map("name", "Late title", "artistName", "Late artist", "isrc", isrc))));
        for (int stage = 0; stage < 3; stage++) {
            ScriptedCatalog catalog = new ScriptedCatalog();
            catalog.suspended = true;
            TrackRecorder result = new TrackRecorder();
            CatalogTitleResolver.queryTrack(catalog, "123", "zh-CN", "F", result);
            Object chain = chainFor(catalog.calls.get(0));
            if (stage > 0) catalog.pending.resumeWith(new Response());
            if (stage > 1) catalog.pending.resumeWith(identity);
            expire(chain);
            catalog.pending.resumeWith(stage == 0 ? regional : stage == 1 ? identity : regional);
            catalog.pending.resumeWith(regional);
            equal(stage + 1, catalog.calls.size());
            equal(1, result.calls);
            equal(null, result.track);
            check(result.error instanceof IllegalStateException, "late artist cannot settle expired chain");
        }
    }

    @SuppressWarnings("rawtypes")
    private static void testSharedDeadline() throws Exception {
        String isrc = "TWABC1200001";
        Object identity = map("data", Arrays.asList(map("id", "123", "type", "songs",
                "attributes", map("name", "Account title", "isrc", isrc))));
        Object regional = map("data", Arrays.asList(map("id", "987", "type", "songs",
                "attributes", map("name", "Mainland title", "isrc", isrc))));
        for (int stage = 0; stage < 6; stage++) {
            final java.util.List<Runnable> tasks = new java.util.ArrayList<Runnable>();
            java.util.concurrent.Executor executor = new java.util.concurrent.Executor() {
                public void execute(Runnable task) { tasks.add(task); }
            };
            ScriptedCatalog catalog = new ScriptedCatalog();
            catalog.suspended = true;
            Recorder result = new Recorder();
            check(CatalogTitleResolver.query(catalog, "123", "zh-CN", "F", result, executor),
                    "queued chain accepted");
            Object latest = null;
            for (Object request : pendingRequests().values()) latest = request;
            check(latest != null, "queued request registered");
            java.lang.reflect.Field chainField = latest.getClass().getDeclaredField("chain");
            chainField.setAccessible(true);
            Object chain = chainField.get(latest);
            if (stage == 0) {
                expire(chain);
                tasks.remove(0).run();
            } else {
                tasks.remove(0).run();
                if (stage == 1) {
                    expire(chain);
                    catalog.pending.resumeWith(new Response());
                } else {
                    catalog.pending.resumeWith(new Response());
                    if (stage == 2) {
                        expire(chain);
                        tasks.remove(0).run();
                    } else {
                        tasks.remove(0).run();
                        if (stage == 3) {
                            expire(chain);
                            catalog.pending.resumeWith(identity);
                        } else {
                            catalog.pending.resumeWith(identity);
                            Object regionalPending = null;
                            for (Object request : pendingRequests().values()) regionalPending = request;
                            check(chainField.get(regionalPending) == chain,
                                    "regional fallback keeps the initial deadline");
                            if (stage == 4) {
                                expire(chain);
                                tasks.remove(0).run();
                            } else {
                                tasks.remove(0).run();
                                expire(chain);
                                catalog.pending.resumeWith(regional);
                            }
                        }
                    }
                }
            }
            equal(stage == 0 ? 0 : stage < 3 ? 1 : stage < 5 ? 2 : 3, catalog.calls.size());
            equal(0, tasks.size());
            equal(1, result.calls);
            equal(null, result.title);
            check(result.error instanceof IllegalStateException, "shared deadline settles as failure");
            for (Object request : pendingRequests().values()) {
                check(chainField.get(request) != chain, "expired chain tokens promptly removed");
            }
            if (catalog.pending != null) {
                catalog.pending.resumeWith(identity);
                catalog.pending.resumeWith(regional);
            }
            equal(1, result.calls);
            equal(0, tasks.size());
        }
    }

    private static void testErrorEnvelopes() {
        for (boolean suspended : new boolean[]{false, true}) {
            Catalog catalog = new Catalog();
            ErrorResponse error = new ErrorResponse(new Object[]{new Object()});
            catalog.result = suspended ? Suspended.COROUTINE_SUSPENDED : error;
            Recorder result = new Recorder();
            check(query(catalog, result), "error envelope dispatch");
            if (suspended) {
                equal(0, result.calls);
                catalog.pending.resumeWith(error);
            }
            equal(1, result.calls);
            check(result.error instanceof IllegalStateException, "error envelope fails query");
            equal(null, result.title);
            catalog.pending.resumeWith(new Response(new Entity("123", "songs", "Late")));
            equal(1, result.calls);
        }
        for (Object[] errors : new Object[][]{null, new Object[0]}) {
            Catalog catalog = new Catalog();
            catalog.result = new ErrorResponse(errors);
            Recorder result = new Recorder();
            query(catalog, result);
            equal(1, result.calls);
            equal(null, result.error);
            equal("Must not apply on error", result.title);
        }
    }

    private static void testRequest() {
        CatalogTitleResolver.Request request = CatalogTitleResolver.songRequest("00123", "zh-Hans-CN");
        equal("songs", request.path);
        equal("123", request.parameters.get("ids"));
        equal("zh-CN", request.parameters.get("l"));
        equal("zh-CN", CatalogTitleResolver.songRequest("123", "zh-CN").parameters.get("l"));
        try {
            request.parameters.put("ids", "999");
            throw new AssertionError("mutable request");
        } catch (UnsupportedOperationException expected) {
        }
        for (String id : new String[]{null, "", "0", "-1", "i.123", "123,456"}) {
            try {
                CatalogTitleResolver.songRequest(id, "zh-Hans-CN");
                throw new AssertionError("invalid ID accepted");
            } catch (IllegalArgumentException expected) {
            }
        }
        for (String language : new String[]{null, "", "en-US", "ja-JP", "zh-TW"}) {
            try {
                CatalogTitleResolver.songRequest("123", language);
                throw new AssertionError("non-mainland locale accepted");
            } catch (IllegalArgumentException expected) {
            }
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void testFinalRequestIsolation() throws Exception {
        Catalog catalog = new Catalog();
        catalog.result = Suspended.COROUTINE_SUSPENDED;
        Recorder result = new Recorder();
        check(query(catalog, result), "register mainland request");
        LinkedHashMap parameters = new LinkedHashMap(catalog.parameters);
        parameters.put("l", "en-US");
        LinkedHashMap headers = new LinkedHashMap(map(
                "Authorization", "Bearer account-token",
                "User-Agent", "AppleMusic/1607",
                "X-Dsid", "321",
                "accept-language", "en",
                "x-apple-store-front", "143441-1,29",
                "X-Apple-Request-Store-Front", "143462-1,26"));
        String path = CatalogTitleResolver.correctCatalogRequest(
                "/v1/catalog/us/songs", headers, parameters);
        equal("/v1/catalog/cn/songs", path);
        equal("zh-CN", parameters.get("l"));
        equal(null, parameters.get(CatalogTitleResolver.REQUEST_TOKEN_PARAMETER));
        equal("zh-Hans", headers.get("Accept-Language"));
        equal("143465-1,29", headers.get("X-Apple-Store-Front"));
        equal("143465-1,26", headers.get("X-Apple-Request-Store-Front"));
        equal(null, headers.get("accept-language"));
        equal(null, headers.get("x-apple-store-front"));
        equal("Bearer account-token", headers.get("Authorization"));
        equal("AppleMusic/1607", headers.get("User-Agent"));
        equal("321", headers.get("X-Dsid"));
        equal("zh-CN", catalog.parameters.get("l"));
        check(catalog.parameters.containsKey(CatalogTitleResolver.REQUEST_TOKEN_PARAMETER),
                "do not mutate resolver input reused by host retries");

        LinkedHashMap ordinary = new LinkedHashMap(map("ids", "123", "l", "en-US"));
        LinkedHashMap originalHeaders = new LinkedHashMap(map("Accept-Language", "en"));
        LinkedHashMap expected = new LinkedHashMap(ordinary);
        equal("/v1/catalog/us/songs", CatalogTitleResolver.correctCatalogRequest(
                "/v1/catalog/us/songs", originalHeaders, ordinary));
        equal(expected, ordinary);
        equal(map("Accept-Language", "en"), originalHeaders);

        for (String unsafePath : new String[]{null, "songs", "/v1/catalog/us/albums",
                "/v1/catalog/us/songs/123", "/v1/me/songs", "/v1/catalog/usa/songs",
                "https://example.com/v1/catalog/us/songs"}) {
            Catalog protectedCatalog = suspendedCatalog();
            LinkedHashMap protectedParameters = new LinkedHashMap(protectedCatalog.parameters);
            LinkedHashMap protectedHeaders = new LinkedHashMap(originalHeaders);
            rejectRequest(unsafePath, protectedHeaders, protectedParameters);
            LinkedHashMap withoutToken = new LinkedHashMap(protectedCatalog.parameters);
            withoutToken.remove(CatalogTitleResolver.REQUEST_TOKEN_PARAMETER);
            equal(withoutToken, protectedParameters);
            equal(originalHeaders, protectedHeaders);
            protectedCatalog.pending.resumeWith(new Response());
        }

        LinkedHashMap spoof = new LinkedHashMap(catalog.parameters);
        spoof.put(CatalogTitleResolver.REQUEST_TOKEN_PARAMETER, "unknown-token");
        LinkedHashMap spoofOriginal = new LinkedHashMap(spoof);
        rejectRequest("/v1/catalog/us/songs", originalHeaders, spoof);
        spoofOriginal.remove(CatalogTitleResolver.REQUEST_TOKEN_PARAMETER);
        equal(spoofOriginal, spoof);
        Catalog wrongIdentity = suspendedCatalog();
        spoof = new LinkedHashMap(wrongIdentity.parameters);
        spoof.put("ids", "456");
        spoofOriginal = new LinkedHashMap(spoof);
        rejectRequest("/v1/catalog/us/songs", originalHeaders, spoof);
        spoofOriginal.remove(CatalogTitleResolver.REQUEST_TOKEN_PARAMETER);
        equal(spoofOriginal, spoof);
        wrongIdentity.pending.resumeWith(new Response());
        for (Object invalidToken : new Object[]{null, Integer.valueOf(3)}) {
            spoof = new LinkedHashMap(map("ids", "123", "l", "en-US",
                    CatalogTitleResolver.REQUEST_TOKEN_PARAMETER, invalidToken));
            rejectRequest("/v1/catalog/us/songs", originalHeaders, spoof);
            equal(map("ids", "123", "l", "en-US"), spoof);
        }

        LinkedHashMap withoutHeaders = new LinkedHashMap();
        parameters = new LinkedHashMap(catalog.parameters);
        equal("/v1/catalog/cn/songs", CatalogTitleResolver.correctCatalogRequest(
                "/v1/catalog/jp/songs", withoutHeaders, parameters));
        equal("143465", withoutHeaders.get("X-Apple-Store-Front"));
        equal("143465", withoutHeaders.get("X-Apple-Request-Store-Front"));

        catalog.pending.resumeWith(new Response(new Entity("123", "songs", "Done")));
        parameters = new LinkedHashMap(catalog.parameters);
        rejectRequest("/v1/catalog/us/songs", originalHeaders, parameters);
        check(!parameters.containsKey(CatalogTitleResolver.REQUEST_TOKEN_PARAMETER),
                "completed token removed before network");

        Catalog expired = suspendedCatalog();
        expire(chainFor(expired.parameters));
        parameters = new LinkedHashMap(expired.parameters);
        rejectRequest("/v1/catalog/us/songs", originalHeaders, parameters);
        check(!parameters.containsKey(CatalogTitleResolver.REQUEST_TOKEN_PARAMETER),
                "expired token removed before network");
        expired.pending.resumeWith(new Response());
    }

    private static Catalog suspendedCatalog() {
        Catalog catalog = new Catalog();
        catalog.result = Suspended.COROUTINE_SUSPENDED;
        check(query(catalog, new Recorder()), "suspended fixture");
        return catalog;
    }

    @SuppressWarnings("rawtypes")
    private static void rejectRequest(String path, LinkedHashMap headers, Map parameters) {
        try {
            CatalogTitleResolver.correctCatalogRequest(path, headers, parameters);
            throw new AssertionError("unverified internal request permitted on network");
        } catch (IllegalStateException expected) {
            check(!parameters.containsKey(CatalogTitleResolver.REQUEST_TOKEN_PARAMETER),
                    "rejected internal token removed");
        }
    }

    @SuppressWarnings("rawtypes")
    private static Map pendingRequests() throws Exception {
        java.lang.reflect.Field field = CatalogTitleResolver.class.getDeclaredField("PENDING");
        field.setAccessible(true);
        return (Map) field.get(null);
    }

    private static Object chainFor(Map<?, ?> parameters) throws Exception {
        Object request = pendingRequests().get(
                parameters.get(CatalogTitleResolver.REQUEST_TOKEN_PARAMETER));
        check(request != null, "pending request exists");
        java.lang.reflect.Field field = request.getClass().getDeclaredField("chain");
        field.setAccessible(true);
        return field.get(request);
    }

    private static void expire(Object chain) throws Exception {
        java.lang.reflect.Field field = chain.getClass().getDeclaredField("deadlineNanos");
        field.setAccessible(true);
        field.setLong(chain, System.nanoTime() - 1L);
    }

    private static void testSchema() {
        Entity wanted = new Entity("00123", "songs", "  Corrected  ");
        equal("Corrected", parse(new Response(
                new Entity("555", "songs", "Wrong"), new Entity("123", "albums", "Album"), wanted)));
        equal(null, parse(new Response(new Entity("123", "albums", "Album"))));
        equal(null, parse(new Response(new Entity("555", "songs", "Wrong"))));
        equal(null, parse(new Response(new Entity(null, "songs", "No ID"))));
        equal(null, parse(new Response(new Entity("123", null, "Unknown type"))));
        equal("Typed song", parse(new Response(
                new com.apple.android.music.mediaapi.models.Song())));
        equal(null, parse(new Response(wanted, wanted)));
        equal(null, parse(new Response(new Entity("123", "songs", "  "))));
        equal(null, parse(new Response(new Entity("123", "songs", null))));
        equal(null, parse(new Response()));
        equal(null, parse(null));
        equal(null, CatalogTitleResolver.extractTitle(new Response(wanted), ""));
        Map<String, Object> attributes = map("name", "Mapped");
        Map<String, Object> entity = map("id", "123", "type", "songs", "attributes", attributes);
        equal("Mapped", parse(map("data", Arrays.asList(entity))));
        equal("Mapped", parse(map("data", map("123", entity))));
        equal(null, parse(entity));
        equal(null, parse(map("data", map("name", "Unstructured"))));
        entity.remove("id");
        equal(null, parse(map("data", Arrays.asList(entity))));
        entity.put("id", "123");
        entity.remove("type");
        equal(null, parse(map("data", Arrays.asList(entity))));
        entity.put("type", "songs");
        attributes.put("name", new Object());
        equal(null, parse(map("data", Arrays.asList(entity))));
    }

    private static void testTrackSchema() {
        Entity wanted = new Entity("00123", "songs", "  \u7a3b\u9999  ", "  \u5468\u6770\u502b  ");
        CatalogTitleResolver.TrackInfo track = CatalogTitleResolver.extractTrack(new Response(
                new Entity("555", "songs", "Wrong title", "Wrong artist"),
                new Entity("123", "albums", "Album", "Album artist"), wanted), "123");
        trackEquals("\u7a3b\u9999", "\u5468\u6770\u4f26", track);
        equal(null, CatalogTitleResolver.extractTrack(new Response(wanted, wanted), "123"));
        equal(null, CatalogTitleResolver.extractTrack(new Response(
                new Entity("123", "songs", " ", "Artist only")), "123"));
        equal(null, CatalogTitleResolver.extractTrack(new Response(
                new Entity("555", "songs", "Wrong title", "Wrong artist")), "123"));
        equal(null, CatalogTitleResolver.extractTrack(new Response(wanted), "i.123"));
        equal(null, CatalogTitleResolver.extractTrack(null, "123"));
        trackEquals("Without artist", "", CatalogTitleResolver.extractTrack(new Response(
                new Entity("123", "songs", "Without artist", null)), "123"));
        Object mapped = map("data", Arrays.asList(map("id", "123", "type", "songs",
                "attributes", map("name", "Mapped title", "artistName", "Mapped artist"))));
        trackEquals("Mapped title", "Mapped artist", CatalogTitleResolver.extractTrack(mapped, "123"));
        Object invalidArtist = map("data", Arrays.asList(map("id", "123", "type", "songs",
                "attributes", map("name", "Mapped title", "artistName", new Object()))));
        trackEquals("Mapped title", "", CatalogTitleResolver.extractTrack(invalidArtist, "123"));
    }

    private static void testCatalogMetadataBypassesDisplayGetters() {
        String isrc = "TWABC1200001";
        HookLikeAttributes direct = new HookLikeAttributes(
                "\u7a3b\u9999", "\u5468\u6770\u502b", isrc);
        Catalog catalog = new Catalog();
        catalog.result = new Response(new RawEntity("123", direct));
        TrackRecorder result = new TrackRecorder();
        check(queryTrack(catalog, result), "raw single-track metadata dispatch");
        equal(1, result.calls);
        equal(null, result.error);
        trackEquals("\u7a3b\u9999", "\u5468\u6770\u4f26", result.track);
        equal(0, direct.displayGetterCalls);
        equal("\u7a3b\u9999", direct.name);
        equal("\u5468\u6770\u502b", direct.artistName);

        BatchTrackRecorder batch = new BatchTrackRecorder();
        check(CatalogTitleResolver.batchQueryTracks(catalog, Arrays.asList("123"),
                "zh-CN", "F", batch), "raw batch metadata dispatch");
        equal(1, batch.calls);
        equal(null, batch.error);
        trackEquals("\u7a3b\u9999", "\u5468\u6770\u4f26", batch.tracks.get("123"));
        equal(0, direct.displayGetterCalls);

        Object identity = map("data", Arrays.asList(map("id", "123", "type", "songs",
                "attributes", map("name", "Account title", "artistName", "Account artist",
                        "isrc", isrc))));
        HookLikeAttributes regional = new HookLikeAttributes(
                "\u7a3b\u9999", "\u5468\u6770\u502b", isrc);
        ScriptedCatalog fallback = new ScriptedCatalog(new Response(), identity,
                new Response(new RawEntity("987", regional)));
        result = new TrackRecorder();
        check(CatalogTitleResolver.queryTrack(fallback, "123", "zh-CN", "F", result),
                "raw ISRC metadata dispatch");
        equal(3, fallback.calls.size());
        equal(1, result.calls);
        equal(null, result.error);
        trackEquals("\u7a3b\u9999", "\u5468\u6770\u4f26", result.track);
        equal(0, regional.displayGetterCalls);
        equal("\u7a3b\u9999", regional.name);
        equal("\u5468\u6770\u502b", regional.artistName);

        GetterOnlyAttributes unavailable = new GetterOnlyAttributes();
        Object unsafe = new Response(new RawEntity("123", unavailable));
        equal(null, CatalogTitleResolver.extractTrack(unsafe, "123"));
        equal(0, CatalogTitleResolver.extractTracks(unsafe).size());
        equal(0, unavailable.displayGetterCalls);
    }

    private static void testSyncAndSuspend() {
        Catalog catalog = new Catalog();
        Recorder result = new Recorder();
        check(query(catalog, result), "synchronous dispatch");
        equal(1, result.calls);
        equal("Corrected", result.title);
        catalog.pending.resumeWith(new Response(new Entity("123", "songs", "Late")));
        equal(1, result.calls);
        catalog = new Catalog();
        catalog.result = Suspended.COROUTINE_SUSPENDED;
        result = new Recorder();
        check(query(catalog, result), "suspended dispatch");
        equal(0, result.calls);
        catalog.pending.resumeWith(new Response(new Entity("123", "songs", "Resumed")));
        catalog.pending.resumeWith(new Response(new Entity("123", "songs", "Duplicate")));
        equal(1, result.calls);
        equal("Resumed", result.title);
        catalog = new Catalog();
        catalog.result = null;
        result = new Recorder();
        check(query(catalog, result), "empty sync result");
        equal(1, result.calls);
        equal(null, result.title);
    }

    private static void testTrackSyncAndSuspend() {
        Catalog catalog = new Catalog();
        catalog.result = new Response(new Entity("123", "songs", "Sync title", "Sync artist"));
        TrackRecorder result = new TrackRecorder();
        check(queryTrack(catalog, result), "synchronous track dispatch");
        equal(1, result.calls);
        equal(null, result.error);
        trackEquals("Sync title", "Sync artist", result.track);
        catalog.pending.resumeWith(new Response(new Entity("123", "songs", "Late title", "Late artist")));
        equal(1, result.calls);
        trackEquals("Sync title", "Sync artist", result.track);

        catalog = new Catalog();
        catalog.result = Suspended.COROUTINE_SUSPENDED;
        result = new TrackRecorder();
        check(queryTrack(catalog, result), "suspended track dispatch");
        equal(0, result.calls);
        catalog.pending.resumeWith(new Response(new Entity("123", "songs", "Resumed title", "Resumed artist")));
        catalog.pending.resumeWith(new Response(new Entity("123", "songs", "Duplicate", "Wrong artist")));
        equal(1, result.calls);
        trackEquals("Resumed title", "Resumed artist", result.track);

        RuntimeException failure = new RuntimeException("host track failed");
        for (Object value : new Object[]{failure, new kotlin.Result.Failure(failure), new bi.q.a(failure)}) {
            catalog = new Catalog();
            catalog.result = Suspended.COROUTINE_SUSPENDED;
            result = new TrackRecorder();
            queryTrack(catalog, result);
            catalog.pending.resumeWith(value);
            catalog.pending.resumeWith(new Response(new Entity("123", "songs", "Late title", "Late artist")));
            equal(1, result.calls);
            equal(null, result.track);
            check(result.error == failure, "track failure rejects later artist");
        }
        catalog = new Catalog();
        catalog.early = new Response(new Entity("123", "songs", "Early title", "Early artist"));
        catalog.thrown = failure;
        result = new TrackRecorder();
        queryTrack(catalog, result);
        equal(1, result.calls);
        trackEquals("Early title", "Early artist", result.track);
        check(!CatalogTitleResolver.queryTrack(new Object(), "123", "zh-CN", "F", null),
                "null track callback declines dispatch");
    }

    private static void testFailuresAndDuplicates() {
        RuntimeException failure = new RuntimeException("host failed");
        for (Object value : new Object[]{failure, new kotlin.Result.Failure(failure), new bi.q.a(failure)}) {
            Catalog catalog = new Catalog();
            catalog.result = value;
            Recorder result = new Recorder();
            query(catalog, result);
            equal(1, result.calls);
            check(result.error == failure, "synchronous failure unwrapped");
            catalog = new Catalog();
            catalog.result = Suspended.COROUTINE_SUSPENDED;
            result = new Recorder();
            query(catalog, result);
            catalog.pending.resumeWith(value);
            catalog.pending.resumeWith(catalog.result);
            equal(1, result.calls);
            check(result.error == failure, "resume failure unwrapped");
        }
        Catalog catalog = new Catalog();
        catalog.thrown = failure;
        Recorder result = new Recorder();
        check(!query(catalog, result), "invocation exception returns false");
        equal(1, result.calls);
        check(result.error == failure, "InvocationTargetException unwrapped");
        catalog = new Catalog();
        catalog.early = new Response(new Entity("123", "songs", "Early"));
        catalog.thrown = failure;
        result = new Recorder();
        query(catalog, result);
        equal(1, result.calls);
        equal("Early", result.title);
        result = new Recorder();
        check(!CatalogTitleResolver.query(null, "123", "zh-Hans-CN", "F", result),
                "missing instance fails");
        equal(1, result.calls);
        result = new Recorder();
        check(!CatalogTitleResolver.query(new Object(), "123", "zh-Hans-CN", "F", result),
                "missing method fails");
        equal(1, result.calls);
        final Recorder raced = new Recorder();
        final Catalog suspended = new Catalog();
        suspended.result = Suspended.COROUTINE_SUSPENDED;
        query(suspended, raced);
        Runnable delivery = new Runnable() {
            public void run() {
                suspended.pending.resumeWith(new Response(new Entity("123", "songs", "Concurrent")));
            }
        };
        Thread one = new Thread(delivery);
        Thread two = new Thread(delivery);
        one.start();
        two.start();
        try {
            one.join();
            two.join();
        } catch (InterruptedException error) {
            throw new AssertionError(error);
        }
        equal(1, raced.calls);
        equal("Concurrent", raced.title);
    }

    private static boolean query(Catalog catalog, Recorder result) {
        return CatalogTitleResolver.query(catalog, "123", "zh-Hans-CN", "F", result);
    }
    private static boolean queryTrack(Catalog catalog, TrackRecorder result) {
        return CatalogTitleResolver.queryTrack(catalog, "123", "zh-Hans-CN", "F", result);
    }
    private static void trackEquals(String title, String artist, CatalogTitleResolver.TrackInfo track) {
        check(track != null, "track result exists");
        equal(title, track.title);
        equal(artist, track.artist);
    }
    private static String parse(Object response) {
        return CatalogTitleResolver.extractTitle(response, "123");
    }
    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static void equal(Object expected, Object actual) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError("expected <" + expected + "> but was <" + actual + ">");
        }
    }
}
