package com.apple.android.music.player;

import android.os.Bundle;
import android.os.Handler;
import com.apple.android.music.AppleMusicApplication;
import com.apple.android.music.mediaapi.repository.MediaApiRepositoryHolder;
import com.apple.android.music.playback.model.StoreMediaItem;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import kotlin.coroutines.Continuation;

public final class VivoTitleIntegrationTest {
    private static final String QUEUE = "com.apple.android.music.playback.metadata.ITEM_QUEUE_ID";
    private static final String CATALOG = "com.apple.android.music.playback.metadata.METADATA_KEY_MEDIA_ID";
    private static final String SUPPORT = "vivomusicmix.media.metadata.support_event";
    private static long nextId = 5000L;

    public static void main(String[] args) throws Exception {
        testWhitelistedFieldWrites();
        testCompatiblePlaybackConverter();
        testNativeIdentityGuards();
        testPlayerBindingAndStaleItems();
        testAsyncSwitchAndCache();
        testQueuedWorkAfterSwitch();
        testFailureAndTimeout();
        testResetRejectsLateApplication();
        testUnknownQueueEnrichment();
        testUnavailableCatalogIsBounded();
        testNotificationIdentityGuards();
        testFixedChinaIgnoresSystemLocale();
        testReplayUsesCachedTitleWithoutCatalog();
        testPersistedCacheAvoidsCatalog(false);
        testPersistedCacheAvoidsCatalog(true);
        testStalePersistedCacheCallback();
        testSuccessfulCatalogResultPersists();
        testCapturedTrackResultPersistsWithoutMixing();
        testLegacyMemoryTitleFetchesArtist();
        testLegacyDiskTitleFetchesArtist();
        testResolvedEmptyArtistDoesNotRetry();
        testArtistOnlyPlayerChange();
        testArtistPrefetchRefreshesCurrentTrack();
        testPrefetchTimeoutAllowsRetryWithoutLateArtist();
        testRegionalFallbackKeepsOriginalDeadline();
        testOlderBatchCannotOverwriteNewSingleTrack();
        testBatchCompletionOrderDoesNotRegressTrack();
        testFormattedPlayerArtistPreservesStock();
        testIndependentPlaylistArtist();
        Handler.reset();
        System.out.println("Vivo title integration tests passed");
    }

    private static void testWhitelistedFieldWrites() throws Exception {
        z3.y metadata = new z3.y("Stock");
        Object untouched = metadata.untouched;
        Bundle extras = metadata.J;
        call("writeCorrectedTrack", metadata, "Corrected", "Corrected artist");
        equal("Corrected", metadata.a);
        equal("Corrected artist", metadata.b);
        check(metadata.J == extras && metadata.untouched == untouched,
                "only whitelisted metadata title and artist fields changed");
        StoreMediaItem store = new StoreMediaItem(1L, "10", 20L, "Stock");
        call("writeCorrectedTrack", store, "Corrected", "Corrected artist");
        equal("Corrected", store.getTitle());
        equal("Corrected artist", store.getArtistName());
        equal("Corrected artist", store.getNowPlayingSubtitle());
        equal("10", store.getSubscriptionStoreId());
        equal(20L, store.getPersistentId());
        OtherMetadata impostor = new OtherMetadata();
        call("writeCorrectedTrack", impostor, "Wrong", "Wrong artist");
        equal("Stock", impostor.a);
        equal("Stock", impostor.title);
        equal("Stock artist", impostor.b);
        equal("Stock artist", impostor.artistName);
        DerivedMetadata subclass = new DerivedMetadata();
        call("writeCorrectedTrack", subclass, "Wrong", "Wrong artist");
        equal("Stock", subclass.a);
        equal("Stock artist", subclass.b);
        call("writeCorrectedTrack", metadata, null, null);
        call("writeCorrectedTrack", metadata, "", "");
        equal("Corrected", metadata.a);
        equal("Corrected artist", metadata.b);
        call("writeCorrectedTrack", metadata, null, "Artist only");
        equal("Corrected", metadata.a);
        equal("Artist only", metadata.b);
        call("writeCorrectedTrack", store, "", "Artist only");
        equal("Corrected", store.getTitle());
        equal("Artist only", store.getArtistName());
        call("writeCorrectedTrack", null, "Corrected", "Corrected artist");
    }

    private static void testNativeIdentityGuards() throws Exception {
        Handler.reset();
        Fixture fixture = new Fixture("Native stock");
        fixture.settleDirectly("Native corrected");
        z3.w wrongQueue = media(fixture.queue.id + 1L, fixture.id, "Wrong queue stock");
        z3.w wrongCatalog = media(fixture.queue.id, "999999999", "Wrong catalog stock");
        z3.w missingIdentity = new z3.w(new z3.y("Missing identity stock"));
        call("applyTitleCorrection", wrongQueue);
        call("applyTitleCorrection", wrongCatalog);
        call("applyTitleCorrection", missingIdentity);
        equal("Wrong queue stock", wrongQueue.d.a);
        equal("Wrong catalog stock", wrongCatalog.d.a);
        equal("Missing identity stock", missingIdentity.d.a);
        z3.y sameMetadata = fixture.manager.media.d;
        Bundle sameExtras = sameMetadata.J;
        VivoCarLyrics.onNativeMediaItem(fixture.manager.media);
        equal("Native corrected", sameMetadata.a);
        equal("Native corrected", fixture.item.getTitle());
        check(fixture.manager.media.d == sameMetadata && sameMetadata.J == sameExtras,
                "native title must not replace metadata or extras");
        equal(31L, sameExtras.getLong(SUPPORT, 0L));
        equal(fixture.queue.id, sameExtras.getLong(QUEUE, 0L));
        equal(fixture.id, sameExtras.getString(CATALOG));
        equal(0, fixture.manager.republishes);
    }

    private static void testCompatiblePlaybackConverter() throws Exception {
        Handler.reset();
        Fixture fixture = new Fixture("Converter stock", "Converter stock artist");
        O.item = fixture.item;
        O.received = null;
        Q.calls = 0;
        P.calls = 0;
        try {
            check(call("currentPlaybackItem", fixture.manager) == fixture.item,
                    "select the compatible static 1609 O converter");
            check(O.received == fixture.manager.media.d, "converter receives current native metadata");
            equal(0, Q.calls);
            equal(0, P.calls);
            equal(0, fixture.manager.republishes);
            equal(0, Handler.pendingCount());
        } finally {
            O.item = null;
            O.received = null;
        }
    }

