package com.vivo.musicwidgetmix.lyrics;

public final class ModuleDispatch {
    private ModuleDispatch() {
    }

    public static void install(String packageName, ClassLoader classLoader) {
        try {
            AtomicServiceDiscoveryHook.install(packageName);
        } catch (Throwable error) {
            android.util.Log.e("AppleMusicLyrics", "Atomic service discovery hook failed", error);
        }
        try {
            AppleMusicLyricsHook.install(packageName, classLoader);
        } catch (Throwable error) {
            android.util.Log.e("AppleMusicLyrics", "Apple Music lyrics hook failed", error);
        }
    }
}
