package dev.amenhancer.compat;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;

public final class CatalogQueryMethod {
    private CatalogQueryMethod() {}

    public static Method resolve(Class<?> clazz, String preferredName)
            throws NoSuchMethodException {
        Method method = find(clazz, preferredName);
        if (method == null) {
            String className = clazz.getName();
            String renamed = null;
            if ("s8.F".equals(className)) {
                renamed = "x";
            } else if ("u8.E".equals(className)) {
                renamed = "v";
            } else if ("w9.Q".equals(className) || "w9.a".equals(className)) {
                renamed = "F";
            }
            if (renamed != null) {
                method = find(clazz, renamed);
            }
        }
        if (method == null) {
            method = findAny(clazz);
        }
        if (method == null) {
            throw new NoSuchMethodException(
                    clazz.getName() + "#" + preferredName + "(String,Map,Continuation)");
        }
        method.setAccessible(true);
        return method;
    }

    private static Method findAny(Class<?> clazz) {
        for (Class<?> current = clazz; current != null; current = current.getSuperclass()) {
            Method candidate = null;
            for (Method method : current.getDeclaredMethods()) {
                Class<?>[] types = method.getParameterTypes();
                if (Modifier.isStatic(method.getModifiers())
                        || method.getReturnType() != Object.class
                        || types.length != 3
                        || types[0] != String.class
                        || types[1] != Map.class
                        || !types[2].isInterface()
                        || !types[2].getName().equals("kotlin.coroutines.Continuation")) {
                    continue;
                }
                if (candidate != null) {
                    return null;
                }
                candidate = method;
            }
            if (candidate != null) {
                return candidate;
            }
        }
        return null;
    }

    private static Method find(Class<?> clazz, String name) throws NoSuchMethodException {
        for (Class<?> current = clazz; current != null; current = current.getSuperclass()) {
            Method candidate = null;
            for (Method method : current.getDeclaredMethods()) {
                Class<?>[] types = method.getParameterTypes();
                if (!method.getName().equals(name)
                        || Modifier.isStatic(method.getModifiers())
                        || method.getReturnType() != Object.class
                        || types.length != 3
                        || types[0] != String.class
                        || types[1] != Map.class
                        || !types[2].isInterface()
                        || !types[2].getName().equals("kotlin.coroutines.Continuation")) {
                    continue;
                }
                if (candidate != null) {
                    throw new NoSuchMethodException("Ambiguous catalog query: " + current.getName());
                }
                candidate = method;
            }
            if (candidate != null) {
                return candidate;
            }
        }
        return null;
    }
}
