package com.apple.android.music.player;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

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
        public final long cacheRevision;
        private final CacheKey cacheKey;
        private final TitleCorrectionState owner;
        private boolean completed;

        private Request(Snapshot snapshot, long revision) {
            owner = snapshot.owner;
            manager = snapshot.manager;
            generation = snapshot.generation;
            queueId = snapshot.queueId;
            catalogId = snapshot.catalogId;
            persistentId = snapshot.persistentId;
            originalTitle = snapshot.originalTitle;
            cacheNamespace = snapshot.cacheNamespace;
            cacheRevision = revision;
            cacheKey = new CacheKey(cacheNamespace, catalogId);
        }
    }

    public static final class CacheRequest {
        public final String cacheNamespace;
        public final long revision;
        private final TitleCorrectionState owner;
        private final long generation;
        private final boolean diskRestore;
        private final Set<String> catalogIds;

        private CacheRequest(TitleCorrectionState state, Collection<String> candidates,
                             boolean diskRestore) {
            owner = state;
            cacheNamespace = state.cacheNamespace;
            generation = state.generation;
            revision = ++state.cacheSequence;
            this.diskRestore = diskRestore;
            Set<String> ids = new LinkedHashSet<String>();
            if (candidates != null) {
                for (String candidate : candidates) {
                    String id = normalizeCatalogId(candidate);
                    if (!id.isEmpty()) ids.add(id);
                }
            }
            catalogIds = Collections.unmodifiableSet(ids);
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
        public final boolean artistResolved;
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
            artistResolved = state.resolvedTrack != null && state.resolvedTrack.artistResolved;
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
        public final boolean artistResolved;

        public TrackInfo(String title, String artist) {
            this(title, artist, true);
        }

        public TrackInfo(String title, String artist, boolean artistResolved) {
            this.title = title == null ? "" : title;
            this.artist = artist == null ? "" : artist;
            this.artistResolved = artistResolved;
        }

        @Override
        public String toString() {
            return title + "\t" + artist;
        }
    }

    private static final class CachedTrack {
        final long generation;
        final long revision;
        final long writtenRevision;
        final TrackInfo info;

        CachedTrack(long generation, long revision, long writtenRevision, TrackInfo info) {
            this.generation = generation;
            this.revision = revision;
            this.writtenRevision = writtenRevision;
            this.info = info;
        }
    }

    private final java.util.concurrent.ConcurrentHashMap<CacheKey, TrackInfo> fastTrackCache =
            new java.util.concurrent.ConcurrentHashMap<CacheKey, TrackInfo>();
    private final Map<CacheKey, CachedTrack> cache;
    private Object manager;
    private long generation;
    private long cacheSequence;
    private String queueId = "";
    private String catalogId = "";
    private String persistentId = "";
    private String originalTitle = "";
    private volatile String cacheNamespace = "";
    private boolean pending;
    private boolean completed;
    private TrackInfo resolvedTrack;
    private long resolvedGeneration = -1;
    private long resolvedRevision = -1;
    private long resolvedWrittenRevision = -1;
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
                if (size() <= cacheSize) return false;
                fastTrackCache.remove(eldest.getKey());
                return true;
            }
        };
    }

    /** Profile changes invalidate active work but retain independently bounded cache entries. */
    public synchronized boolean setCacheNamespace(String namespace) {
        String normalized = text(namespace);
        if (cacheNamespace.equals(normalized)) return false;
        cacheNamespace = normalized;
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
        resolvedTrack = null;
        resolvedGeneration = -1;
        resolvedRevision = -1;
        resolvedWrittenRevision = -1;
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

    /** Complete hits settle this generation; legacy title-only hits still request the artist. */
    public synchronized Request request() {
        if (manager == null || queueId.isEmpty() || catalogId.isEmpty()
                || pending || completed) {
            return null;
        }
        if (restoreCached(new Snapshot(this)) && completed) return null;
        activeRequest = new Request(new Snapshot(this), ++cacheSequence);
        pending = true;
        return activeRequest;
    }

    /** Late results populate the bounded cache but cannot settle or change another track. */
    public synchronized boolean complete(Request request, String title) {
        if (request == null || request.owner != this || request.completed) return false;
        CachedTrack cached = cache.get(request.cacheKey);
        return completeTrack(request, title, cached == null ? "" : cached.info.artist);
    }

    public synchronized boolean completeTrack(Request request, String title, String artist) {
        if (request == null || request.owner != this || request.completed) {
            return false;
        }
        request.completed = true;
        TrackInfo info = new TrackInfo(text(title), text(artist), true);
        CachedTrack cached = cache.get(request.cacheKey);
        boolean accepted = !newerCurrentTrack(request)
                && (cached == null || !cached.info.artistResolved
                    || cached.revision <= request.cacheRevision);
        if (accepted) {
            cached = cacheTrack(request.cacheKey, request.generation, request.cacheRevision, info);
        }
        if (!matchesRequest(request)) {
            return false;
        }
        pending = false;
        completed = true;
        if (accepted || (cached != null && cached.info.artistResolved
                && cached.revision >= resolvedRevision)) {
            resolve(cached);
        }
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

    public synchronized CharSequence currentArtist(Object candidateManager, String candidateQueueId,
                                                    String candidateCatalogId, CharSequence stock) {
        String corrected = matchesIdentity(candidateManager, candidateQueueId, candidateCatalogId)
                ? correctedArtist() : null;
        return corrected == null || (stock != null && corrected.contentEquals(stock))
                ? stock : corrected;
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(this);
    }

    public synchronized boolean acceptsResult(Request request) {
        if (request == null || request.owner != this || request.completed) return false;
        CachedTrack cached = cache.get(request.cacheKey);
        return !newerCurrentTrack(request) && (cached == null || !cached.info.artistResolved
                || cached.revision <= request.cacheRevision);
    }

    /** A disk result cannot supersede an already-started query or a different identity. */
    public synchronized boolean restore(Snapshot identity, String title) {
        return restoreTrack(identity, new TrackInfo(title, ""));
    }

    public synchronized boolean restoreTrack(Snapshot identity, TrackInfo track) {
        if (!matchesSnapshot(identity) || pending || completed || track == null) return false;
        TrackInfo normalized = normalizeTrack(track);
        if (normalized.title.isEmpty() && normalized.artist.isEmpty()) return false;
        CacheKey key = new CacheKey(cacheNamespace, catalogId);
        CachedTrack cached = cache.get(key);
        if (cached != null && cached.info.artistResolved) {
            return restoreCached(identity);
        }
        resolve(cacheTrack(key, generation, ++cacheSequence, normalized));
        completed = normalized.artistResolved;
        return true;
    }

    public synchronized boolean restoreCached(Snapshot identity) {
        if (!matchesSnapshot(identity) || pending || completed) return false;
        CachedTrack cached = cache.get(new CacheKey(cacheNamespace, catalogId));
        if (cached == null) return false;
        resolve(cached);
        completed = cached.info.artistResolved;
        return true;
    }

    /** Prefetch may refresh the current track without consuming its active single-track query. */
    public synchronized boolean refreshCachedTrack(Snapshot identity) {
        if (!matchesSnapshot(identity)) return false;
        CachedTrack cached = cache.get(new CacheKey(cacheNamespace, catalogId));
        if (cached == null || cached.revision < resolvedRevision
                || (resolvedTrack != null && resolvedTrack.artistResolved
                    && !cached.info.artistResolved)) return false;
        resolve(cached);
        if (!pending && cached.info.artistResolved) completed = true;
        return true;
    }

    synchronized String correctedArtist() {
        return resolvedTrack == null || resolvedTrack.artist.isEmpty() ? null : resolvedTrack.artist;
    }

    public String getCachedTitle(String candidateCatalogId) {
        TrackInfo info = getCachedTrack(candidateCatalogId);
        return info == null || info.title.isEmpty() ? null : info.title;
    }

    public String getCachedArtist(String candidateCatalogId) {
        TrackInfo info = getCachedTrack(candidateCatalogId);
        return info == null || info.artist.isEmpty() ? null : info.artist;
    }

    public TrackInfo getCachedTrack(String candidateCatalogId) {
        String id = normalizeCatalogId(candidateCatalogId);
        if (id.isEmpty()) return null;
        String namespace = cacheNamespace;
        TrackInfo info = fastTrackCache.get(new CacheKey(namespace, id));
        if (info != null && namespace.equals(cacheNamespace)) return info;
        synchronized (this) {
            CachedTrack cached = cache.get(new CacheKey(cacheNamespace, id));
            return cached == null ? null : cached.info;
        }
    }

    public synchronized void putCachedTrack(String candidateCatalogId, String title, String artist) {
        putCachedTrack(candidateCatalogId, new TrackInfo(title, artist));
    }

    public synchronized void putCachedTrack(String candidateCatalogId, TrackInfo track) {
        String id = normalizeCatalogId(candidateCatalogId);
        if (id.isEmpty() || track == null) return;
        TrackInfo normalized = normalizeTrack(track);
        if (normalized.title.isEmpty() && normalized.artist.isEmpty()
                && !normalized.artistResolved) return;
        CacheKey key = new CacheKey(cacheNamespace, id);
        CachedTrack cached = cache.get(key);
        if (cached != null && cached.info.artistResolved && !normalized.artistResolved) {
            return;
        }
        cacheTrack(key, generation, ++cacheSequence, normalized);
    }

    public synchronized void putCachedTitle(String candidateCatalogId, String title) {
        String id = normalizeCatalogId(candidateCatalogId);
        String normalized = text(title);
        if (id.isEmpty() || normalized.isEmpty()) return;
        putCachedTrack(id, new TrackInfo(normalized, "", false));
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
                putCachedTrack(entry.getKey(), ti);
            } else if (val instanceof CatalogTitleResolver.TrackInfo) {
                CatalogTitleResolver.TrackInfo ti = (CatalogTitleResolver.TrackInfo) val;
                putCachedTrack(entry.getKey(), ti.title, ti.artist);
            } else if (val instanceof String) {
                putCachedTitle(entry.getKey(), (String) val);
            }
        }
    }

    public synchronized CacheRequest beginCacheRequest(Collection<String> catalogIds) {
        return new CacheRequest(this, catalogIds, false);
    }

    public synchronized CacheRequest beginCacheRestore(Collection<String> catalogIds) {
        return new CacheRequest(this, catalogIds, true);
    }

    public synchronized Map<String, TrackInfo> putCachedTracks(CacheRequest request,
                                                               Map<String, TrackInfo> tracks) {
        if (request == null || request.owner != this || tracks == null || tracks.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, TrackInfo> accepted = new LinkedHashMap<String, TrackInfo>();
        for (Map.Entry<String, TrackInfo> item : tracks.entrySet()) {
            String id = normalizeCatalogId(item.getKey());
            if (!request.catalogIds.contains(id) || item.getValue() == null) continue;
            TrackInfo info = normalizeTrack(item.getValue());
            if (info.title.isEmpty() && info.artist.isEmpty() && !info.artistResolved) continue;
            CacheKey key = new CacheKey(request.cacheNamespace, id);
            CachedTrack cached = cache.get(key);
            // Disk restores yield to memory; network results follow request-start order.
            if (cached != null && ((cached.info.artistResolved
                    && (!info.artistResolved || request.diskRestore
                        || cached.revision > request.revision))
                    || (request.diskRestore && cached.writtenRevision > request.revision)
                    || (!info.artistResolved && cached.revision > request.revision))) continue;
            boolean current = cacheNamespace.equals(request.cacheNamespace) && catalogId.equals(id);
            if (current && resolvedTrack != null
                    && ((resolvedTrack.artistResolved && (!info.artistResolved
                        || request.diskRestore || resolvedRevision > request.revision))
                        || (request.diskRestore
                            && resolvedWrittenRevision > request.revision))) continue;
            cacheTrack(key, request.generation, request.revision, info);
            accepted.put(id, info);
        }
        return Collections.unmodifiableMap(accepted);
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

    private boolean newerCurrentTrack(Request request) {
        return resolvedTrack != null && resolvedTrack.artistResolved
                && (resolvedGeneration > request.generation || resolvedRevision > request.cacheRevision)
                && cacheNamespace.equals(request.cacheNamespace)
                && catalogId.equals(request.catalogId);
    }

    private String correction() {
        return resolvedTrack == null || resolvedTrack.title.isEmpty() ? null : resolvedTrack.title;
    }

    private CachedTrack cacheTrack(CacheKey key, long cachedGeneration, long revision, TrackInfo track) {
        CachedTrack cached = new CachedTrack(cachedGeneration, revision, ++cacheSequence, track);
        cache.put(key, cached);
        fastTrackCache.put(key, track);
        return cached;
    }

    private void resolve(CachedTrack cached) {
        resolvedTrack = cached.info;
        resolvedGeneration = cached.generation;
        resolvedRevision = cached.revision;
        resolvedWrittenRevision = cached.writtenRevision;
    }

    private static TrackInfo normalizeTrack(TrackInfo track) {
        return new TrackInfo(text(track.title), text(track.artist), track.artistResolved);
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}
