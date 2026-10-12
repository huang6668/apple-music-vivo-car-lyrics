package com.apple.android.music.player;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Properties;

public final class TitleCacheStoreTest {
    private static final String CACHE_FILE = "vivo-car-title-cn-v1.properties";

    private static final class FakeClock implements TitleCacheStore.Clock {
        long time = 1000;

        @Override
        public long now() {
            return time;
        }
    }

    public static void main(String[] args) throws Exception {
        testRestartAndUnicode();
        testSuccessfulTitlesOnly();
        testExpiryAndClockRollback();
        testLruAndBoundedReload();
        testCorruptionAndNamespace();
        testWriteFailureKeepsPreviousFile();
        testUnavailableDirectory();
        testBatchPutAndGet();
        testLegacyArtistResolutionMigration();
        testResolvedTrackRestart();
        testTrackValidationAndPartialBatch();
        System.out.println("TitleCacheStore tests passed");
    }

    private static void testLegacyArtistResolutionMigration() throws Exception {
        File directory = directory();
        try {
            FakeClock clock = new FakeClock();
            Properties properties = fixture();
            write(directory, properties);
            TitleCacheStore store = store(directory, 4, 100, clock);
            TitleCorrectionState.TrackInfo legacy = store.getTrack("123");
            equal("Valid", legacy.title);
            equal("", legacy.artist);
            check(!legacy.artistResolved, "old title-only cache must request an artist backfill");

            properties.setProperty("entry.0.artist", "Legacy artist");
            write(directory, properties);
            legacy = store(directory, 4, 100, clock).getTrack("123");
            equal("Legacy artist", legacy.artist);
            check(legacy.artistResolved, "old separate artist field is already resolved");

            properties.remove("entry.0.artist");
            properties.setProperty("entry.0.title", "Legacy title\tLegacy tab artist");
            write(directory, properties);
            legacy = store(directory, 4, 100, clock).getTrack("123");
            equal("Legacy title", legacy.title);
            equal("Legacy tab artist", legacy.artist);
            check(legacy.artistResolved, "old composite title preserves its resolved artist");

            properties.setProperty("entry.0.title", "Legacy title\t");
            write(directory, properties);
            legacy = store(directory, 4, 100, clock).getTrack("123");
            check(legacy.artistResolved, "explicit old composite response may have no artist");

            properties.setProperty("entry.0.title", "Old title");
            properties.setProperty("entry.0.artistResolved", "false");
            write(directory, properties);
            store = store(directory, 4, 100, clock);
            check(!store.getTrack("123").artistResolved, "explicit partial flag survives loading");
            check(store.putTrack("123", "New title", "New artist"), "complete artist backfill persists");
            TitleCorrectionState.TrackInfo migrated = store(directory, 4, 100, clock).getTrack("123");
            equal("New title", migrated.title);
            equal("New artist", migrated.artist);
            check(migrated.artistResolved, "complete result survives process restart");
            equal("1", read(directory).getProperty("schema"));
            equal("true", read(directory).getProperty("entry.0.artistResolved"));
        } finally {
            delete(directory);
        }
    }

    private static void testResolvedTrackRestart() throws Exception {
        File directory = directory();
        try {
            FakeClock clock = new FakeClock();
            TitleCacheStore store = store(directory, 4, 100, clock);
            String title = "\u6a31\u82b1\u8349";
            String artist = "\u5b59\u71d5\u59ff";
            check(store.putTrack("123", new TitleCorrectionState.TrackInfo(title, artist)),
                    "full track can be written using a captured immutable result");
            check(store.putTrack("124", "Known title", ""), "resolved empty artist persists");
            check(store.putTrack("125", "Title\twith a tab", ""),
                    "structured results need not use the legacy tab delimiter");
            TitleCacheStore reopened = store(directory, 4, 100, clock);
            TitleCorrectionState.TrackInfo track = reopened.getTrack("123");
            equal(title, track.title);
            equal(artist, track.artist);
            check(track.artistResolved, "unicode full track keeps its completion flag");
            track = reopened.getTrack("124");
            equal("", track.artist);
            check(track.artistResolved, "successful empty artist is not mistaken for an old partial");
            equal("Title\twith a tab", reopened.getTrack("125").title);
            java.util.Map<String, TitleCorrectionState.TrackInfo> batch =
                    reopened.getAllTracks(java.util.Arrays.asList("123", "124"));
            check(batch.get("123").artistResolved && batch.get("124").artistResolved,
                    "batch disk hits preserve completion flags");
            check(!store.putTrack("126", "", ""), "negative catalog result is not persisted");
            equal(null, reopened.getTrack("126"));
        } finally {
            delete(directory);
        }
    }

