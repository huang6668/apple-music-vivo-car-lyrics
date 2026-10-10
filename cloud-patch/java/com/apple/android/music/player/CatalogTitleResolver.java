package com.apple.android.music.player;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.Executor;

/** Mainland China title lookup using the host's authenticated, request-scoped catalog path. */
public final class CatalogTitleResolver {
    public static final String CATALOG_LANGUAGE = "zh-CN";
    public static final String REQUEST_TOKEN_PARAMETER = "vivo_title_catalog_request";
    private static final String MAINLAND_PATH = "/v1/catalog/cn/songs";
    private static final long REQUEST_LIFETIME_NANOS = 30_000_000_000L;
    private static final int MAX_PENDING_REQUESTS = 64;
    private static final AtomicLong REQUEST_SEQUENCE = new AtomicLong();
    private static final Map<String, PendingRequest> PENDING =
            new LinkedHashMap<String, PendingRequest>();

    public interface Callback {
        void onTitle(String title);
        void onError(Throwable error);
    }

    public static final class Request {
        public final String path;
        public final Map<String, String> parameters;

        Request(String path, Map<String, String> parameters) {
            this.path = path;
            this.parameters = Collections.unmodifiableMap(parameters);
        }
    }

    private CatalogTitleResolver() {}

    public static Request songRequest(String catalogId, String language) {
        String id = normalizeId(catalogId);
        if (id.isEmpty()) throw new IllegalArgumentException("Invalid catalog ID");
        String lang = language == null ? "" : language.trim();
        if (!CATALOG_LANGUAGE.equals(lang) && !"zh-Hans-CN".equals(lang)) {
            throw new IllegalArgumentException("Only mainland China title requests are supported");
        }
        Map<String, String> params = new LinkedHashMap<String, String>();
        params.put("ids", id);
        params.put("l", CATALOG_LANGUAGE);
        params.put("platform", "android");
        params.put("include[songs]", "artists");
        return new Request("songs", params);
    }

    /** Timeout/cancellation is owned by the caller; duplicate host callbacks settle once. */
    public static boolean query(Object catalog, String catalogId, String language,
                                String preferredName, Callback callback) {
        return query(catalog, catalogId, language, preferredName, callback, new Executor() {
            public void execute(Runnable task) { task.run(); }
        });
    }

    public static boolean query(final Object catalog, String catalogId, String language,
                                String preferredName, Callback callback, final Executor hostExecutor) {
        if (callback == null) return false;
        final Once once = new Once(callback);
        try {
            if (catalog == null) throw new IllegalArgumentException("Missing catalog instance");
            final Request request = songRequest(catalogId, language);
            final String id = request.parameters.get("ids");
            final Method method = CatalogQueryMethod.resolve(catalog.getClass(),
                    preferredName == null ? "F" : preferredName);
            return dispatch(catalog, method, request.parameters, true, hostExecutor, new ResponseCallback() {
                public void onResponse(Object response) {
                    try {
                        if (hasResponseErrors(response) && !isNotFound(response)) {
                            throw new IllegalStateException("Catalog response contains errors");
                        }
                        String title = hasResponseErrors(response) ? null : extractTitle(response, id);
                        if (title != null) once.success(title);
                        else resolveByIsrc(catalog, method, id, once, hostExecutor);
                    } catch (Throwable error) {
                        once.failure(unwrap(error));
                    }
                }
                public void onError(Throwable error) { once.failure(error); }
            });
        } catch (Throwable error) {
            once.failure(unwrap(error));
            return false;
        }
    }

