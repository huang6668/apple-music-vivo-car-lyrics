package com.apple.android.music.playback.model;

public class StoreMediaItem implements com.apple.android.music.model.PlaybackItem {
    private String title;
    private String artistName;
    private final String catalogId;
    private final long queueId;
    private final long persistentId;
    private boolean showComposer;
    private int contentType;
    private String formattedSubtitle;

    public StoreMediaItem(long queueId, String catalogId, long persistentId, String title) {
        this(queueId, catalogId, persistentId, title, "Stock artist");
    }
    public StoreMediaItem(long queueId, String catalogId, long persistentId, String title, String artist) {
        this.queueId = queueId;
        this.catalogId = catalogId;
        this.persistentId = persistentId;
        this.title = title;
        this.artistName = artist;
    }
    public long getQueueId() { return queueId; }
    public String getSubscriptionStoreId() { return catalogId; }
    public long getPersistentId() { return persistentId; }
    public String getTitle() { return title; }
    public String getArtistName() { return artistName; }
    public String getNowPlayingSubtitle() {
        return formattedSubtitle == null ? artistName : formattedSubtitle;
    }
    public boolean isShowComposer() { return showComposer; }
    public void setShowComposer(boolean value) { showComposer = value; }
    public int getContentType() { return contentType; }
    public void setContentType(int value) { contentType = value; }
    public void setFormattedSubtitle(String value) { formattedSubtitle = value; }
}
