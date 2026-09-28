package com.vivo.musicwidgetmix.lyrics;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import dev.amenhancer.module.hook.ModernMethodHook;
import dev.amenhancer.module.hook.ModernXposedRuntime;

public final class AtomicServiceDiscoveryHook extends ModernMethodHook {
    private static final String ACTION = "com.vivo.musicwidgetmix.support.service";
    private static final ComponentName APPLE_MUSIC = new ComponentName(
            "com.apple.android.music", "com.apple.android.music.player.MediaPlaybackService");

    private AtomicServiceDiscoveryHook() {
    }

    private static final AtomicServiceDiscoveryHook HOOK = new AtomicServiceDiscoveryHook();

    public static void install(String packageName, ClassLoader classLoader) {
        if (!"com.vivo.musicwidgetmix".equals(packageName)) {
            return;
        }
        try {
            Class<?> type = Class.forName("android.app.ApplicationPackageManager");
            for (Method method : type.getDeclaredMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                if (!"queryIntentServices".equals(method.getName())
                        || parameters.length != 2
                        || !Intent.class.equals(parameters[0])
                        || (!int.class.equals(parameters[1]) && !Integer.class.equals(parameters[1]))) {
                    continue;
                }
                ModernXposedRuntime.INSTANCE.hookMethod(method, HOOK);
            }
        } catch (Throwable error) {
            ModernXposedRuntime.INSTANCE.log("Atomic service discovery hook failed", error);
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