    private static void resolveByIsrc(final Object catalog, final Method method,
                                      final String id, final Once once, final Executor hostExecutor) {
        Map<String, String> identity = new LinkedHashMap<String, String>();
        identity.put("ids", id);
        identity.put("platform", "android");
        dispatch(catalog, method, identity, false, hostExecutor, new ResponseCallback() {
            public void onResponse(Object response) {
                try {
                    if (hasResponseErrors(response)) {
                        if (isNotFound(response)) once.success(null);
                        else once.failure(new IllegalStateException("Catalog identity contains errors"));
                        return;
                    }
                    final String isrc = extractIsrc(response, id);
                    if (isrc == null) {
                        once.success(null);
                        return;
                    }
                    Map<String, String> parameters = new LinkedHashMap<String, String>();
                    parameters.put("filter[isrc]", isrc);
                    parameters.put("l", CATALOG_LANGUAGE);
                    parameters.put("platform", "android");
                    parameters.put("include[songs]", "artists");
                    dispatch(catalog, method, parameters, true, hostExecutor, new ResponseCallback() {
                        public void onResponse(Object regionalResponse) {
                            try {
                                if (hasResponseErrors(regionalResponse)
                                        && !isNotFound(regionalResponse)) {
                                    throw new IllegalStateException("Catalog ISRC contains errors");
                                }
                                once.success(hasResponseErrors(regionalResponse)
                                        ? null : extractIsrcTitle(regionalResponse, isrc));
                            } catch (Throwable error) {
                                once.failure(unwrap(error));
                            }
                        }
                        public void onError(Throwable error) { once.failure(error); }
                    });
                } catch (Throwable error) {
                    once.failure(unwrap(error));
                }
            }
            public void onError(Throwable error) { once.failure(error); }
        });
    }

    private interface ResponseCallback {
        void onResponse(Object response);
        void onError(Throwable error);
    }

    private static boolean dispatch(final Object catalog, final Method method,
                                    Map<String, String> parameters, boolean mainland,
                                    Executor executor, ResponseCallback callback) {
        final RawOnce once = new RawOnce(callback);
        try {
            final Map<String, String> owned = new LinkedHashMap<String, String>(parameters);
            if (mainland) {
                String key = owned.containsKey("ids") ? "ids" : "filter[isrc]";
                once.request = new PendingRequest(key, owned.get(key),
                        System.nanoTime() + REQUEST_LIFETIME_NANOS);
                once.token = registerRequest(once.request);
                owned.put(REQUEST_TOKEN_PARAMETER, once.token);
            }
            final Object continuation = continuation(method.getParameterTypes()[2], once);
            final AtomicBoolean failedToStart = new AtomicBoolean();
            executor.execute(new Runnable() {
                public void run() {
                    try {
                        Object result = method.invoke(catalog, "songs",
                                Collections.unmodifiableMap(owned), continuation);
                        if (!isSuspended(result)) once.complete(result);
                    } catch (Throwable error) {
                        failedToStart.set(true);
                        once.failure(unwrap(error));
                    }
                }
            });
            return !failedToStart.get();
        } catch (Throwable error) {
            once.failure(unwrap(error));
            return false;
        }
    }

