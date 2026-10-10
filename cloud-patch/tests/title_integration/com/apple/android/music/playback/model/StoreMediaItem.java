package com.apple.android.music.playback.model;

public class StoreMediaItem {
    private String title;
    private final String catalogId;
    private final long queueId;
    private final long persistentId;

    public StoreMediaItem(long queueId, String catalogId, long persistentId, String title) {
        this.queueId = queueId;
        this.catalogId = catalogId;
        this.persistentId = persistentId;
        this.title = title;
    }
    public long getQueueId() { return queueId; }
    public String getSubscriptionStoreId() { return catalogId; }
    public long getPersistentId() { return persistentId; }
    public String getTitle() { return title; }
}
