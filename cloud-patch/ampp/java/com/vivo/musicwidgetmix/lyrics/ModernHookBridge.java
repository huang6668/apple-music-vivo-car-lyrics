package com.vivo.musicwidgetmix.lyrics;

import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

public final class ModernHookBridge {
    private static final String TAG = "AppleMusicLyrics";
    private static final Set<Object> ADAPTERS = new HashSet<Object>();

    private ModernHookBridge() {
    }

    public static boolean hook(Method method, MethodHook callback) {
        try {
            Object runtime = runtimeInstance();
            Method hookMethod = runtime.getClass().getMethod(
                    "hookMethod", java.lang.reflect.Executable.class,
                    Class.forName("dev.amenhancer.module.hook.ModernMethodHook"));
            Object adapter = adapter(callback);
            return ((Boolean) hookMethod.invoke(runtime, method, adapter)).booleanValue();
        } catch (Throwable error) {
            Log.e(TAG, "Modern Xposed bridge failed", error);
            return false;
        }
    }

    private static Object runtimeInstance() throws Exception {
        Class<?> type = Class.forName("dev.amenhancer.module.hook.ModernXposedRuntime");
        Field instance = type.getDeclaredField("INSTANCE");
        instance.setAccessible(true);
        return instance.get(null);
    }

    private static Object adapter(MethodHook callback) throws Exception {
        synchronized (ADAPTERS) {
            for (Object item : ADAPTERS) {
                if (callback(item) == callback) {
                    return item;
                }
            }
            Class<?> type = Class.forName(
                    "com.vivo.musicwidgetmix.lyrics.CompatMethodHookAdapter");
            java.lang.reflect.Constructor<?> constructor =
                    type.getDeclaredConstructor(MethodHook.class);
            constructor.setAccessible(true);
            Object item = constructor.newInstance(callback);
            ADAPTERS.add(item);
            return item;
        }
    }

    private static MethodHook callback(Object adapter) throws Exception {
        Field field = adapter.getClass().getDeclaredField("callback");
        field.setAccessible(true);
        return (MethodHook) field.get(adapter);
    }
}