    /**
     * The 1607 y9.k.a entry receives fresh mutable header/query maps after Q.q0 has overwritten
     * l. Only a live resolver token plus its exact song ID authorizes the final regional rewrite.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static String correctCatalogRequest(String path, LinkedHashMap headers, Map parameters) {
        if (path == null || headers == null || parameters == null
                || !path.matches("/v1/catalog/[a-zA-Z]{2}/songs")) return path;
        Object rawToken = parameters.get(REQUEST_TOKEN_PARAMETER);
        if (!(rawToken instanceof String)) return path;
        final PendingRequest request;
        synchronized (PENDING) {
            purgeExpiredRequests(System.nanoTime());
            request = PENDING.get(rawToken);
            if (request == null || !request.selector.equals(parameters.get(request.selectorKey))
                    || parameters.containsKey("ids") != "ids".equals(request.selectorKey)
                    || parameters.containsKey("filter[isrc]")
                    != "filter[isrc]".equals(request.selectorKey)) return path;
        }
        parameters.put("l", CATALOG_LANGUAGE);
        parameters.remove(REQUEST_TOKEN_PARAMETER);
        replaceHeader(headers, "Accept-Language", "zh-Hans");
        replaceHeader(headers, "X-Apple-Store-Front",
                mainlandStorefrontHeader(header(headers, "X-Apple-Store-Front")));
        replaceHeader(headers, "X-Apple-Request-Store-Front",
                mainlandStorefrontHeader(header(headers, "X-Apple-Request-Store-Front")));
        request.localized = true;
        return MAINLAND_PATH;
    }

    private static String registerRequest(PendingRequest request) {
        synchronized (PENDING) {
            long now = System.nanoTime();
            purgeExpiredRequests(now);
            if (PENDING.size() >= MAX_PENDING_REQUESTS) {
                throw new IllegalStateException("Too many pending title requests");
            }
            String token = Long.toString(REQUEST_SEQUENCE.incrementAndGet(), 36);
            PENDING.put(token, request);
            return token;
        }
    }

    private static void purgeExpiredRequests(long now) {
        Iterator<PendingRequest> requests = PENDING.values().iterator();
        while (requests.hasNext()) {
            if (now - requests.next().deadlineNanos >= 0L) requests.remove();
        }
    }

    private static void finishRequest(String token) {
        if (token == null) return;
        synchronized (PENDING) {
            PENDING.remove(token);
        }
    }

    @SuppressWarnings("rawtypes")
    private static String header(Map headers, String name) {
        for (Object key : headers.keySet()) {
            if (key instanceof String && name.equalsIgnoreCase((String) key)) {
                Object value = headers.get(key);
                return value instanceof String ? (String) value : null;
            }
        }
        return null;
    }

    private static String mainlandStorefrontHeader(String original) {
        String value = original == null ? "" : original;
        int offset = 0;
        while (offset < value.length() && value.charAt(offset) >= '0'
                && value.charAt(offset) <= '9') offset++;
        return "143465" + value.substring(offset);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void replaceHeader(Map headers, String name, String value) {
        Iterator keys = headers.keySet().iterator();
        while (keys.hasNext()) {
            Object key = keys.next();
            if (key instanceof String && name.equalsIgnoreCase((String) key)) keys.remove();
        }
        headers.put(name, value);
    }

    private static final class PendingRequest {
        final String selectorKey;
        final String selector;
        final long deadlineNanos;
        volatile boolean localized;
        PendingRequest(String selectorKey, String selector, long deadlineNanos) {
            this.selectorKey = selectorKey;
            this.selector = selector;
            this.deadlineNanos = deadlineNanos;
        }
    }

    private static Object continuation(Class<?> type, final RawOnce once)
            throws ReflectiveOperationException {
        Method getter = type.getMethod("getContext");
        final Object context = emptyContext(getter.getReturnType());
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                new InvocationHandler() {
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        String name = method.getName();
                        if ("resumeWith".equals(name) && args != null && args.length == 1) {
                            once.complete(args[0]);
                            return null;
                        }
                        if ("getContext".equals(name) && method.getParameterTypes().length == 0) {
                            return context;
                        }
                        if ("toString".equals(name)) return "CatalogTitleContinuation";
                        if ("hashCode".equals(name)) return System.identityHashCode(proxy);
                        if ("equals".equals(name)) return proxy == (args == null ? null : args[0]);
                        throw new UnsupportedOperationException("Unknown Continuation method: " + name);
                    }
                });
    }

    /** fi.f is the concrete EmptyCoroutineContext used by the 1607 host. Never fake its algebra. */
    static Object emptyContext(Class<?> contextType) throws ReflectiveOperationException {
        for (String name : new String[]{"kotlin.coroutines.EmptyCoroutineContext", "fi.f"}) {
            final Class<?> owner;
            try {
                owner = Class.forName(name, true, contextType.getClassLoader());
            } catch (ClassNotFoundException ignored) {
                continue;
            }
            if (!contextType.isAssignableFrom(owner)) continue;
            Field singleton = null;
            for (Field field : owner.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())
                        || !contextType.isAssignableFrom(field.getType())) continue;
                if (singleton != null) throw new NoSuchFieldException("Ambiguous empty context");
                singleton = field;
            }
            if (singleton == null) continue;
            singleton.setAccessible(true);
            Object value = singleton.get(null);
            if (contextType.isInstance(value)) return value;
        }
        throw new ClassNotFoundException("Host EmptyCoroutineContext unavailable");
    }

    private static boolean isSuspended(Object value) {
        // The host obfuscates the enum class (gi.a), but preserves its enum constant name.
        return value instanceof Enum && "COROUTINE_SUSPENDED".equals(((Enum<?>) value).name());
    }

    private static boolean isNotFound(Object response) {
        List<Object> errors = values(member(response, "getErrors", "errors"));
        if (errors.isEmpty()) return false;
        for (Object error : errors) {
            Object status = member(error, "getStatus", "status");
            if (!"404".equals(String.valueOf(status))) return false;
        }
        return true;
    }

    private static boolean hasResponseErrors(Object response) {
        // Q.F returns MediaApiResponse errors for transport failures, not Result.Failure.
        Object errors = member(response, "getErrors", "errors");
        if (errors == null) return false;
        if (errors.getClass().isArray()) return Array.getLength(errors) > 0;
        if (errors instanceof Map) return !((Map<?, ?>) errors).isEmpty();
        if (errors instanceof Iterable) return ((Iterable<?>) errors).iterator().hasNext();
        return true;
    }

    private static Throwable failureOf(Object value) throws ReflectiveOperationException {
        if (value instanceof Throwable) return (Throwable) value;
        if (value == null) return null;
        String name = value.getClass().getName();
        if (!"kotlin.Result$Failure".equals(name) && !"bi.q$a".equals(name)) return null;
        Field exception = null;
        for (Field field : value.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())
                    || !Throwable.class.isAssignableFrom(field.getType())) continue;
            if (exception != null) throw new NoSuchFieldException("Ambiguous Result.Failure");
            exception = field;
        }
        if (exception == null) throw new NoSuchFieldException("Result.Failure exception unavailable");
        exception.setAccessible(true);
        Object cause = exception.get(value);
        if (!(cause instanceof Throwable)) throw new IllegalStateException("Empty Result.Failure");
        return (Throwable) cause;
    }

    /** MediaApiResponse.getData() is MediaEntity[]; accept only an exact requested song identity. */
    public static String extractTitle(Object response, String catalogId) {
        String id = normalizeId(catalogId);
        if (id.isEmpty() || response == null) return null;
        String title = null;
        boolean matched = false;
        for (Object entity : values(member(response, "getData", "data"))) {
            Object entityId = member(entity, "getId", "id");
            if (!(entityId instanceof String) || !id.equals(normalizeId((String) entityId))
                    || !isSong(entity)) continue;
            if (matched) return null;
            matched = true;
            Object attributes = member(entity, "getAttributes", "attributes");
            Object value = member(attributes, "getName", "name");
            if (value instanceof String) {
                String text = ((String) value).trim();
                if (!text.isEmpty()) title = text;
            }
        }
        return title;
    }

    private static String extractIsrc(Object response, String id) {
        String result = null;
        boolean matched = false;
        for (Object entity : values(member(response, "getData", "data"))) {
            Object entityId = member(entity, "getId", "id");
            if (!(entityId instanceof String) || !id.equals(normalizeId((String) entityId))
                    || !isSong(entity)) continue;
            if (matched) return null;
            matched = true;
            Object attributes = member(entity, "getAttributes", "attributes");
            result = normalizeIsrc(member(attributes, "getIsrc", "isrc"));
        }
        return result;
    }

    private static String extractIsrcTitle(Object response, String isrc) {
        String title = null;
        boolean matched = false;
        for (Object entity : values(member(response, "getData", "data"))) {
            Object entityId = member(entity, "getId", "id");
            if (!(entityId instanceof String) || normalizeId((String) entityId).isEmpty()
                    || !isSong(entity)) continue;
            Object attributes = member(entity, "getAttributes", "attributes");
            if (!isrc.equals(normalizeIsrc(member(attributes, "getIsrc", "isrc")))) continue;
            if (matched) return null;
            matched = true;
            Object name = member(attributes, "getName", "name");
            if (name instanceof String && !((String) name).trim().isEmpty()) {
                title = ((String) name).trim();
            }
        }
        return title;
    }

    private static String normalizeIsrc(Object candidate) {
        if (!(candidate instanceof String)) return null;
        String isrc = ((String) candidate).trim().toUpperCase(java.util.Locale.ROOT);
        return isrc.matches("[A-Z]{2}[A-Z0-9]{3}[0-9]{7}") ? isrc : null;
    }

    private static List<Object> values(Object data) {
        List<Object> items = new ArrayList<Object>();
        if (data instanceof Map) {
            items.addAll(((Map<?, ?>) data).values());
        } else if (data instanceof Iterable) {
            for (Object item : (Iterable<?>) data) items.add(item);
        } else if (data != null && data.getClass().isArray()) {
            for (int i = 0; i < Array.getLength(data); i++) items.add(Array.get(data, i));
        }
        return items;
    }

    private static boolean isSong(Object entity) {
        Object type = member(entity, "getType", "type");
        if (type != null) return "songs".equals(type);
        for (Class<?> c = entity == null ? null : entity.getClass(); c != null; c = c.getSuperclass()) {
            if ("com.apple.android.music.mediaapi.models.Song".equals(c.getName())) return true;
        }
        return false;
    }

    private static Object member(Object object, String getter, String key) {
        if (object == null) return null;
        if (object instanceof Map) return ((Map<?, ?>) object).get(key);
        try {
            Method method = object.getClass().getMethod(getter);
            if (Modifier.isStatic(method.getModifiers())) return null;
            method.setAccessible(true);
            return method.invoke(object);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private static String normalizeId(String candidate) {
        String value = candidate == null ? "" : candidate.trim();
        int first = -1;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') return "";
            if (c != '0' && first < 0) first = i;
        }
        return first < 0 ? "" : value.substring(first);
    }

    private static Throwable unwrap(Throwable error) {
        return error instanceof InvocationTargetException && error.getCause() != null
                ? error.getCause() : error;
    }

    private static final class Once {
        private final Callback callback;
        private final AtomicBoolean done = new AtomicBoolean();
        Once(Callback callback) { this.callback = callback; }
        void success(String title) {
            if (done.compareAndSet(false, true)) {
                callback.onTitle(title);
            }
        }
        void failure(Throwable error) {
            if (done.compareAndSet(false, true)) {
                callback.onError(error);
            }
        }
    }

    private static final class RawOnce {
        private final ResponseCallback callback;
        private final AtomicBoolean done = new AtomicBoolean();
        private String token;
        private PendingRequest request;
        RawOnce(ResponseCallback callback) { this.callback = callback; }
        void complete(Object response) {
            if (!done.compareAndSet(false, true)) return;
            finishRequest(token);
            try {
                Throwable failure = failureOf(response);
                if (failure != null) callback.onError(failure);
                else if (request != null && (!request.localized
                        || System.nanoTime() - request.deadlineNanos >= 0L)) {
                    callback.onError(new IllegalStateException("Mainland request was not localized"));
                } else callback.onResponse(response);
            } catch (Throwable error) {
                callback.onError(unwrap(error));
            }
        }
        void failure(Throwable error) {
            if (!done.compareAndSet(false, true)) return;
            finishRequest(token);
            callback.onError(error);
        }
    }
}