    private static void testTrackValidationAndPartialBatch() throws Exception {
        File directory = directory();
        try {
            FakeClock clock = new FakeClock();
            TitleCacheStore store = store(directory, 4, 100, clock);
            java.util.Map<String, TitleCorrectionState.TrackInfo> tracks =
                    new java.util.LinkedHashMap<String, TitleCorrectionState.TrackInfo>();
            tracks.put("123", new TitleCorrectionState.TrackInfo("Partial title", "", false));
            tracks.put("124", new TitleCorrectionState.TrackInfo("Full title", "Full artist"));
            tracks.put("125", new TitleCorrectionState.TrackInfo("", "Artist-only result"));
            check(store.putAllTracks(tracks), "mixed full and partial track batch persists");
            TitleCacheStore reopened = store(directory, 4, 100, clock);
            check(!reopened.getTrack("123").artistResolved, "partial batch is not promoted to complete");
            check(reopened.getTrack("124").artistResolved, "complete batch remains complete");
            equal("Artist-only result", reopened.getTrack("125").artist);
            char[] oversized = new char[2049];
            java.util.Arrays.fill(oversized, 'a');
            check(!store.putTrack("126", "Title", new String(oversized)), "oversized artist rejected");
            check(!store.putTrack("126", (TitleCorrectionState.TrackInfo) null), "null track rejected");
            Properties properties = fixture();
            properties.setProperty("entry.0.artist", new String(oversized));
            write(directory, properties);
            equal(null, store(directory, 4, 100, clock).getTrack("123"));
        } finally {
            delete(directory);
        }
    }

    private static void testRestartAndUnicode() throws Exception {
        File directory = directory();
        try {
            FakeClock clock = new FakeClock();
            TitleCacheStore store = store(directory, 4, 100, clock);
            String title = "\u6a31\u82b1\u8349 = \\ \u2014\n\u6b4c\u540d";
            check(store.put(" 00123 ", " " + title + " "), "initial persistent write");
            equal(title, store.get("123"));
            equal(title, store(directory, 4, 100, clock).get("00123"));
            check(store.put("124", "Same stock title"), "equal stock title still cacheable");
            equal("Same stock title", store(directory, 4, 100, clock).get("124"));
        } finally {
            delete(directory);
        }
    }

    private static void testSuccessfulTitlesOnly() throws Exception {
        File directory = directory();
        try {
            FakeClock clock = new FakeClock();
            TitleCacheStore store = store(directory, 4, 100, clock);
            check(store.put("123", "Valid"), "save valid result");
            for (String title : new String[]{null, "", "   "}) {
                check(!store.put("123", title), "negative response is not persistent");
                equal("Valid", store.get("123"));
            }
            for (String id : new String[]{null, "", "0", "i.123", "-1", "1.5", "\uff11"}) {
                check(!store.put(id, "Wrong"), "invalid catalog rejected");
                equal(null, store.get(id));
            }
            char[] oversized = new char[2049];
            java.util.Arrays.fill(oversized, 'a');
            check(!store.put("124", new String(oversized)), "oversized title rejected");
            equal(null, store.get("124"));
            equal("Valid", store(directory, 4, 100, clock).get("123"));
        } finally {
            delete(directory);
        }
    }

