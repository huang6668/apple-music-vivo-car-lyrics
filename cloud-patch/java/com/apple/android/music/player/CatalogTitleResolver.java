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
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Direct song-only catalog lookup using the host's authenticated MediaApi instance. */
public final class CatalogTitleResolver {
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
        if (lang.isEmpty()) throw new IllegalArgumentException("Missing catalog language");
        Map<String, String> params = new LinkedHashMap<String, String>();
        params.put("ids", id);
        params.put("l", lang);
        params.put("platform", "android");
        params.put("include[songs]", "artists");
        return new Request("songs", params);
    }

    /** Timeout/cancellation is owned by the caller; duplicate host callbacks settle once. */
    public static boolean query(Object catalog, String catalogId, String language,
                                String preferredName, Callback callback) {
        if (callback == null) return false;
        final Once once = new Once(callback);
        try {
            if (catalog == null) throw new IllegalArgumentException("Missing catalog instance");
            final Request request = songRequest(catalogId, language);
            final String id = request.parameters.get("ids");
            Method method = CatalogQueryMethod.resolve(catalog.getClass(),
                    preferredName == null ? "F" : preferredName);
            Object continuation = continuation(method.getParameterTypes()[2], id, once);
            Object result = method.invoke(catalog, request.path, request.parameters, continuation);
            if (!isSuspended(result)) complete(result, id, once);
            return true;
        } catch (Throwable error) {
            once.failure(unwrap(error));
            return false;
        }
    }

    private static Object continuation(Class<?> type, final String id, final Once once)
            throws ReflectiveOperationException {
        Method getter = type.getMethod("getContext");
        final Object context = emptyContext(getter.getReturnType());
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                new InvocationHandler() {
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        String name = method.getName();
                        if ("resumeWith".equals(name) && args != null && args.length == 1) {
                            complete(args[0], id, once);
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

    private static void complete(Object value, String id, Once once) {
        try {
            Throwable failure = failureOf(value);
            if (failure != null) once.failure(failure);
            else if (hasResponseErrors(value)) {
                once.failure(new IllegalStateException("Catalog response contains errors"));
            } else once.success(extractTitle(value, id));
        } catch (Throwable error) {
            once.failure(unwrap(error));
        }
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
        Object data = member(response, "getData", "data");
        Iterable<?> entities;
        if (data instanceof Map) {
            entities = ((Map<?, ?>) data).values();
        } else if (data instanceof Iterable) {
            entities = (Iterable<?>) data;
        } else if (data != null && data.getClass().isArray()) {
            java.util.List<Object> items = new java.util.ArrayList<Object>();
            for (int i = 0; i < Array.getLength(data); i++) items.add(Array.get(data, i));
            entities = items;
        } else {
            return null;
        }
        String title = null;
        boolean matched = false;
        for (Object entity : entities) {
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
            if (done.compareAndSet(false, true)) callback.onTitle(title);
        }
        void failure(Throwable error) {
            if (done.compareAndSet(false, true)) callback.onError(error);
        }
    }
}