    private static void testPlayerBindingAndStaleItems() throws Exception {
        Handler.reset();
        Fixture fixture = new Fixture("UI stock");
        Binding binding = new Binding();
        CharSequence stock = new StringBuilder("UI stock");
        check(VivoCarLyrics.correctPlayerTitle(binding, fixture.item, stock) == stock,
                "unresolved UI keeps stock object");
        StoreMediaItem wrongQueue = new StoreMediaItem(fixture.queue.id + 1L, fixture.id, 1L, "Stale");
        StoreMediaItem wrongCatalog = new StoreMediaItem(fixture.queue.id, "88888888", 1L, "Stale");
        check(VivoCarLyrics.correctPlayerTitle(new Binding(), wrongQueue, stock) == stock,
                "stale UI queue unchanged");
        check(VivoCarLyrics.correctPlayerTitle(new Binding(), wrongCatalog, stock) == stock,
                "stale UI catalog unchanged");
        fixture.settleDirectly("UI corrected");
        call("applyTitleCorrectionFromState", state().snapshot());
        equal(1, binding.rebinds);
        check(binding.item == fixture.item, "same bound item supplied to q0");
        equal("UI corrected", binding.title);
        equal("UI corrected", VivoCarLyrics.correctPlayerTitle(binding, fixture.item, stock));
        equal("Stale", VivoCarLyrics.correctPlayerTitle(new Binding(), wrongQueue, "Stale"));
    }

    private static void testAsyncSwitchAndCache() throws Exception {
        Handler.reset();
        Fixture first = new Fixture("First stock", "First stock artist");
        Binding firstBinding = new Binding();
        VivoCarLyrics.correctPlayerTitle(firstBinding, first.item, "First stock");
        first.request();
        equal(1, first.catalog.calls.size());
        Fixture second = new Fixture("Second stock", "Second stock artist");
        Binding secondBinding = new Binding();
        VivoCarLyrics.correctPlayerTitle(secondBinding, second.item, "Second stock");
        second.request();
        first.catalog.succeed(0, "First corrected", "First corrected artist");
        Handler.drain();
        equal("First stock", first.manager.media.d.a);
        equal("First stock artist", first.manager.media.d.b);
        equal("Second stock", second.manager.media.d.a);
        equal("Second stock artist", second.manager.media.d.b);
        equal("Second stock", second.item.getTitle());
        equal("Second stock artist", second.item.getArtistName());
        equal(0, firstBinding.rebinds);
        equal(0, secondBinding.rebinds);
        equal(0, first.manager.service.notificationRefreshes);
        equal(0, second.manager.service.notificationRefreshes);
        check(state().snapshot().pending, "old callback cannot settle second request");
        second.catalog.succeed(0, "Second corrected", "Second corrected artist");
        Handler.drain();
        equal("Second corrected", second.manager.media.d.a);
        equal("Second corrected", second.item.getTitle());
        equal("Second corrected artist", second.manager.media.d.b);
        equal("Second corrected artist", second.item.getArtistName());
        equal("Second corrected artist", secondBinding.artist);
        equal(1, secondBinding.rebinds);
        equal(1, second.manager.service.notificationRefreshes);
        check(second.manager.service.lastSession == second.manager.a.b,
                "refresh uses current notification session");
        check(!second.manager.service.lastForeground, "refresh is not a foreground transition");
        equal(0, first.manager.republishes);
        equal(0, second.manager.republishes);
        second.request();
        equal(1, second.catalog.calls.size());
        equal(1, second.manager.service.notificationRefreshes);

        first.activate();
        first.request();
        equal(1, first.catalog.calls.size());
        equal("First corrected", first.manager.media.d.a);
        equal("First corrected", first.item.getTitle());
        equal("First corrected artist", first.manager.media.d.b);
        equal("First corrected artist", first.item.getArtistName());
        equal(1, first.manager.service.notificationRefreshes);
    }

    private static void testQueuedWorkAfterSwitch() throws Exception {
        Handler.reset();
        Fixture first = new Fixture("Queued first");
        call("requestTitleCorrection");
        Fixture second = new Fixture("Queued second");
        Handler.drain();
        equal(0, first.catalog.calls.size());
        equal(0, second.catalog.calls.size());
        equal("Queued second", second.manager.media.d.a);

        second.request();
        second.catalog.succeed(0, "Result queued before switch");
        Fixture third = new Fixture("Queued third");
        Handler.drain();
        equal("Queued third", third.manager.media.d.a);
        equal("Queued second", second.manager.media.d.a);
    }

    private static void testFailureAndTimeout() throws Exception {
        Handler.reset();
        Fixture failed = new Fixture("Failure stock");
        failed.request();
        failed.catalog.fail(0);
        Handler.drain();
        check(state().snapshot().completed && !state().snapshot().pending, "failure settles");
        equal("Failure stock", failed.manager.media.d.a);
        failed.activate();
        failed.request();
        equal(2, failed.catalog.calls.size());

        Fixture timeout = new Fixture("Timeout stock");
        timeout.request();
        Handler.advanceBy(15000L);
        check(state().snapshot().completed && !state().snapshot().pending, "timeout settles");
        timeout.catalog.succeed(0, "Too late");
        Handler.drain();
        equal("Timeout stock", timeout.manager.media.d.a);
        timeout.activate();
        timeout.request();
        equal(2, timeout.catalog.calls.size());
    }

    private static void testUnknownQueueEnrichment() throws Exception {
        Handler.reset();
        Fixture fixture = new Fixture("Unknown queue");
        fixture.queue.id = 0L;
        fixture.activate();
        fixture.request();
        equal(0, fixture.catalog.calls.size());
        Queue differentObject = new Queue(fixture.item.getQueueId(), fixture.item);
        call("enrichTitleCorrection", fixture.manager, differentObject);
        equal("", state().snapshot().queueId);
        fixture.queue.id = fixture.item.getQueueId();
        call("enrichTitleCorrection", fixture.manager, fixture.queue);
        equal(String.valueOf(fixture.queue.id), state().snapshot().queueId);
        fixture.request();
        equal(1, fixture.catalog.calls.size());
        fixture.catalog.succeed(0, "Enriched corrected");
        Handler.drain();
        equal("Enriched corrected", fixture.manager.media.d.a);

        Fixture library = new Fixture("Library");
        StoreMediaItem libraryItem = new StoreMediaItem(library.queue.id, "", 123456L, "Library");
        library.queue.item = libraryItem;
        library.activate();
        library.request();
        equal(0, library.catalog.calls.size());
        equal("", state().snapshot().catalogId);
    }

