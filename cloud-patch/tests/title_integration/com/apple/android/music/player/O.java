package com.apple.android.music.player;

import com.apple.android.music.playback.model.StoreMediaItem;

public final class O {
    public static StoreMediaItem item;
    public static z3.y received;

    /** 1609 signature: b(Lz3/y;)Lcom/apple/android/music/model/PlaybackItem; */
    public static com.apple.android.music.model.PlaybackItem b(z3.y metadata) {
        received = metadata;
        return item;
    }
}