    private static void testExpiryAndClockRollback() throws Exception {
        File directory = directory();
        try {
            FakeClock clock = new FakeClock();
            TitleCacheStore store = store(directory, 4, 100, clock);
            check(store.put("123", "Old"), "save expiring title");
            clock.time = 1099;
            equal("Old", store.get("123"));
            clock.time = 1100;
            equal(null, store.get("123"));
            equal(null, store(directory, 4, 100, clock).get("123"));
            check(store.put("124", "New"), "save fresh title");
            clock.time = 1099;
            equal(null, store.get("124"));
            equal(null, store(directory, 4, 100, clock).get("124"));
        } finally {
            delete(directory);
        }
    }

    private static void testLruAndBoundedReload() throws Exception {
        File directory = directory();
        try {
            FakeClock clock = new FakeClock();
            TitleCacheStore store = store(directory, 2, 100, clock);
            check(store.put("1", "One"), "save one");
            check(store.put("2", "Two"), "save two");
            equal("One", store.get("1"));
            check(store.put("3", "Three"), "save three");
            equal(null, store.get("2"));
            equal("One", store.get("1"));
            TitleCacheStore reopened = store(directory, 2, 100, clock);
            equal(null, reopened.get("2"));
            equal("One", reopened.get("1"));
            equal("Three", reopened.get("3"));
            TitleCacheStore smaller = store(directory, 1, 100, clock);
            equal(null, smaller.get("1"));
            equal("Three", smaller.get("3"));
            clock.time += 100;
            check(reopened.put("4", "Four"), "put prunes expired records");
            Properties properties = read(directory);
            equal("1", properties.getProperty("count"));
        } finally {
            delete(directory);
        }
    }

    private static void testCorruptionAndNamespace() throws Exception {
        File directory = directory();
        try {
            FakeClock clock = new FakeClock();
            Properties properties = fixture();
            properties.setProperty("namespace", "us_v1");
            write(directory, properties);
            equal(null, store(directory, 4, 100, clock).get("123"));
            properties.setProperty("namespace", TitleCacheStore.NAMESPACE);
            properties.setProperty("schema", "2");
            write(directory, properties);
            equal(null, store(directory, 4, 100, clock).get("123"));
            properties.setProperty("schema", "1");
            properties.setProperty("count", String.valueOf(TitleCacheStore.MAX_ENTRIES + 1));
            write(directory, properties);
            equal(null, store(directory, 4, 100, clock).get("123"));
            properties.setProperty("count", "2");
            properties.setProperty("entry.1.id", "124");
            properties.setProperty("entry.1.title", "Damaged");
            properties.setProperty("entry.1.writtenAt", "not-a-time");
            write(directory, properties);
            TitleCacheStore store = store(directory, 4, 100, clock);
            equal("Valid", store.get("123"));
            equal(null, store.get("124"));
            try (OutputStreamWriter writer = new OutputStreamWriter(
                    new FileOutputStream(new File(directory, CACHE_FILE)), StandardCharsets.UTF_8)) {
                writer.write("broken=\\uZZZZ\n");
            }
            equal(null, store(directory, 4, 100, clock).get("123"));
        } finally {
            delete(directory);
        }
    }

    private static void testWriteFailureKeepsPreviousFile() throws Exception {
        File directory = directory();
        try {
            FakeClock clock = new FakeClock();
            TitleCacheStore store = store(directory, 4, 100, clock);
            check(store.put("123", "Previous"), "save prior cache");
            File temporary = new File(directory, CACHE_FILE + ".tmp");
            check(temporary.mkdir(), "block temporary cache path");
            check(!store.put("123", "New"), "write failure is reported");
            equal("New", store.get("123"));
            equal("Previous", store(directory, 4, 100, clock).get("123"));
            check(temporary.delete(), "remove temporary blocker");
            check(store.put("124", "Other"), "write recovers");
            equal("New", store(directory, 4, 100, clock).get("123"));
        } finally {
            delete(directory);
        }
    }

