package com.apple.android.music.player;

import java.util.LinkedHashMap;
import java.util.Map;

/** Independent title identity and request state; no playback or metadata publishing. */
public final class TitleCorrectionState {
    private static final int DEFAULT_CACHE_SIZE = 4096;

    public static final class Request {
        public final Object manager;
        public final long generation;
        public final String queueId;
        public final String catalogId;
        public final String persistentId;
        public final String originalTitle;
        public final String cacheNamespace;
        private final CacheKey cacheKey;
        private final TitleCorrectionState owner;
        private boolean completed;

        private Request(Snapshot snapshot) {
            owner = snapshot.owner;
            manager = snapshot.manager;
            generation = snapshot.generation;
            queueId = snapshot.queueId;
            catalogId = snapshot.catalogId;
            persistentId = snapshot.persistentId;
            originalTitle = snapshot.originalTitle;
            cacheNamespace = snapshot.cacheNamespace;
            cacheKey = new CacheKey(cacheNamespace, catalogId);
        }
    }

    public static final class Snapshot {
        public final Object manager;
        public final long generation;
        public final String queueId;
        public final String catalogId;
        public final String persistentId;
        public final String originalTitle;
        public final String cacheNamespace;
        public final boolean pending;
        public final boolean completed;
        public final String correctedTitle;
        public final String correctedArtist;
        private final TitleCorrectionState owner;

        private Snapshot(TitleCorrectionState state) {
            owner = state;
            manager = state.manager;
            generation = state.generation;
            queueId = state.queueId;
            catalogId = state.catalogId;
            persistentId = state.persistentId;
            originalTitle = state.originalTitle;
            cacheNamespace = state.cacheNamespace;
            pending = state.pending;
            completed = state.completed;
            correctedTitle = state.correction();
            correctedArtist = state.correctedArtist();
        }
    }

    private static final class CacheKey {
        final String namespace;
        final String catalogId;

