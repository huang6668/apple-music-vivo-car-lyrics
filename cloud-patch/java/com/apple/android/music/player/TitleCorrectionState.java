package com.apple.android.music.player;

import java.util.LinkedHashMap;
import java.util.Map;

/** Independent title identity and request state; no playback or metadata publishing. */
public final class TitleCorrectionState {
    private static final int DEFAULT_CACHE_SIZE = 64;

    public static final class Request {
        public final Object manager;
        public final long generation;
        public final String queueId;
        public final String catalogId;
        public final String persistentId;
        public final String originalTitle;
        private boolean completed;

        private Request(Snapshot snapshot) {
            manager = snapshot.manager;
            generation = snapshot.generation;
            queueId = snapshot.queueId;
            catalogId = snapshot.catalogId;
            persistentId = snapshot.persistentId;
            originalTitle = snapshot.originalTitle;
        }
    }

    public static final class Snapshot {
        public final Object manager;
        public final long generation;
        public final String queueId;
        public final String catalogId;
        public final String persistentId;
        public final String originalTitle;
        public final boolean pending;
        public final boolean completed;
        public final String correctedTitle;

        private Snapshot(TitleCorrectionState state) {
            manager = state.manager;
            generation = state.generation;
            queueId = state.queueId;
            catalogId = state.catalogId;
            persistentId = state.persistentId;
            originalTitle = state.originalTitle;
            pending = state.pending;
            completed = state.completed;
            correctedTitle = state.correction();
        }
    }

    private static final class CachedTitle {
        final long generation;
        final String title;

        CachedTitle(long generation, String title) {
            this.generation = generation;
            this.title = title;
        }
    }

    private final Map<String, CachedTitle> cache;
    private Object manager;
    private long generation;
    private String queueId = "";
    private String catalogId = "";
    private String persistentId = "";
    private String originalTitle = "";
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
        cache = new LinkedHashMap<String, CachedTitle>(cacheSize, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, CachedTitle> eldest) {
                return size() > cacheSize;
            }
        };
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
        CachedTitle cached = cache.get(catalogId);
        if (cached != null) {
            resolvedTitle = cached.title;
            completed = true;
            return null;
        }
        activeRequest = new Request(new Snapshot(this));
        pending = true;
        return activeRequest;
    }

    /** Late results populate the bounded cache but cannot settle or change another track. */
    public synchronized boolean complete(Request request, String title) {
        if (request == null || request.completed) {
            return false;
        }
        request.completed = true;
        String normalizedTitle = text(title);
        if (normalizedTitle.isEmpty()) {
            normalizedTitle = null;
        }
        CachedTitle cached = cache.get(request.catalogId);
        if (cached == null || cached.generation <= request.generation) {
            // Same-name responses retain their value: another play may have a different stock title.
            cache.put(request.catalogId, new CachedTitle(request.generation, normalizedTitle));
        }
        if (activeRequest != request || manager != request.manager
                || generation != request.generation || !queueId.equals(request.queueId)
                || !catalogId.equals(request.catalogId)) {
            return false;
        }
        pending = false;
        completed = true;
        resolvedTitle = normalizedTitle;
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

    private String correction() {
        return completed && resolvedTitle != null
                && !resolvedTitle.equals(originalTitle.trim()) ? resolvedTitle : null;
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}
