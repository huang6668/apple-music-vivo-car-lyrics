package com.apple.android.music.mediaapi.models;

/** Fixture for the confirmed host Song subtype when its type attribute is absent. */
public class Song {
    public String getId() { return "123"; }
    public Object getAttributes() {
        return java.util.Collections.singletonMap("name", "Typed song");
    }
}