        CacheKey(String namespace, String catalogId) {
            this.namespace = namespace;
            this.catalogId = catalogId;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof CacheKey)) return false;
            CacheKey key = (CacheKey) other;
            return namespace.equals(key.namespace) && catalogId.equals(key.catalogId);
        }

        @Override
        public int hashCode() {
            return 31 * namespace.hashCode() + catalogId.hashCode();
        }
    }

    public static final class TrackInfo {
        public final String title;
        public final String artist;

        public TrackInfo(String title, String artist) {
            this.title = title == null ? "" : title;
            this.artist = artist == null ? "" : artist;
        }

        @Override
        public String toString() {
            return title + "\t" + artist;
        }
    }

    private static final class CachedTrack {
        final long generation;
        final String title;
        final String artist;

        CachedTrack(long generation, String title, String artist) {
            this.generation = generation;
            this.title = title == null ? "" : title;
            this.artist = artist == null ? "" : artist;
        }
    }

    private final java.util.concurrent.ConcurrentHashMap<String, TrackInfo> fastTrackCache =
            new java.util.concurrent.ConcurrentHashMap<String, TrackInfo>();
    private final Map<CacheKey, CachedTrack> cache;
    private Object manager;
    private long generation;
    private String queueId = "";
    private String catalogId = "";
    private String persistentId = "";
    private String originalTitle = "";
    private String cacheNamespace = "";
    private boolean pending;
    private boolean completed;
    private String resolvedTitle;
    private Request activeRequest;

    public TitleCorrectionState() {
        this(DEFAULT_CACHE_SIZE);
    }

    public TitleCorrectionState(final int cacheSize) {
        if (cacheSize <= 0) {
            throw new IllegalArgumentException("cacheSize must be positive");
        }
        cache = new LinkedHashMap<CacheKey, CachedTrack>(cacheSize, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<CacheKey, CachedTrack> eldest) {
                return size() > cacheSize;
            }
        };
    }

    /** Profile changes invalidate active work but retain independently bounded cache entries. */
    public synchronized boolean setCacheNamespace(String namespace) {
        String normalized = text(namespace);
        if (cacheNamespace.equals(normalized)) return false;
        cacheNamespace = normalized;
        fastTrackCache.clear();
        begin(manager, queueId, catalogId, persistentId, originalTitle);
        return true;
    }

    public synchronized long begin(Object newManager, String newQueueId, String newCatalogId,
                                   String newPersistentId, CharSequence newOriginalTitle) {
        generation++;
        manager = newManager;
        queueId = normalizeCatalogId(newQueueId);
        catalogId = normalizeCatalogId(newCatalogId);
        persistentId = text(newPersistentId);
        originalTitle = newOriginalTitle == null ? "" : newOriginalTitle.toString();
        pending = false;
        completed = false;
        resolvedTitle = null;
        activeRequest = null;
        return generation;
    }

    /** Only missing metadata may be filled, and only for the exact known current queue. */
    public synchronized boolean enrich(Object candidateManager, String candidateQueueId,
                                       String candidateCatalogId, String candidatePersistentId,
                                       CharSequence candidateOriginalTitle) {
        if (!matchesQueue(candidateManager, candidateQueueId)) {
            return false;
        }
        String normalizedCatalogId = normalizeCatalogId(candidateCatalogId);
        if (!catalogId.isEmpty() && !normalizedCatalogId.isEmpty()
                && !catalogId.equals(normalizedCatalogId)) {
            return false;
        }
        if (catalogId.isEmpty()) {
            catalogId = normalizedCatalogId;
        }
        if (persistentId.isEmpty()) {
            persistentId = text(candidatePersistentId);
        }
        if (originalTitle.isEmpty() && candidateOriginalTitle != null) {
            originalTitle = candidateOriginalTitle.toString();
        }
        return true;
    }

    /** A cache hit settles this generation without returning a network request. */
    public synchronized Request request() {
        if (manager == null || queueId.isEmpty() || catalogId.isEmpty()
                || pending || completed) {
            return null;
        }
        if (restoreCached(new Snapshot(this))) return null;
        activeRequest = new Request(new Snapshot(this));
        pending = true;
        return activeRequest;
    }

    /** Late results populate the bounded cache but cannot settle or change another track. */
    public synchronized boolean complete(Request request, String title) {
        if (request == null || request.owner != this || request.completed) {
            return false;
        }
        request.completed = true;
        String normalizedTitle = text(title);
        if (normalizedTitle.isEmpty()) {
            normalizedTitle = null;
        }
        CachedTrack cached = cache.get(request.cacheKey);
        if (cached == null || cached.generation <= request.generation) {
            String existingArtist = cached == null ? "" : cached.artist;
            cache.put(request.cacheKey, new CachedTrack(request.generation, normalizedTitle, existingArtist));
            fastTrackCache.put(request.catalogId, new TrackInfo(normalizedTitle, existingArtist));
        }
        if (!matchesRequest(request)) {
            return false;
        }
        pending = false;
        completed = true;
        resolvedTitle = normalizedTitle;
        activeRequest = null;
        return true;
    }

    /** Transient failures settle this generation without poisoning the catalog cache. */
    public synchronized boolean fail(Request request) {
        if (request == null || request.owner != this || request.completed) {
            return false;
        }
        request.completed = true;
        if (!matchesRequest(request)) {
            return false;
        }
        pending = false;
        completed = true;
        resolvedTitle = null;
        activeRequest = null;
        return true;
    }

    public synchronized String correctedTitle(Object candidateManager, String candidateQueueId) {
        return matchesQueue(candidateManager, candidateQueueId) ? correction() : null;
    }

    public synchronized String correctedTitle(Object candidateManager, String candidateQueueId,
                                               String candidateCatalogId) {
        return matchesIdentity(candidateManager, candidateQueueId, candidateCatalogId)
                ? correction() : null;
    }

    public synchronized CharSequence currentTitle(Object candidateManager, String candidateQueueId,
                                                   String candidateCatalogId, CharSequence stock) {
        String corrected = correctedTitle(candidateManager, candidateQueueId, candidateCatalogId);
        return corrected == null || (stock != null && corrected.contentEquals(stock))
                ? stock : corrected;
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(this);
    }

    public synchronized boolean acceptsResult(Request request) {
        if (request == null || request.owner != this || request.completed) return false;
        CachedTrack cached = cache.get(request.cacheKey);
        return cached == null || cached.generation <= request.generation;
    }

    /** A disk result cannot supersede an already-started query or a different identity. */
    public synchronized boolean restore(Snapshot identity, String title) {
        String normalized = text(title);
        if (!matchesSnapshot(identity) || pending || completed || normalized.isEmpty()) return false;
        cache.put(new CacheKey(cacheNamespace, catalogId), new CachedTrack(generation, normalized, ""));
        fastTrackCache.put(catalogId, new TrackInfo(normalized, ""));
        resolvedTitle = normalized;
        completed = true;
        return true;
    }

    public synchronized boolean restoreCached(Snapshot identity) {
        if (!matchesSnapshot(identity) || pending || completed) return false;
        CachedTrack cached = cache.get(new CacheKey(cacheNamespace, catalogId));
        if (cached == null) return false;
        resolvedTitle = cached.title;
        completed = true;
        return true;
    }

    synchronized String correctedArtist() {
        if (!completed) return null;
        TrackInfo info = fastTrackCache.get(catalogId);
        if (info != null && !info.artist.isEmpty()) return info.artist;
        CachedTrack cached = cache.get(new CacheKey(cacheNamespace, catalogId));
        return cached == null ? null : (cached.artist.isEmpty() ? null : cached.artist);
    }

    public String getCachedTitle(String candidateCatalogId) {
        String id = normalizeCatalogId(candidateCatalogId);
        if (id.isEmpty()) return null;
        TrackInfo info = fastTrackCache.get(id);
        if (info != null && !info.title.isEmpty()) return info.title;
        synchronized (this) {
            CachedTrack cached = cache.get(new CacheKey(cacheNamespace, id));
            return cached == null ? null : (cached.title.isEmpty() ? null : cached.title);
        }
    }

    public String getCachedArtist(String candidateCatalogId) {
        String id = normalizeCatalogId(candidateCatalogId);
        if (id.isEmpty()) return null;
        TrackInfo info = fastTrackCache.get(id);
        if (info != null && !info.artist.isEmpty()) return info.artist;
        synchronized (this) {
            CachedTrack cached = cache.get(new CacheKey(cacheNamespace, id));
            return cached == null ? null : (cached.artist.isEmpty() ? null : cached.artist);
        }
    }

    public void putCachedTrack(String candidateCatalogId, String title, String artist) {
        String id = normalizeCatalogId(candidateCatalogId);
        String normalizedTitle = text(title);
        String normalizedArtist = text(artist);
        if (id.isEmpty() || (normalizedTitle.isEmpty() && normalizedArtist.isEmpty())) return;
        fastTrackCache.put(id, new TrackInfo(normalizedTitle, normalizedArtist));
        synchronized (this) {
            cache.put(new CacheKey(cacheNamespace, id), new CachedTrack(generation, normalizedTitle, normalizedArtist));
        }
    }

    public void putCachedTitle(String candidateCatalogId, String title) {
        String id = normalizeCatalogId(candidateCatalogId);
        if (id.isEmpty()) return;
        String existingArtist = getCachedArtist(id);
        putCachedTrack(id, title, existingArtist == null ? "" : existingArtist);
    }

    public synchronized void putCachedTitles(Map<String, String> titles) {
        if (titles == null || titles.isEmpty()) return;
        for (Map.Entry<String, String> entry : titles.entrySet()) {
            putCachedTitle(entry.getKey(), entry.getValue());
        }
    }

    public synchronized void putCachedTracks(Map<String, ?> tracks) {
        if (tracks == null || tracks.isEmpty()) return;
        for (Map.Entry<String, ?> entry : tracks.entrySet()) {
            Object val = entry.getValue();
            if (val instanceof TrackInfo) {
                TrackInfo ti = (TrackInfo) val;
                putCachedTrack(entry.getKey(), ti.title, ti.artist);
            } else if (val instanceof CatalogTitleResolver.TrackInfo) {
                CatalogTitleResolver.TrackInfo ti = (CatalogTitleResolver.TrackInfo) val;
                putCachedTrack(entry.getKey(), ti.title, ti.artist);
            } else if (val instanceof String) {
                putCachedTrack(entry.getKey(), (String) val, "");
            }
        }
    }

    /** Revalidate a captured identity immediately before applying an asynchronous UI update. */
    public synchronized boolean matchesSnapshot(Snapshot candidate) {
        return candidate != null && candidate.owner == this
                && generation == candidate.generation
                && cacheNamespace.equals(candidate.cacheNamespace)
                && matchesIdentity(candidate.manager, candidate.queueId, candidate.catalogId);
    }

    /** An error from a retired manager must not invalidate the active playback manager. */
    public synchronized boolean reset(Object candidateManager) {
        if (manager != candidateManager) {
            return false;
        }
        begin(null, "", "", "", "");
        return true;
    }

    public static String selectCatalogId(String... candidates) {
        if (candidates != null) {
            for (String candidate : candidates) {
                String normalized = normalizeCatalogId(candidate);
                if (!normalized.isEmpty()) {
                    return normalized;
                }
            }
        }
        return "";
    }

    /** Accept positive decimal IDs only; do not reinterpret library/persistent IDs as catalog IDs. */
    public static String normalizeCatalogId(String candidate) {
        String value = text(candidate);
        int firstNonzero = -1;
        for (int index = 0; index < value.length(); index++) {
            char digit = value.charAt(index);
            if (digit < '0' || digit > '9') {
                return "";
            }
            if (digit != '0' && firstNonzero < 0) {
                firstNonzero = index;
            }
        }
        return firstNonzero < 0 ? "" : value.substring(firstNonzero);
    }

    private boolean matchesQueue(Object candidateManager, String candidateQueueId) {
        return manager != null && manager == candidateManager && !queueId.isEmpty()
                && queueId.equals(normalizeCatalogId(candidateQueueId));
    }

    private boolean matchesIdentity(Object candidateManager, String candidateQueueId,
                                    String candidateCatalogId) {
        return matchesQueue(candidateManager, candidateQueueId) && !catalogId.isEmpty()
                && catalogId.equals(normalizeCatalogId(candidateCatalogId));
    }

    private boolean matchesRequest(Request request) {
        return activeRequest == request && generation == request.generation
                && cacheNamespace.equals(request.cacheNamespace)
                && matchesIdentity(request.manager, request.queueId, request.catalogId);
    }

    private String correction() {
        return completed ? resolvedTitle : null;
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}
