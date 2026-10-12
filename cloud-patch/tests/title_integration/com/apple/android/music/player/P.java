package com.apple.android.music.player;

public final class P {
    public static int calls;

    public Object b(z3.y metadata) {
        calls++;
        throw new AssertionError("nonstatic P converter must not run");
    }
}
