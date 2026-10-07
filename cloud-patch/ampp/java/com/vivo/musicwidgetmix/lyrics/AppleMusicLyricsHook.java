package com.vivo.musicwidgetmix.lyrics;

import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;

public final class AppleMusicLyricsHook extends MethodHook {
    private static final String TAG = "AppleMusicLyrics";
    private static final AppleMusicLyricsHook HOOK = new AppleMusicLyricsHook();
    private static final Map<Method, Integer> HOOK_POINTS =
            new HashMap<Method, Integer>();

    private static final int POINT_NATIVE_MEDIA_ITEM = 1;
    private static final int POINT_CURRENT_ITEM_CHANGED = 2;
    private static final int POINT_METADATA_UPDATED = 3;
    private static final int POINT_PLAYBACK_ERROR = 4;
    private static final int POINT_SEEK = 5;
    private static final int POINT_ATOMIC_CONNECTED = 6;
    private static final int HOOK_BEFORE = 0;
    private static final int HOOK_AFTER = 1;
    private static final MethodHook BEFORE_HOOK = new MethodHook() {
        @Override
        public void beforeHookedMethod(MethodHookParam param) {
            dispatch(param);
        }
    };

    private AppleMusicLyricsHook() {
    }

    public static void install(String packageName, ClassLoader classLoader) {
        if (!"com.apple.android.music".equals(packageName)) {
            return;
        }
        String managerClass = "com.apple.android.music.player.S";
        try {
            classLoader.loadClass(managerClass);
        } catch (ClassNotFoundException e) {
            managerClass = "com.apple.android.music.player.Q";
        }
        hook(classLoader, managerClass, "P",
                POINT_NATIVE_MEDIA_ITEM, 2, HOOK_BEFORE);
        if (!hasHookPoint(POINT_NATIVE_MEDIA_ITEM)) {
            hook(classLoader, managerClass, "F",
                    POINT_NATIVE_MEDIA_ITEM, 2, HOOK_BEFORE);
        }
        hook(classLoader, managerClass, "onCurrentItemChanged",
                POINT_CURRENT_ITEM_CHANGED, 3, HOOK_BEFORE);
        hook(classLoader, managerClass, "onMetadataUpdated",
                POINT_METADATA_UPDATED, 2, HOOK_BEFORE);
        hook(classLoader, managerClass, "onPlaybackError",
                POINT_PLAYBACK_ERROR, 2, HOOK_BEFORE);
        hook(classLoader, managerClass, "seekTo",
                POINT_SEEK, 1, HOOK_AFTER);
        hook(classLoader, "com.apple.android.music.player.e0", "o",
                POINT_ATOMIC_CONNECTED, 2, HOOK_BEFORE);
        if (!hasHookPoint(POINT_ATOMIC_CONNECTED)) {
            hook(classLoader, "com.apple.android.music.player.e0", "p",
                    POINT_ATOMIC_CONNECTED, 2, HOOK_BEFORE);
        }
        Log.i(TAG, "Apple Music lyrics hooks installed: " + HOOK_POINTS.size());
    }

    private static boolean hasHookPoint(int point) {
        for (Integer p : HOOK_POINTS.values()) {
            if (p != null && p.intValue() == point) {
                return true;
            }
        }
        return false;
    }

    private static void hook(ClassLoader classLoader, String className, String methodName,
                             int point, int preferredParameterCount, int phase) {
        try {
            Class<?> type = classLoader.loadClass(className);
            Method selected = null;
            for (Method method : type.getDeclaredMethods()) {
                if (!methodName.equals(method.getName()) || Modifier.isStatic(method.getModifiers())) {
                    continue;
                }
                if (selected == null || closer(method, selected, preferredParameterCount)) {
                    selected = method;
                }
            }
            if (selected == null) {
                throw new NoSuchMethodException(className + "#" + methodName);
            }
            selected.setAccessible(true);
            HOOK_POINTS.put(selected, Integer.valueOf(point));
            MethodHook callback = phase == HOOK_BEFORE ? BEFORE_HOOK : HOOK;
            if (!ModernHookBridge.hook(selected, callback)) {
                Log.e(TAG, "Hook request failed for " + selected.toGenericString());
            }
        } catch (Throwable error) {
            Log.e(TAG, "Hook failed: " + className + "#" + methodName, error);
        }
    }

    private static boolean closer(Method candidate, Method selected, int preferred) {
        return Math.abs(candidate.getParameterTypes().length - preferred)
                < Math.abs(selected.getParameterTypes().length - preferred);
    }

    @Override
    public void afterHookedMethod(MethodHookParam param) {
        dispatch(param);
    }

    private static void dispatch(MethodHookParam param) {
        try {
            Integer point = HOOK_POINTS.get(param.getMethod());
            if (point == null) {
                return;
            }
            Object[] args = param.getArgs();
            switch (point.intValue()) {
                case POINT_NATIVE_MEDIA_ITEM:
                    Object mediaArg = (args != null && args.length > 0 && !(args[0] instanceof Number))
                            ? args[0]
                            : (args != null && args.length > 1 ? args[1] : null);
                    com.apple.android.music.player.VivoCarLyrics.onNativeMediaItem(mediaArg);
                    break;
                case POINT_CURRENT_ITEM_CHANGED:
                    com.apple.android.music.player.VivoCarLyrics.onCurrentItemChanged(
                            param.getThisObject(), args[2]);
                    break;
                case POINT_METADATA_UPDATED:
                    com.apple.android.music.player.VivoCarLyrics.onMetadataUpdated(
                            param.getThisObject(), args[1]);
                    break;
                case POINT_PLAYBACK_ERROR:
                    com.apple.android.music.player.VivoCarLyrics.onPlaybackError(
                            param.getThisObject());
                    break;
                case POINT_SEEK:
                    com.apple.android.music.player.VivoCarLyrics.onSeek(
                            param.getThisObject(), ((Number) args[0]).longValue());
                    break;
                case POINT_ATOMIC_CONNECTED:
                    com.apple.android.music.player.VivoCarLyrics.onAtomicControllerConnected(
                            getPackageName(args[1]));
                    break;
                default:
                    break;
            }
        } catch (Throwable error) {
            Log.e(TAG, "Lyrics hook callback failed", error);
        }
    }

    private static String getPackageName(Object controller) {
        Object userInfo = readField(controller, "a");
        Object packageInfo = readField(userInfo, "a");
        Object packageName = readField(packageInfo, "a");
        return packageName instanceof String ? (String) packageName : null;
    }

    private static Object readField(Object object, String name) {
        if (object == null) {
            return null;
        }
        try {
            Field field = object.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(object);
        } catch (Throwable error) {
            return null;
        }
    }
}