    private static void testResetRejectsLateApplication() throws Exception {
        Handler.reset();
        Fixture fixture = new Fixture("Reset stock");
        Binding binding = new Binding();
        VivoCarLyrics.correctPlayerTitle(binding, fixture.item, "Reset stock");
        fixture.request();
        TitleCorrectionState.Snapshot beforeReset = state().snapshot();
        call("resetTitleCorrection", new Object());
        check(state().matchesSnapshot(beforeReset), "foreign reset preserves title request");
        call("resetTitleCorrection", fixture.manager);
        fixture.catalog.succeed(0, "After reset");
        Handler.drain();
        equal("Reset stock", fixture.manager.media.d.a);
        equal("Reset stock", fixture.item.getTitle());
        equal(0, binding.rebinds);
        equal(0, fixture.manager.service.notificationRefreshes);
        equal("Reset stock", VivoCarLyrics.correctPlayerTitle(binding, fixture.item, "Reset stock"));
        fixture.activate();
        fixture.request();
        equal(1, fixture.catalog.calls.size());
        equal("After reset", fixture.manager.media.d.a);
    }

    private static void testUnavailableCatalogIsBounded() throws Exception {
        Handler.reset();
        Fixture fixture = new Fixture("No catalog");
        MediaApiRepositoryHolder.Companion.setMediaApi(null);
        call("requestTitleCorrection");
        Handler.drain();
        Handler.advanceBy(1200L);
        equal(0, Handler.pendingCount());
        equal("No catalog", fixture.manager.media.d.a);
        MediaApiRepositoryHolder.Companion.setMediaApi(fixture.catalog);
        fixture.request();
        equal(1, fixture.catalog.calls.size());
    }

    private static void testNotificationIdentityGuards() throws Exception {
        Handler.reset();
        Fixture wrongSession = new Fixture("Wrong service session");
        wrongSession.request();
        wrongSession.manager.service.y = new J4.g2(wrongSession.manager, wrongSession.manager.service);
        wrongSession.catalog.succeed(0, "Corrected but not notified");
        Handler.drain();
        equal("Corrected but not notified", wrongSession.manager.media.d.a);
        equal(0, wrongSession.manager.service.notificationRefreshes);

        Fixture wrongManager = new Fixture("Wrong session manager");
        wrongManager.request();
        wrongManager.manager.a.b.manager = new Object();
        wrongManager.catalog.succeed(0, "Corrected without manager match");
        Handler.drain();
        equal(0, wrongManager.manager.service.notificationRefreshes);

        Fixture wrongServiceType = new Fixture("Wrong service type");
        wrongServiceType.request();
        J4.Z2 impostor = new J4.Z2();
        wrongServiceType.manager.a.b.a.f = impostor;
        wrongServiceType.catalog.succeed(0, "Corrected without service match");
        Handler.drain();
        equal(0, impostor.notificationRefreshes);
        equal(0, wrongServiceType.manager.service.notificationRefreshes);

        Fixture wrongMetadata = new Fixture("Wrong native identity");
        wrongMetadata.manager.media.d.J.putString(CATALOG, "999999999");
        wrongMetadata.request();
        wrongMetadata.catalog.succeed(0, "Not native");
        Handler.drain();
        equal("Wrong native identity", wrongMetadata.manager.media.d.a);
        equal(0, wrongMetadata.manager.service.notificationRefreshes);
    }

