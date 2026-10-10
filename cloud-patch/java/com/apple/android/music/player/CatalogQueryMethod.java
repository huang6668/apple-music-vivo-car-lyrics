package com.apple.android.music.player;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Resolves Apple's obfuscated direct catalog query without depending on AM++. */
public final class CatalogQueryMethod {
    private CatalogQueryMethod() {}

    public static Method resolve(Class<?> clazz, String preferredName)
            throws NoSuchMethodException {
        Method method = find(clazz, preferredName);
        if (method == null) {
            String name = clazz.getName();
            String renamed = "s8.F".equals(name) ? "x"
                    : "u8.E".equals(name) ? "v"
                    : ("w9.Q".equals(name) || "w9.a".equals(name)) ? "F" : null;
            if (renamed != null) method = find(clazz, renamed);
        }
        if (method == null) method = findUnique(clazz);
        if (method == null) {
            throw new NoSuchMethodException(clazz.getName() + "#" + preferredName
                    + "(String,Map,Continuation)");
        }
        method.setAccessible(true);
        return method;
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
