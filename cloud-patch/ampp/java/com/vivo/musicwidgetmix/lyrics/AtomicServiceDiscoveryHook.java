package com.vivo.musicwidgetmix.lyrics;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.util.Log;
import android.util.SparseArray;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public final class AtomicServiceDiscoveryHook extends MethodHook {
    private static final String TAG = "AppleMusicLyrics";
    private static final String ACTION = "com.vivo.musicwidgetmix.support.service";
    private static final ComponentName APPLE_MUSIC = new ComponentName(
            "com.apple.android.music", "com.apple.android.music.player.MediaPlaybackService");

    private AtomicServiceDiscoveryHook() {
    }

    private static final AtomicServiceDiscoveryHook HOOK = new AtomicServiceDiscoveryHook();

    public static void install(String packageName) {
        if (!"com.vivo.musicwidgetmix".equals(packageName)) {
            return;
        }
        try {
            Class<?> type = Class.forName("android.app.ApplicationPackageManager");
            Method method = type.getDeclaredMethod("queryIntentServices", Intent.class, int.class);
            method.setAccessible(true);
            if (!ModernHookBridge.hook(method, HOOK)) {
                Log.e(TAG, "Unable to hook PackageManager.queryIntentServices");
            }
        } catch (Throwable error) {
            Log.e(TAG, "Atomic service discovery hook install failed", error);
        }
    }

    private static boolean isAtomicQuery(Object[] args) {
        return args.length >= 1 && args[0] instanceof Intent
                && ACTION.equals(((Intent) args[0]).getAction());
    }

    @Override
    public void afterHookedMethod(MethodHookParam param) {
        Object resultValue = param.getResultValue();
        if (!(resultValue instanceof List) || !isAtomicQuery(param.getArgs())) {
            return;
        }
        List<?> original = (List<?>) resultValue;
        for (Object item : original) {
            if (item instanceof ResolveInfo) {
                ServiceInfo serviceInfo = ((ResolveInfo) item).serviceInfo;
                if (serviceInfo != null
                        && "com.apple.android.music".equals(serviceInfo.packageName)) {
                    return;
                }
            }
        }
        List<ResolveInfo> patched = new ArrayList<>(original.size() + 1);
        for (Object item : original) {
            if (item instanceof ResolveInfo) {
                patched.add((ResolveInfo) item);
            }
        }
        ResolveInfo appleMusic = new ResolveInfo();
        ServiceInfo serviceInfo = new ServiceInfo();
        serviceInfo.packageName = APPLE_MUSIC.getPackageName();
        serviceInfo.name = APPLE_MUSIC.getClassName();
        serviceInfo.exported = true;
        appleMusic.serviceInfo = serviceInfo;
        patched.add(appleMusic);
        param.setResult(patched);
    }
}
