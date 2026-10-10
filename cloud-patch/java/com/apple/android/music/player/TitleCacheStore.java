package com.apple.android.music.player;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/** Mainland catalog cache. Construct and call on the caller's serial background worker. */
public final class TitleCacheStore {
    public static final String NAMESPACE = "cn_v1";
    private static final String SCHEMA = "1";
    private static final String FILE_NAME = "vivo-car-title-cn-v1.properties";
    private static final int MAX_ENTRIES = 4096;
    private static final long TTL_MILLIS = 30L * 24 * 60 * 60 * 1000;
    private static final int MAX_TITLE_LENGTH = 2048;
    private static final long MAX_FILE_BYTES = 4L * 1024 * 1024;

    interface Clock {
        long now();
    }

    private static final class Entry {
        final String title;
        final long writtenAt;

        Entry(String title, long writtenAt) {
            this.title = title;
            this.writtenAt = writtenAt;
        }
    }

    private final File directory;
    private final File file;
    private final int capacity;
    private final long ttlMillis;
    private final Clock clock;
    private final LinkedHashMap<String, Entry> entries =
            new LinkedHashMap<String, Entry>(MAX_ENTRIES, 0.75f, true);

    public TitleCacheStore(File appFilesDirectory) {
        this(appFilesDirectory, MAX_ENTRIES, TTL_MILLIS, new Clock() {
            @Override
            public long now() {
                return System.currentTimeMillis();
            }
        });
    }

    TitleCacheStore(File appFilesDirectory, int capacity, long ttlMillis, Clock clock) {
        if (capacity <= 0 || capacity > MAX_ENTRIES || ttlMillis <= 0 || clock == null) {
            throw new IllegalArgumentException("Invalid title cache limits");
        }
        directory = appFilesDirectory;
        file = directory == null ? null : new File(directory, FILE_NAME);
        this.capacity = capacity;
        this.ttlMillis = ttlMillis;
        this.clock = clock;
        load();
    }

    public synchronized String get(String catalogId) {
        String id = catalogId(catalogId);
        if (id.isEmpty()) return null;
        Entry entry = entries.get(id);
        if (entry == null) return null;
        if (!fresh(entry, clock.now())) {
            entries.remove(id);
            return null;
        }
        return entry.title;
    }

    public synchronized Map<String, String> getAll(Collection<String> catalogIds) {
        if (catalogIds == null || catalogIds.isEmpty()) return Collections.emptyMap();
        Map<String, String> result = new LinkedHashMap<String, String>();
        long now = clock.now();
        for (String candidate : catalogIds) {
            String id = catalogId(candidate);
            if (id.isEmpty()) continue;
            Entry entry = entries.get(id);
            if (entry == null) continue;
            if (!fresh(entry, now)) {
                entries.remove(id);
                continue;
            }
            result.put(id, entry.title);
        }
        return Collections.unmodifiableMap(result);
    }

    /** Rejects invalid results; valid titles remain in memory even if the disk write fails. */
    public synchronized boolean put(String catalogId, String title) {
        String id = catalogId(catalogId);
        String normalizedTitle = title == null ? "" : title.trim();
        if (id.isEmpty() || normalizedTitle.isEmpty()
                || normalizedTitle.length() > MAX_TITLE_LENGTH) {
            return false;
        }
        long now = clock.now();
        prune(now);
        entries.put(id, new Entry(normalizedTitle, now));
        trim();
        return persist();
    }

    public synchronized boolean putAll(Map<String, String> titles) {
        if (titles == null || titles.isEmpty()) return false;
        long now = clock.now();
        prune(now);
        boolean any = false;
        for (Map.Entry<String, String> item : titles.entrySet()) {
            String id = catalogId(item.getKey());
            String title = item.getValue() == null ? "" : item.getValue().trim();
            if (id.isEmpty() || title.isEmpty() || title.length() > MAX_TITLE_LENGTH) {
                continue;
            }
            entries.put(id, new Entry(title, now));
            any = true;
        }
        if (!any) return false;
        trim();
        return persist();
    }

    private void load() {
        if (file == null) return;
        try {
            if (!file.isFile() || file.length() > MAX_FILE_BYTES) return;
            Properties properties = new Properties();
            try (InputStreamReader reader = new InputStreamReader(
                    new FileInputStream(file), StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
            if (!SCHEMA.equals(properties.getProperty("schema"))
                    || !NAMESPACE.equals(properties.getProperty("namespace"))) {
                return;
            }
            int count = Integer.parseInt(properties.getProperty("count", "0"));
            if (count < 0 || count > MAX_ENTRIES) return;
            long now = clock.now();
            for (int index = 0; index < count; index++) {
                String prefix = "entry." + index + ".";
                String id = catalogId(properties.getProperty(prefix + "id"));
                String title = properties.getProperty(prefix + "title", "").trim();
                if (id.isEmpty() || title.isEmpty() || title.length() > MAX_TITLE_LENGTH) {
                    continue;
                }
                try {
                    Entry entry = new Entry(title,
                            Long.parseLong(properties.getProperty(prefix + "writtenAt", "")));
                    if (fresh(entry, now)) entries.put(id, entry);
                } catch (NumberFormatException ignored) {
                    // A damaged record does not invalidate other verified catalog entries.
                }
            }
            trim();
        } catch (Exception ignored) {
            entries.clear();
        }
    }

    private boolean persist() {
        if (file == null) return false;
        File temporary = new File(directory, FILE_NAME + ".tmp");
        boolean written = false;
        try {
            if (!directory.isDirectory() && !directory.mkdirs()) return false;
            Properties properties = new Properties();
            properties.setProperty("schema", SCHEMA);
            properties.setProperty("namespace", NAMESPACE);
            properties.setProperty("count", Integer.toString(entries.size()));
            int index = 0;
            for (Map.Entry<String, Entry> item : entries.entrySet()) {
                String prefix = "entry." + index++ + ".";
                properties.setProperty(prefix + "id", item.getKey());
                properties.setProperty(prefix + "title", item.getValue().title);
                properties.setProperty(prefix + "writtenAt",
                        Long.toString(item.getValue().writtenAt));
            }
            try (FileOutputStream stream = new FileOutputStream(temporary)) {
                OutputStreamWriter writer = new OutputStreamWriter(stream, StandardCharsets.UTF_8);
                properties.store(writer, "Mainland catalog title cache");
                writer.flush();
                stream.getFD().sync();
            }
            // Never delete the valid previous cache before the replacement is ready.
            Files.move(temporary.toPath(), file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            written = true;
            return true;
        } catch (Exception ignored) {
            return false;
        } finally {
            if (!written && temporary.isFile()) temporary.delete();
        }
    }

    private void prune(long now) {
        Iterator<Entry> iterator = entries.values().iterator();
        while (iterator.hasNext()) {
            if (!fresh(iterator.next(), now)) iterator.remove();
        }
    }

    private void trim() {
        while (entries.size() > capacity) {
            entries.remove(entries.keySet().iterator().next());
        }
    }

    private boolean fresh(Entry entry, long now) {
        return entry.writtenAt >= 0 && entry.writtenAt <= now
                && now - entry.writtenAt < ttlMillis;
    }

    private static String catalogId(String value) {
        String id = TitleCorrectionState.normalizeCatalogId(value);
        return id.length() <= 64 ? id : "";
    }
}
