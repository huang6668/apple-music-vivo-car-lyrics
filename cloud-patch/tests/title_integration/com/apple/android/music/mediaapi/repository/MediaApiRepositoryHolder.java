package com.apple.android.music.mediaapi.repository;

public final class MediaApiRepositoryHolder {
    public static final Companion Companion = new Companion();
    private MediaApiRepositoryHolder() {}
    public static final class Companion {
        private Object api;
        public Object getMediaApi() { return api; }
        public void setMediaApi(Object value) { api = value; }
    }
}
