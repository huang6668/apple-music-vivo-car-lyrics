package com.apple.android.music.player;

import com.apple.android.music.playback.model.StoreMediaItem;

public final class O {
    public static StoreMediaItem item;
    public static z3.y received;

    public static StoreMediaItem b(z3.y metadata) {
        received = metadata;
        return item;
    }
}
