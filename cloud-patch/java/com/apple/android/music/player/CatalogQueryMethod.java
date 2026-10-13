package com.apple.android.music.player;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Resolves Apple's obfuscated direct catalog query, independent of any hook framework. */
public final class CatalogQueryMethod {
    private static final Object MISSING = new Object();
    private static final ConcurrentHashMap<Class<?>, ConcurrentHashMap<String, Object>> RESOLVED =
            new ConcurrentHashMap<Class<?>, ConcurrentHashMap<String, Object>>();

    private CatalogQueryMethod() {}

    /** Hits and misses are cached per (class, preferredName); a cached miss still throws. */
    public static Method resolve(Class<?> clazz, String preferredName)
            throws NoSuchMethodException {
        String key = String.valueOf(preferredName);
        ConcurrentHashMap<String, Object> byName = RESOLVED.get(clazz);
        if (byName == null) {
            RESOLVED.putIfAbsent(clazz, new ConcurrentHashMap<String, Object>());
            byName = RESOLVED.get(clazz);
        }
        Object cached = byName.get(key);
        if (cached instanceof Method) return (Method) cached;
        if (cached == MISSING) throw missing(clazz, preferredName);
        Method method;
        try {
            method = lookup(clazz, preferredName);
        } catch (NoSuchMethodException ambiguous) {
            byName.put(key, MISSING);
            throw ambiguous;
        }
        if (method == null) {
            byName.put(key, MISSING);
            throw missing(clazz, preferredName);
        }
        method.setAccessible(true);
        byName.put(key, method);
        return method;
    }

    private static NoSuchMethodException missing(Class<?> clazz, String preferredName) {
        return new NoSuchMethodException(clazz.getName() + "#" + preferredName
                + "(String,Map,Continuation)");
    }

    private static Method lookup(Class<?> clazz, String preferredName)
            throws NoSuchMethodException {
        Method method = find(clazz, preferredName);
        if (method == null) {
            String name = clazz.getName();
            String renamed = "s8.F".equals(name) ? "x"
                    : "u8.E".equals(name) ? "v"
                    : ("w9.Q".equals(name) || "w9.a".equals(name)) ? "F" : null;
            if (renamed != null) method = find(clazz, renamed);
        }
        return method == null ? findUnique(clazz) : method;
    }

    private static boolean compatible(Method method) {
        Class<?>[] p = method.getParameterTypes();
        return !Modifier.isStatic(method.getModifiers())
                && method.getReturnType() == Object.class
                && p.length == 3 && p[0] == String.class && p[1] == Map.class
                && p[2].isInterface()
                && "kotlin.coroutines.Continuation".equals(p[2].getName());
    }

    private static Method find(Class<?> clazz, String name) throws NoSuchMethodException {
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            Method result = null;
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals(name) || !compatible(m)) continue;
                if (result != null) throw new NoSuchMethodException("Ambiguous catalog query: " + c.getName());
                result = m;
            }
            if (result != null) return result;
        }
        return null;
    }

    private static Method findUnique(Class<?> clazz) {
        Method result = null;
        Set<String> overridden = new HashSet<String>();
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (!compatible(m) || !overridden.add(m.getName())) continue;
                if (result != null) return null;
                result = m;
            }
        }
        return result;
    }
}