    private static void testUnavailableDirectory() throws Exception {
        FakeClock clock = new FakeClock();
        TitleCacheStore store = store(null, 4, 100, clock);
        check(!store.put("123", "Memory only"), "missing context disables persistence");
        equal("Memory only", store.get("123"));
        File directory = directory();
        try {
            File regularFile = new File(directory, "regular-file");
            check(regularFile.createNewFile(), "create non-directory");
            store = store(regularFile, 4, 100, clock);
            check(!store.put("123", "Memory only"), "invalid directory fails safely");
            equal("Memory only", store.get("123"));
        } finally {
            delete(directory);
        }
    }

    private static void testBatchPutAndGet() throws Exception {
        File directory = directory();
        try {
            FakeClock clock = new FakeClock();
            TitleCacheStore store = store(directory, 4, 100, clock);
            java.util.Map<String, String> batch = new java.util.HashMap<String, String>();
            batch.put("101", "Song 101");
            batch.put("102", "Song 102");
            batch.put("invalid", "Invalid ID");
            batch.put("103", "");
            check(store.putAll(batch), "batch put should succeed for valid items");

            java.util.Map<String, String> retrieved = store.getAll(java.util.Arrays.asList("101", "102", "103", "999"));
            equal(2, retrieved.size());
            equal("Song 101", retrieved.get("101"));
            equal("Song 102", retrieved.get("102"));
            equal(null, retrieved.get("103"));
            equal(null, retrieved.get("999"));

            TitleCacheStore reopened = store(directory, 4, 100, clock);
            java.util.Map<String, String> fromDisk = reopened.getAll(java.util.Arrays.asList("101", "102"));
            equal(2, fromDisk.size());
            equal("Song 101", fromDisk.get("101"));
            equal("Song 102", fromDisk.get("102"));

            java.util.Map<String, TitleCorrectionState.TrackInfo> trackBatch =
                    new java.util.HashMap<String, TitleCorrectionState.TrackInfo>();
            trackBatch.put("201", new TitleCorrectionState.TrackInfo("Song 201", "Artist 201"));
            check(store.putAllTracks(trackBatch), "batch track put should succeed");
            TitleCorrectionState.TrackInfo t201 = store.getTrack("201");
            check(t201 != null, "track 201 found");
            equal("Song 201", t201.title);
            equal("Artist 201", t201.artist);

            check(!store.putAll(null), "null map rejected");
            check(!store.putAll(java.util.Collections.<String, String>emptyMap()), "empty map rejected");
            check(store.getAll(null).isEmpty(), "null ids return empty map");
            check(store.getAll(java.util.Collections.<String>emptyList()).isEmpty(), "empty ids return empty map");
        } finally {
            delete(directory);
        }
    }

    private static TitleCacheStore store(File directory, int capacity, long ttl, FakeClock clock) {
        return new TitleCacheStore(directory, capacity, ttl, clock);
    }

    private static File directory() throws Exception {
        return Files.createTempDirectory("mainland-title-cache-test").toFile();
    }

    private static Properties fixture() {
        Properties properties = new Properties();
        properties.setProperty("schema", "1");
        properties.setProperty("namespace", TitleCacheStore.NAMESPACE);
        properties.setProperty("count", "1");
        properties.setProperty("entry.0.id", "123");
        properties.setProperty("entry.0.title", "Valid");
        properties.setProperty("entry.0.writtenAt", "1000");
        return properties;
    }

    private static void write(File directory, Properties properties) throws Exception {
        try (OutputStreamWriter writer = new OutputStreamWriter(
                new FileOutputStream(new File(directory, CACHE_FILE)), StandardCharsets.UTF_8)) {
            properties.store(writer, "Test cache");
        }
    }

    private static Properties read(File directory) throws Exception {
        Properties properties = new Properties();
        try (java.io.InputStreamReader reader = new java.io.InputStreamReader(
                new java.io.FileInputStream(new File(directory, CACHE_FILE)),
                StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }

    private static void delete(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) delete(child);
        }
        if (!file.delete()) throw new AssertionError("Cannot remove test path " + file);
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void equal(Object expected, Object actual) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError("Expected " + expected + ", actual " + actual);
        }
    }
}