    private static void testFixedChinaIgnoresSystemLocale() throws Exception {
        Handler.reset();
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.US);
            Fixture fixture = new Fixture("Locale stock");
            fixture.request();
            equal("zh-CN", fixture.catalog.calls.get(0).language);
            equal("cn_v1", state().snapshot().cacheNamespace);
            Locale.setDefault(Locale.SIMPLIFIED_CHINESE);
            fixture.request();
            equal(1, fixture.catalog.calls.size());
            check(state().snapshot().pending, "system language does not restart a China query");
            fixture.catalog.succeed(0, "Chinese localized");
            Handler.drain();
            equal("Chinese localized", fixture.manager.media.d.a);
            equal(Locale.SIMPLIFIED_CHINESE, Locale.getDefault());
            Locale.setDefault(Locale.US);
            fixture.request();
            equal(1, fixture.catalog.calls.size());
            equal("Chinese localized", fixture.manager.media.d.a);
            equal(Locale.US, Locale.getDefault());
            z3.w freshNative = media(fixture.queue.id, fixture.id, "Fresh native stock");
            VivoCarLyrics.onNativeMediaItem(freshNative);
            equal("Chinese localized", freshNative.d.a);
            equal(1, fixture.catalog.calls.size());
        } finally {
            Locale.setDefault(original);
        }
    }

    private static void testPersistedCacheAvoidsCatalog(boolean unavailableApi) throws Exception {
        Handler.reset();
        File directory = openDiskCache();
        try {
            String id = String.valueOf(nextId + 1L);
            check(new TitleCacheStore(directory).putTrack(id, "Persisted China title", "Persisted China artist"),
                    "prepare a disk entry for the next fixture");
            Fixture fixture = new Fixture("Disk stock");
            equal(id, fixture.id);
            Binding binding = new Binding();
            VivoCarLyrics.correctPlayerTitle(binding, fixture.item, "Disk stock");
            if (unavailableApi) MediaApiRepositoryHolder.Companion.setMediaApi(null);
            fixture.request();
            flushTitleIO();
            Handler.drain();
            equal(0, fixture.catalog.calls.size());
            equal("Persisted China title", fixture.manager.media.d.a);
            equal("Persisted China title", fixture.item.getTitle());
            equal("Persisted China title", binding.title);
            equal("Persisted China artist", fixture.manager.media.d.b);
            equal("Persisted China artist", fixture.item.getArtistName());
            equal("Persisted China artist", binding.artist);
            equal(1, binding.rebinds);
            equal(1, fixture.manager.service.notificationRefreshes);
            equal(0, fixture.manager.republishes);
            check(state().snapshot().completed && !state().snapshot().pending,
                    "disk cache settles before any catalog availability check");
            equal(0, Handler.pendingCount());
        } finally {
            closeDiskCache(directory);
        }
    }

    private static void testReplayUsesCachedTitleWithoutCatalog() throws Exception {
        Handler.reset();
        Fixture fixture = new Fixture("English stock");
        fixture.request();
        fixture.catalog.succeed(0, "China title");
        Handler.drain();
        Handler.advanceBy(15000L);
        equal("China title", fixture.item.getTitle());
        fixture.activate();
        MediaApiRepositoryHolder.Companion.setMediaApi(null);
        Binding binding = new Binding();
        VivoCarLyrics.correctPlayerTitle(binding, fixture.item, "English stock");
        fixture.request();
        equal("China title", binding.title);
        equal(1, fixture.catalog.calls.size());
        z3.w fresh = media(fixture.queue.id, fixture.id, "English stock");
        VivoCarLyrics.onNativeMediaItem(fresh);
        equal("China title", fresh.d.a);
        equal(0, Handler.pendingCount());
    }

    private static void testStalePersistedCacheCallback() throws Exception {
        Handler.reset();
        File directory = openDiskCache();
        try {
            String firstId = String.valueOf(nextId + 1L);
            check(new TitleCacheStore(directory).putTrack(firstId, "Old disk title", "Old disk artist"),
                    "prepare the old track's disk entry");
            Fixture first = new Fixture("Old disk stock");
            Binding firstBinding = new Binding();
            VivoCarLyrics.correctPlayerTitle(firstBinding, first.item, "Old disk stock");
            first.request();
            flushTitleIO();
            // The background read has completed, but its main-thread callback is still queued.
            Fixture second = new Fixture("New disk stock");
            Handler.drain();
            equal("Old disk stock", first.manager.media.d.a);
            equal("Old disk stock", first.item.getTitle());
            equal("New disk stock", second.manager.media.d.a);
            equal("New disk stock", second.item.getTitle());
            equal(0, firstBinding.rebinds);
            equal(0, first.manager.service.notificationRefreshes);
            equal(0, second.manager.service.notificationRefreshes);
            equal(0, first.catalog.calls.size());
            equal(0, second.catalog.calls.size());
            equal(second.id, state().snapshot().catalogId);
            check(!state().snapshot().completed && !state().snapshot().pending,
                    "stale disk callback does not settle the new track");
            second.request();
            flushTitleIO();
            Handler.drain();
            equal(1, second.catalog.calls.size());
            equal(second.id, second.catalog.calls.get(0).id);
        } finally {
            closeDiskCache(directory);
        }
    }

    private static void testSuccessfulCatalogResultPersists() throws Exception {
        Handler.reset();
        File directory = openDiskCache();
        try {
            Fixture fixture = new Fixture("Network disk stock");
            fixture.request();
            flushTitleIO();
            Handler.drain();
            equal(1, fixture.catalog.calls.size());
            fixture.catalog.succeed(0, "Persisted network title");
            Handler.drain();
            flushTitleIO();
            equal("Persisted network title", fixture.manager.media.d.a);
            equal("Persisted network title", new TitleCacheStore(directory).get(fixture.id));
            equal("China artist", new TitleCacheStore(directory).getTrack(fixture.id).artist);
            equal(0, fixture.manager.republishes);
        } finally {
            closeDiskCache(directory);
        }
    }

    private static void testCapturedTrackResultPersistsWithoutMixing() throws Exception {
        Handler.reset();
        File directory = openDiskCache();
        try {
            Fixture first = new Fixture("First disk stock", "First disk artist");
            first.request();
            flushTitleIO();
            Handler.drain();
            equal(1, first.catalog.calls.size());
            first.catalog.succeed(0, "First captured title", "First captured artist");
            Fixture second = new Fixture("Second disk stock", "Second disk artist");
            second.request();
            flushTitleIO();
            Handler.drain();
            equal("Second disk stock", second.manager.media.d.a);
            equal("Second disk artist", second.manager.media.d.b);
            equal("Second disk artist", second.item.getArtistName());
            equal(1, second.catalog.calls.size());
            second.catalog.succeed(0, "Second captured title", "Second captured artist");
            Handler.drain();
            flushTitleIO();
            TitleCacheStore persisted = new TitleCacheStore(directory);
            trackEquals("First captured title", "First captured artist", persisted.getTrack(first.id));
            trackEquals("Second captured title", "Second captured artist", persisted.getTrack(second.id));
            equal("First disk stock", first.manager.media.d.a);
            equal("First disk artist", first.manager.media.d.b);
            equal("Second captured artist", second.manager.media.d.b);
            equal("Second captured artist", second.item.getArtistName());
            equal(0, first.manager.republishes);
            equal(0, second.manager.republishes);
        } finally {
            closeDiskCache(directory);
        }
    }

    private static void testLegacyMemoryTitleFetchesArtist() throws Exception {
        Handler.reset();
        Fixture fixture = new Fixture("Legacy memory stock", "Jay Chou");
        state().putCachedTitle(fixture.id, "Cached China title");
        Binding binding = new Binding();
        VivoCarLyrics.correctPlayerTitle(binding, fixture.item, fixture.item.getTitle());
        VivoCarLyrics.correctPlayerArtist(binding, fixture.item, fixture.item.getArtistName());
        fixture.request();
        equal(1, fixture.catalog.calls.size());
        equal("Cached China title", fixture.manager.media.d.a);
        equal("Jay Chou", fixture.manager.media.d.b);
        check(state().snapshot().pending, "legacy memory title does not suppress the artist query");
        fixture.catalog.succeed(0, "Cached China title", "\u5468\u6770\u502b");
        Handler.drain();
        equal("Cached China title", fixture.manager.media.d.a);
        equal("\u5468\u6770\u4f26", fixture.manager.media.d.b);
        equal("\u5468\u6770\u4f26", binding.artist);
        equal(2, binding.rebinds);
        fixture.request();
        equal(1, fixture.catalog.calls.size());
        equal(0, fixture.manager.republishes);
    }

    private static void testLegacyDiskTitleFetchesArtist() throws Exception {
        Handler.reset();
        File directory = openDiskCache();
        try {
            String id = String.valueOf(nextId + 1L);
            check(new TitleCacheStore(directory).put(id, "Legacy disk title"),
                    "prepare a legacy title-only disk entry");
            Fixture fixture = new Fixture("Legacy disk stock", "Jay Chou");
            equal(id, fixture.id);
            Binding binding = new Binding();
            VivoCarLyrics.correctPlayerTitle(binding, fixture.item, fixture.item.getTitle());
            VivoCarLyrics.correctPlayerArtist(binding, fixture.item, fixture.item.getArtistName());
            fixture.request();
            flushTitleIO();
            Handler.drain();
            equal("Legacy disk title", fixture.manager.media.d.a);
            equal("Jay Chou", fixture.manager.media.d.b);
            equal(1, fixture.catalog.calls.size());
            check(state().snapshot().pending, "legacy disk title does not settle artist resolution");
            fixture.catalog.succeed(0, "Legacy disk title", "\u5468\u6770\u502b");
            Handler.drain();
            flushTitleIO();
            trackEquals("Legacy disk title", "\u5468\u6770\u4f26",
                    new TitleCacheStore(directory).getTrack(id));
            equal("\u5468\u6770\u4f26", fixture.item.getNowPlayingSubtitle());
            equal("\u5468\u6770\u4f26", binding.artist);
            equal(2, binding.rebinds);
            equal(0, fixture.manager.republishes);
        } finally {
            closeDiskCache(directory);
        }
    }

    private static void testArtistOnlyPlayerChange() throws Exception {
        Handler.reset();
        Fixture fixture = new Fixture("Identical title", "Jay Chou");
        Binding binding = new Binding();
        CharSequence artist = new StringBuilder("Jay Chou");
        check(VivoCarLyrics.correctPlayerArtist(binding, fixture.item, artist) == artist,
                "unresolved artist keeps the stock object");
        StoreMediaItem stale = new StoreMediaItem(fixture.queue.id + 1L, fixture.id, 1L,
                "Identical title", "Wrong artist");
        check(VivoCarLyrics.correctPlayerArtist(new Binding(), stale, artist) == artist,
                "foreign artist binding keeps the stock object");
        fixture.request();
        fixture.catalog.succeed(0, "Identical title", "\u5468\u6770\u502b");
        Handler.drain();
        equal("Identical title", fixture.manager.media.d.a);
        equal("\u5468\u6770\u4f26", fixture.manager.media.d.b);
        equal("\u5468\u6770\u4f26", fixture.item.getNowPlayingSubtitle());
        equal("Identical title", binding.title);
        equal("\u5468\u6770\u4f26", binding.artist);
        equal(1, binding.rebinds);
        equal(1, fixture.manager.service.notificationRefreshes);
        equal(0, fixture.manager.republishes);
        call("applyTitleCorrectionFromState", state().snapshot());
        equal(1, binding.rebinds);
        equal(1, fixture.manager.service.notificationRefreshes);
    }

    private static void testResolvedEmptyArtistDoesNotRetry() throws Exception {
        Handler.reset();
        Fixture fixture = new Fixture("Empty artist stock", "Stock artist remains");
        fixture.request();
        fixture.catalog.succeed(0, "Resolved title", "");
        Handler.drain();
        equal("Resolved title", fixture.manager.media.d.a);
        equal("Stock artist remains", fixture.manager.media.d.b);
        equal("Stock artist remains", fixture.item.getArtistName());
        check(state().snapshot().artistResolved, "queried empty artist is resolved, not a legacy miss");
        fixture.activate();
        fixture.request();
        equal(1, fixture.catalog.calls.size());
        equal("Stock artist remains", VivoCarLyrics.correctPlayerArtist(
                new Binding(), fixture.item, "Stock artist remains"));
        equal(0, fixture.manager.republishes);
    }

    private static void testArtistPrefetchRefreshesCurrentTrack() throws Exception {
        Handler.reset();
        Fixture fixture = new Fixture("Prefetch unchanged title", "Jay Chou");
        Binding binding = new Binding();
        VivoCarLyrics.correctPlayerArtist(binding, fixture.item, fixture.item.getNowPlayingSubtitle());
        long generation = state().snapshot().generation;
        call("queryCatalogBatch", Collections.singletonList(fixture.id));
        Handler.drain();
        equal(1, fixture.catalog.calls.size());
        fixture.catalog.succeed(0, "Prefetch unchanged title", "\u5468\u6770\u502b");
        Handler.drain();
        equal("\u5468\u6770\u4f26", fixture.manager.media.d.b);
        equal("\u5468\u6770\u4f26", binding.artist);
        equal(1, binding.rebinds);
        equal(1, fixture.manager.service.notificationRefreshes);
        equal(generation, state().snapshot().generation);
        equal(0, fixture.manager.republishes);

        call("queryCatalogBatch", Collections.singletonList(fixture.id));
        Handler.drain();
        fixture.catalog.succeed(1, "Prefetch unchanged title", "\u5b6b\u71d5\u59ff");
        Handler.drain();
        equal("\u5b59\u71d5\u59ff", fixture.manager.media.d.b);
        equal("\u5b59\u71d5\u59ff", binding.artist);
        equal(2, binding.rebinds);
        equal(2, fixture.manager.service.notificationRefreshes);
        equal(generation, state().snapshot().generation);

        call("queryCatalogBatch", Collections.singletonList(fixture.id));
        Handler.drain();
        fixture.catalog.succeed(2, "Prefetch unchanged title", "\u5b6b\u71d5\u59ff");
        Handler.drain();
        equal(2, binding.rebinds);
        equal(2, fixture.manager.service.notificationRefreshes);
        equal(0, fixture.manager.republishes);
    }

    private static void testIndependentPlaylistArtist() throws Exception {
        Handler.reset();
        String id = String.valueOf(++nextId);
        state().putCachedTrack(id, "", "\u5468\u6770\u4f26");
        PlaylistEntity entity = new PlaylistEntity(id, "Uncached stock title", "Jay Chou");
        PlaylistController controller = new PlaylistController();
        VivoCarLyrics.onPlaylistTrack(controller, entity);
        equal("Uncached stock title", entity.attributes.getName());
        equal("\u5468\u6770\u4f26", entity.attributes.getArtistName());
        equal("\u5468\u6770\u4f26", VivoCarLyrics.resolveItemArtist(entity, "Jay Chou"));
        equal(null, state().getCachedTitle(id));
    }

    private static void testOlderBatchCannotOverwriteNewSingleTrack() throws Exception {
        Handler.reset();
        File directory = openDiskCache();
        try {
            Fixture fixture = new Fixture("Race stock title", "Race stock artist");
            Binding binding = new Binding();
            VivoCarLyrics.correctPlayerTitle(binding, fixture.item, fixture.item.getTitle());
            VivoCarLyrics.correctPlayerArtist(binding, fixture.item, fixture.item.getArtistName());
            call("queryCatalogBatch", Collections.singletonList(fixture.id));
            Handler.drain();
            equal(1, fixture.catalog.calls.size());
            fixture.request();
            flushTitleIO();
            Handler.drain();
            equal(2, fixture.catalog.calls.size());
            fixture.catalog.succeed(1, "New single title", "New single artist");
            Handler.drain();
            flushTitleIO();
            trackEquals("New single title", "New single artist",
                    new TitleCacheStore(directory).getTrack(fixture.id));
            equal(1, binding.rebinds);
            equal(1, fixture.manager.service.notificationRefreshes);

            fixture.catalog.succeed(0, "Old batch title", "Old batch artist");
            Handler.drain();
            flushTitleIO();
            equal("New single title", fixture.manager.media.d.a);
            equal("New single artist", fixture.manager.media.d.b);
            equal("New single artist", fixture.item.getArtistName());
            equal("New single title", binding.title);
            equal("New single artist", binding.artist);
            trackEquals("New single title", "New single artist", state().getCachedTrack(fixture.id));
            trackEquals("New single title", "New single artist",
                    new TitleCacheStore(directory).getTrack(fixture.id));
            equal(1, binding.rebinds);
            equal(1, fixture.manager.service.notificationRefreshes);
            equal(0, fixture.manager.republishes);
        } finally {
            closeDiskCache(directory);
        }
    }

    private static void testPrefetchTimeoutAllowsRetryWithoutLateArtist() throws Exception {
        Handler.reset();
        File directory = openDiskCache();
        try {
            Fixture fixture = new Fixture("Prefetch timeout stock", "Prefetch timeout artist");
            Binding binding = new Binding();
            VivoCarLyrics.correctPlayerTitle(binding, fixture.item, fixture.item.getTitle());
            VivoCarLyrics.correctPlayerArtist(binding, fixture.item, fixture.item.getArtistName());
            VivoCarLyrics.enqueuePrefetch(fixture.id);
            Handler.advanceBy(150L);
            flushTitleIO();
            Handler.drain();
            equal(1, fixture.catalog.calls.size());
            Handler.advanceBy(15000L);
            equal("Prefetch timeout artist", fixture.manager.media.d.b);
            equal(0, binding.rebinds);
            VivoCarLyrics.enqueuePrefetch(fixture.id);
            Handler.advanceBy(150L);
            flushTitleIO();
            Handler.drain();
            equal(2, fixture.catalog.calls.size());
            fixture.catalog.succeed(1, "Retried prefetch title", "Retried prefetch artist");
            Handler.drain();
            flushTitleIO();
            equal("Retried prefetch title", binding.title);
            equal("Retried prefetch artist", binding.artist);
            equal(1, binding.rebinds);
            equal(1, fixture.manager.service.notificationRefreshes);

            fixture.catalog.succeed(0, "Expired prefetch title", "Expired prefetch artist");
            Handler.drain();
            flushTitleIO();
            equal("Retried prefetch title", fixture.manager.media.d.a);
            equal("Retried prefetch artist", fixture.manager.media.d.b);
            equal("Retried prefetch artist", fixture.item.getArtistName());
            equal("Retried prefetch artist", binding.artist);
            trackEquals("Retried prefetch title", "Retried prefetch artist",
                    new TitleCacheStore(directory).getTrack(fixture.id));
            equal(1, binding.rebinds);
            equal(1, fixture.manager.service.notificationRefreshes);
            equal(0, fixture.manager.republishes);
        } finally {
            closeDiskCache(directory);
        }
    }

    private static void testRegionalFallbackKeepsOriginalDeadline() throws Exception {
        for (boolean miss : new boolean[]{true, false}) {
            Handler.reset();
            File directory = openDiskCache();
            try {
                Fixture fixture = new Fixture("Fallback deadline stock", "Fallback stock artist");
                Binding binding = new Binding();
                VivoCarLyrics.correctPlayerTitle(binding, fixture.item, fixture.item.getTitle());
                VivoCarLyrics.correctPlayerArtist(binding, fixture.item, fixture.item.getArtistName());
                fixture.request();
                flushTitleIO();
                Handler.drain();
                equal(1, fixture.catalog.calls.size());
                Handler.advanceBy(14000L);
                fixture.catalog.miss(0);
                Handler.drain();
                equal(2, fixture.catalog.calls.size());
                equal(null, fixture.catalog.calls.get(1).language);
                fixture.catalog.miss(1);
                Handler.drain();
                equal(3, fixture.catalog.calls.size());
                equal("zh-CN", fixture.catalog.calls.get(2).language);

                Handler.advanceBy(1000L);
                check(state().snapshot().completed && !state().snapshot().pending,
                        "the original single-track 15-second deadline settles the fallback");
                if (miss) fixture.catalog.miss(2);
                else fixture.catalog.succeed(2, "Late fallback title", "Late fallback artist");
                Handler.drain();
                flushTitleIO();
                equal(3, fixture.catalog.calls.size());
                equal(3, fixture.catalog.invocations);
                equal("Fallback deadline stock", fixture.manager.media.d.a);
                equal("Fallback stock artist", fixture.manager.media.d.b);
                equal("Fallback stock artist", fixture.item.getArtistName());
                equal(null, new TitleCacheStore(directory).getTrack(fixture.id));
                equal(0, binding.rebinds);
                equal(0, fixture.manager.service.notificationRefreshes);
                equal(0, fixture.manager.republishes);
            } finally {
                closeDiskCache(directory);
            }
        }
    }

    private static void testBatchCompletionOrderDoesNotRegressTrack() throws Exception {
        Handler.reset();
        File directory = openDiskCache();
        try {
            Fixture fixture = new Fixture("Batch race stock", "Batch race stock artist");
            Binding binding = new Binding();
            VivoCarLyrics.correctPlayerTitle(binding, fixture.item, fixture.item.getTitle());
            VivoCarLyrics.correctPlayerArtist(binding, fixture.item, fixture.item.getArtistName());
            long generation = state().snapshot().generation;
            call("queryCatalogBatch", Collections.singletonList(fixture.id));
            Handler.drain();
            call("queryCatalogBatch", Collections.singletonList(fixture.id));
            Handler.drain();
            equal(2, fixture.catalog.calls.size());
            fixture.catalog.succeed(1, "New batch title", "New batch artist");
            Handler.drain();
            flushTitleIO();
            equal(1, binding.rebinds);
            equal(1, fixture.manager.service.notificationRefreshes);

            fixture.catalog.succeed(0, "Old batch title", "Old batch artist");
            Handler.drain();
            flushTitleIO();
            equal("New batch title", fixture.manager.media.d.a);
            equal("New batch artist", fixture.manager.media.d.b);
            equal("New batch artist", fixture.item.getArtistName());
            equal("New batch title", binding.title);
            equal("New batch artist", binding.artist);
            trackEquals("New batch title", "New batch artist", state().getCachedTrack(fixture.id));
            trackEquals("New batch title", "New batch artist",
                    new TitleCacheStore(directory).getTrack(fixture.id));
            equal(1, binding.rebinds);
            equal(1, fixture.manager.service.notificationRefreshes);
            equal(generation, state().snapshot().generation);

            call("queryCatalogBatch", Collections.singletonList(fixture.id));
            Handler.drain();
            fixture.catalog.succeed(2, "Later batch title", "Later batch artist");
            Handler.drain();
            flushTitleIO();
            equal("Later batch title", fixture.manager.media.d.a);
            equal("Later batch artist", fixture.manager.media.d.b);
            equal("Later batch artist", binding.artist);
            trackEquals("Later batch title", "Later batch artist",
                    new TitleCacheStore(directory).getTrack(fixture.id));
            equal(2, binding.rebinds);
            equal(2, fixture.manager.service.notificationRefreshes);
            equal(generation, state().snapshot().generation);
            equal(0, fixture.manager.republishes);
        } finally {
            closeDiskCache(directory);
        }
    }

    private static void testFormattedPlayerArtistPreservesStock() throws Exception {
        for (boolean composer : new boolean[]{true, false}) {
            Handler.reset();
            Fixture fixture = new Fixture("Formatted stock title", "Stock performer");
            String formatted = composer ? "Ludwig van Beethoven / Piano Sonata"
                    : "Live radio / KCRW 89.9";
            fixture.item.setShowComposer(composer);
            fixture.item.setContentType(composer ? 0 : 9);
            fixture.item.setFormattedSubtitle(formatted);
            Binding binding = new Binding();
            VivoCarLyrics.correctPlayerTitle(binding, fixture.item, fixture.item.getTitle());
            CharSequence stock = new StringBuilder(formatted);
            check(VivoCarLyrics.correctPlayerArtist(binding, fixture.item, stock) == stock,
                    "formatted subtitle keeps its original object before lookup");
            fixture.request();
            fixture.catalog.succeed(0, "Localized title", "Localized performer");
            Handler.drain();
            equal("Localized title", binding.title);
            equal(formatted, binding.artist);
            equal(formatted, fixture.item.getNowPlayingSubtitle());
            equal("Localized performer", fixture.item.getArtistName());
            check(VivoCarLyrics.correctPlayerArtist(binding, fixture.item, stock) == stock,
                    "composer or radio subtitle is not replaced by the performer");
            equal(1, binding.rebinds);
            equal(0, fixture.manager.republishes);
        }
    }

    private static File openDiskCache() throws Exception {
        flushTitleIO();
        File directory = Files.createTempDirectory("vivo-title-integration-").toFile();
        set("titleCacheStore", null);
        AppleMusicApplication.setFilesDirectory(directory);
        return directory;
    }

    private static void closeDiskCache(File directory) throws Exception {
        flushTitleIO();
        Handler.reset();
        AppleMusicApplication.setFilesDirectory(null);
        set("titleCacheStore", null);
        File[] children = directory.listFiles();
        if (children != null) {
            for (File child : children) check(child.delete(), "remove temporary cache file");
        }
        check(directory.delete(), "remove temporary cache directory");
    }

    private static void flushTitleIO() throws Exception {
        Field field = VivoCarLyrics.class.getDeclaredField("TITLE_IO");
        field.setAccessible(true);
        ExecutorService executor = (ExecutorService) field.get(null);
        executor.submit(new Runnable() {
            @Override public void run() {}
        }).get(5L, TimeUnit.SECONDS);
    }

    private static final class Fixture {
        final String id = String.valueOf(++nextId);
        final StoreMediaItem item;
        final Queue queue;
        final Manager manager;
        final AsyncCatalog catalog = new AsyncCatalog();
        Fixture(String title) throws Exception {
            this(title, "Stock artist");
        }
        Fixture(String title, String artist) throws Exception {
            long queueId = ++nextId;
            item = new StoreMediaItem(queueId, id, queueId + 100000L, title, artist);
            queue = new Queue(queueId, item);
            manager = new Manager(media(queueId, id, title, artist));
            activate();
        }
        void activate() throws Exception {
            set("currentManager", manager);
            set("currentQueueItem", queue);
            MediaApiRepositoryHolder.Companion.setMediaApi(catalog);
            call("beginTitleCorrection", manager, queue);
        }
        void request() throws Exception {
            call("requestTitleCorrection");
            Handler.drain();
        }
        void settleDirectly(String title) throws Exception {
            settleDirectly(title, "China artist");
        }
        void settleDirectly(String title, String artist) throws Exception {
            check(state().completeTrack(state().request(), title, artist),
                    "direct result belongs to current fixture");
        }
    }

    public static final class Queue {
        long id;
        StoreMediaItem item;
        Queue(long id, StoreMediaItem item) { this.id = id; this.item = item; }
        public long getPlaybackQueueId() { return id; }
        public StoreMediaItem getItem() { return item; }
    }

    public static final class Manager {
        final z3.w media;
        final SessionHolder a;
        final MediaPlaybackService service = new MediaPlaybackService();
        int republishes;
        Manager(z3.w media) {
            this.media = media;
            a = new SessionHolder(new J4.g2(this, service));
            service.y = a.b;
        }
        public z3.w c() { return media; }
        public void P(Object mediaItem, int reason) { republishes++; }
        public void I(Object metadata) { republishes++; }
    }

    public static final class SessionHolder {
        public final J4.g2 b;
        SessionHolder(J4.g2 session) { b = session; }
    }

    public static final class Binding {
        int rebinds;
        Object item;
        CharSequence title;
        CharSequence artist;
        public void q0(Object value) {
            rebinds++;
            item = value;
            title = VivoCarLyrics.correctPlayerTitle(this, value,
                    ((StoreMediaItem) value).getTitle());
            artist = VivoCarLyrics.correctPlayerArtist(this, value,
                    ((StoreMediaItem) value).getNowPlayingSubtitle());
        }
    }

    public static final class PlaylistEntity {
        final String id;
        final PlaylistAttributes attributes;
        PlaylistEntity(String id, String title, String artist) {
            this.id = id;
            attributes = new PlaylistAttributes(title, artist);
        }
        public String catalogId() { return id; }
        public PlaylistAttributes getAttributes() { return attributes; }
    }

    public static final class PlaylistAttributes {
        String title;
        String artist;
        PlaylistAttributes(String title, String artist) { this.title = title; this.artist = artist; }
        public String getName() { return title; }
        public void setName(String value) { title = value; }
        public String getArtistName() { return artist; }
        public void setArtistName(String value) { artist = value; }
    }

    public static final class PlaylistController {
        int rebuilds;
        public void requestModelBuild() { rebuilds++; }
    }

    public static final class AsyncCatalog {
        final List<Call> calls = new ArrayList<Call>();
        int invocations;
        public Object F(String path, Map<?, ?> parameters, Continuation continuation) {
            invocations++;
            equal("songs", path);
            if (parameters.containsKey(CatalogTitleResolver.REQUEST_TOKEN_PARAMETER)) {
                Map<Object, Object> fresh = new LinkedHashMap<Object, Object>(parameters);
                fresh.put("l", Locale.getDefault().toLanguageTag());
                LinkedHashMap<Object, Object> headers = new LinkedHashMap<Object, Object>();
                equal("/v1/catalog/cn/songs", CatalogTitleResolver.correctCatalogRequest(
                        "/v1/catalog/us/songs", headers, fresh));
                equal("zh-Hans", headers.get("Accept-Language"));
                equal("143465", headers.get("X-Apple-Store-Front"));
                check(!fresh.containsKey(CatalogTitleResolver.REQUEST_TOKEN_PARAMETER),
                        "module token does not leave final HTTP adapter");
                parameters = fresh;
                check(parameters.get("l") instanceof String, "regional request has a language");
            } else {
                equal(null, parameters.get("l"));
                equal(null, parameters.get("include[songs]"));
            }
            check(parameters.get("ids") instanceof String, "request has catalog ID");
            equal("android", parameters.get("platform"));
            calls.add(new Call((String) parameters.get("ids"),
                    (String) parameters.get("l"), continuation));
            return Suspended.COROUTINE_SUSPENDED;
        }
        void succeed(int index, String title) {
            succeed(index, title, "China artist");
        }
        void succeed(int index, String title, String artist) {
            Call call = calls.get(index);
            Map<String, Object> entity = new LinkedHashMap<String, Object>();
            entity.put("id", call.id);
            entity.put("type", "songs");
            Map<String, String> attributes = new LinkedHashMap<String, String>();
            attributes.put("name", title);
            attributes.put("artistName", artist);
            entity.put("attributes", attributes);
            call.continuation.resumeWith(Collections.singletonMap("data",
                    Collections.singletonList(entity)));
        }
        void fail(int index) {
            calls.get(index).continuation.resumeWith(
                    new kotlin.Result.Failure(new IllegalStateException("temporary failure")));
        }
        void miss(int index) {
            calls.get(index).continuation.resumeWith(Collections.singletonMap("data",
                    Collections.emptyList()));
        }
    }

    private static final class Call {
        final String id;
        final String language;
        final Continuation continuation;
        Call(String id, String language, Continuation continuation) {
            this.id = id;
            this.language = language;
            this.continuation = continuation;
        }
    }
    private enum Suspended { COROUTINE_SUSPENDED }
    public static final class OtherMetadata {
        public CharSequence a = "Stock";
        public CharSequence b = "Stock artist";
        public String title = "Stock";
        public String artistName = "Stock artist";
    }
    public static final class DerivedMetadata extends z3.y {
        DerivedMetadata() { super("Stock"); }
    }

    private static z3.w media(long queueId, String catalogId, String title) {
        return media(queueId, catalogId, title, "Stock artist");
    }
    private static z3.w media(long queueId, String catalogId, String title, String artist) {
        z3.y metadata = new z3.y(title, artist);
        metadata.J.putLong(QUEUE, queueId);
        metadata.J.putString(CATALOG, catalogId);
        return new z3.w(metadata);
    }
    private static TitleCorrectionState state() throws Exception {
        Field field = VivoCarLyrics.class.getDeclaredField("TITLE_STATE");
        field.setAccessible(true);
        return (TitleCorrectionState) field.get(null);
    }
    private static void trackEquals(String title, String artist, TitleCorrectionState.TrackInfo info) {
        check(info != null, "cached track exists");
        equal(title, info.title);
        equal(artist, info.artist);
        check(info.artistResolved, "cached artist was resolved");
    }
    private static void set(String name, Object value) throws Exception {
        Field field = VivoCarLyrics.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }
    private static Object call(String name, Object... arguments) throws Exception {
        for (Method method : VivoCarLyrics.class.getDeclaredMethods()) {
            if (name.equals(method.getName()) && method.getParameterTypes().length == arguments.length) {
                method.setAccessible(true);
                return method.invoke(null, arguments);
            }
        }
        throw new AssertionError("Missing helper method " + name);
    }
    private static void equal(Object expected, Object actual) {
        check(expected == null ? actual == null : expected.equals(actual),
                "expected <" + expected + "> but was <" + actual + ">");
    }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
