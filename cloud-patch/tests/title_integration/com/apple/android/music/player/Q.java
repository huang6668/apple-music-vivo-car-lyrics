package com.apple.android.music.player;

public final class Q {
    public static int calls;

    public static Object b(String incompatibleMetadata) {
        calls++;
        throw new AssertionError("incompatible Q converter must not run");
    }
}
