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
            this.id = id; this.type = type; attributes = new Attributes(name);
        }
        public String getId() { return id; }
        public String getType() { return type; }
        public Attributes getAttributes() { return attributes; }
    }

    public static final class Attributes {
        final String name;
        Attributes(String name) { this.name = name; }
        public String getName() { return name; }
    }

    public static final class Catalog {
        Object result = new Response(new Entity("123", "songs", "  Corrected  "));
        Object early;
        RuntimeException thrown;
        Continuation pending;
        public Object F(String path, Map<?, ?> params, Continuation continuation) {
            equal("songs", path);
            equal("123", params.get("ids"));
            equal("zh-Hans-CN", params.get("l"));
            equal("android", params.get("platform"));
            equal("artists", params.get("include[songs]"));
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

    public static void main(String[] args) throws Exception {
        testRequest();
        testSchema();
        testSyncAndSuspend();
        testErrorEnvelopes();
        testFailuresAndDuplicates();
        check(CatalogTitleResolver.emptyContext(fi.e.class) == fi.f.a,
                "obfuscated empty context singleton");
        try {
            CatalogTitleResolver.emptyContext(Runnable.class);
            throw new AssertionError("unknown context accepted");
        } catch (ClassNotFoundException expected) {
        }
        System.out.println("CatalogTitleResolver tests passed");
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
        equal("zh-Hans-CN", request.parameters.get("l"));
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
        try {
            CatalogTitleResolver.songRequest("123", "");
            throw new AssertionError("missing locale silently assumed");
        } catch (IllegalArgumentException expected) {
        }
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
