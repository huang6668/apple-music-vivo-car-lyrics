package com.apple.android.music.player;

import android.os.Bundle;
import android.os.Handler;
import com.apple.android.music.mediaapi.repository.MediaApiRepositoryHolder;
import com.apple.android.music.playback.model.StoreMediaItem;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import kotlin.coroutines.Continuation;

public final class VivoTitleIntegrationTest {
    private static final String QUEUE = "com.apple.android.music.playback.metadata.ITEM_QUEUE_ID";
    private static final String CATALOG = "com.apple.android.music.playback.metadata.METADATA_KEY_MEDIA_ID";
    private static final String SUPPORT = "vivomusicmix.media.metadata.support_event";
    private static long nextId = 5000L;

    public static void main(String[] args) throws Exception {
        testWhitelistedFieldWrites();
        testNativeIdentityGuards();
        testPlayerBindingAndStaleItems();
        testAsyncSwitchAndCache();
        testQueuedWorkAfterSwitch();
        testFailureAndTimeout();
        testResetRejectsLateApplication();
        testUnknownQueueEnrichment();
        testUnavailableCatalogIsBounded();
        testNotificationIdentityGuards();
        testLocaleNamespaceSwitch();
        Handler.reset();
        System.out.println("Vivo title integration tests passed");
    }

    private static void testWhitelistedFieldWrites() throws Exception {
        z3.x metadata = new z3.x("Stock");
        Object untouched = metadata.untouched;
        Bundle extras = metadata.J;
        call("writeCorrectedTitle", metadata, "Corrected");
        equal("Corrected", metadata.a);
        check(metadata.J == extras && metadata.untouched == untouched, "only metadata title changed");
        StoreMediaItem store = new StoreMediaItem(1L, "10", 20L, "Stock");
        call("writeCorrectedTitle", store, "Corrected");
        equal("Corrected", store.getTitle());
        equal("10", store.getSubscriptionStoreId());
        equal(20L, store.getPersistentId());
        OtherMetadata impostor = new OtherMetadata();
        call("writeCorrectedTitle", impostor, "Wrong");
        equal("Stock", impostor.a);
        equal("Stock", impostor.title);
        DerivedMetadata subclass = new DerivedMetadata();
        call("writeCorrectedTitle", subclass, "Wrong");
        equal("Stock", subclass.a);
        call("writeCorrectedTitle", metadata, null);
        call("writeCorrectedTitle", metadata, "");
        equal("Corrected", metadata.a);
        call("writeCorrectedTitle", null, "Corrected");
    }

    private static void testNativeIdentityGuards() throws Exception {
        Handler.reset();
        Fixture fixture = new Fixture("Native stock");
        fixture.settleDirectly("Native corrected");
        z3.v wrongQueue = media(fixture.queue.id + 1L, fixture.id, "Wrong queue stock");
        z3.v wrongCatalog = media(fixture.queue.id, "999999999", "Wrong catalog stock");
        z3.v missingIdentity = new z3.v(new z3.x("Missing identity stock"));
        call("applyTitleCorrection", wrongQueue);
        call("applyTitleCorrection", wrongCatalog);
        call("applyTitleCorrection", missingIdentity);
        equal("Wrong queue stock", wrongQueue.d.a);
        equal("Wrong catalog stock", wrongCatalog.d.a);
        equal("Missing identity stock", missingIdentity.d.a);
        z3.x sameMetadata = fixture.manager.media.d;
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
        Fixture first = new Fixture("First stock");
        Binding firstBinding = new Binding();
        VivoCarLyrics.correctPlayerTitle(firstBinding, first.item, "First stock");
        first.request();
        equal(1, first.catalog.calls.size());
        Fixture second = new Fixture("Second stock");
        Binding secondBinding = new Binding();
        VivoCarLyrics.correctPlayerTitle(secondBinding, second.item, "Second stock");
        second.request();
        first.catalog.succeed(0, "First corrected");
        Handler.drain();
        equal("First stock", first.manager.media.d.a);
        equal("Second stock", second.manager.media.d.a);
        equal("Second stock", second.item.getTitle());
        equal(0, firstBinding.rebinds);
        equal(0, secondBinding.rebinds);
        equal(0, first.manager.service.notificationRefreshes);
        equal(0, second.manager.service.notificationRefreshes);
        check(state().snapshot().pending, "old callback cannot settle second request");
        second.catalog.succeed(0, "Second corrected");
        Handler.drain();
        equal("Second corrected", second.manager.media.d.a);
        equal("Second corrected", second.item.getTitle());
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
        wrongSession.manager.service.y = new J4.f2(wrongSession.manager, wrongSession.manager.service);
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
        J4.Y2 impostor = new J4.Y2();
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

    private static void testLocaleNamespaceSwitch() throws Exception {
        Handler.reset();
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.US);
            Fixture fixture = new Fixture("Locale stock");
            fixture.request();
            equal("en-US", fixture.catalog.calls.get(0).language);
            Locale.setDefault(Locale.SIMPLIFIED_CHINESE);
            fixture.request();
            equal(2, fixture.catalog.calls.size());
            equal("zh-CN", fixture.catalog.calls.get(1).language);
            fixture.catalog.succeed(0, "English localized");
            Handler.drain();
            equal("Locale stock", fixture.manager.media.d.a);
            check(state().snapshot().pending, "old locale callback cannot settle new locale query");
            fixture.catalog.succeed(1, "Chinese localized");
            Handler.drain();
            equal("Chinese localized", fixture.manager.media.d.a);
            equal(Locale.SIMPLIFIED_CHINESE, Locale.getDefault());
            Locale.setDefault(Locale.US);
            fixture.request();
            equal(3, fixture.catalog.calls.size());
            equal("en-US", fixture.catalog.calls.get(2).language);
            equal("Chinese localized", fixture.manager.media.d.a);
            check(state().snapshot().pending, "discarded locale result must be queried again");
            fixture.catalog.succeed(2, "Fresh English localized");
            Handler.drain();
            equal("Fresh English localized", fixture.manager.media.d.a);
            equal(Locale.US, Locale.getDefault());

            Fixture settled = new Fixture("Settled locale stock");
            settled.request();
            settled.catalog.succeed(0, "Settled English");
            Handler.drain();
            equal("Settled English", settled.manager.media.d.a);
            Locale.setDefault(Locale.SIMPLIFIED_CHINESE);
            z3.v freshNative = media(settled.queue.id, settled.id, "Fresh native stock");
            VivoCarLyrics.onNativeMediaItem(freshNative);
            equal("Fresh native stock", freshNative.d.a);
            equal(1, settled.catalog.calls.size());
            settled.request();
            equal(2, settled.catalog.calls.size());
            equal("zh-CN", settled.catalog.calls.get(1).language);
            settled.catalog.succeed(1, "Settled Chinese");
            Handler.drain();
            equal("Settled Chinese", settled.manager.media.d.a);
            VivoCarLyrics.onNativeMediaItem(freshNative);
            equal("Settled Chinese", freshNative.d.a);
        } finally {
            Locale.setDefault(original);
        }
    }

    private static final class Fixture {
        final String id = String.valueOf(++nextId);
        final StoreMediaItem item;
        final Queue queue;
        final Manager manager;
        final AsyncCatalog catalog = new AsyncCatalog();
        Fixture(String title) throws Exception {
            long queueId = ++nextId;
            item = new StoreMediaItem(queueId, id, queueId + 100000L, title);
            queue = new Queue(queueId, item);
            manager = new Manager(media(queueId, id, title));
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
            check(state().complete(state().request(), title), "direct result belongs to current fixture");
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
        final z3.v media;
        final SessionHolder a;
        final MediaPlaybackService service = new MediaPlaybackService();
        int republishes;
        Manager(z3.v media) {
            this.media = media;
            a = new SessionHolder(new J4.f2(this, service));
            service.y = a.b;
        }
        public z3.v c() { return media; }
        public void P(Object mediaItem, int reason) { republishes++; }
        public void I(Object metadata) { republishes++; }
    }

    public static final class SessionHolder {
        public final J4.f2 b;
        SessionHolder(J4.f2 session) { b = session; }
    }

    public static final class Binding {
        int rebinds;
        Object item;
        CharSequence title;
        public void q0(Object value) {
            rebinds++;
            item = value;
            title = VivoCarLyrics.correctPlayerTitle(this, value,
                    ((StoreMediaItem) value).getTitle());
        }
    }

    public static final class AsyncCatalog {
        final List<Call> calls = new ArrayList<Call>();
        public Object F(String path, Map<?, ?> parameters, Continuation continuation) {
            equal("songs", path);
            check(parameters.get("ids") instanceof String, "request has catalog ID");
            check(parameters.get("l") instanceof String, "request has language");
            equal("android", parameters.get("platform"));
            calls.add(new Call((String) parameters.get("ids"),
                    (String) parameters.get("l"), continuation));
            return Suspended.COROUTINE_SUSPENDED;
        }
        void succeed(int index, String title) {
            Call call = calls.get(index);
            Map<String, Object> entity = new LinkedHashMap<String, Object>();
            entity.put("id", call.id);
            entity.put("type", "songs");
            entity.put("attributes", Collections.singletonMap("name", title));
            call.continuation.resumeWith(Collections.singletonMap("data",
                    Collections.singletonList(entity)));
        }
        void fail(int index) {
            calls.get(index).continuation.resumeWith(
                    new kotlin.Result.Failure(new IllegalStateException("temporary failure")));
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
        public String title = "Stock";
    }
    public static final class DerivedMetadata extends z3.x {
        DerivedMetadata() { super("Stock"); }
    }

    private static z3.v media(long queueId, String catalogId, String title) {
        z3.x metadata = new z3.x(title);
        metadata.J.putLong(QUEUE, queueId);
        metadata.J.putString(CATALOG, catalogId);
        return new z3.v(metadata);
    }
    private static TitleCorrectionState state() throws Exception {
        Field field = VivoCarLyrics.class.getDeclaredField("TITLE_STATE");
        field.setAccessible(true);
        return (TitleCorrectionState) field.get(null);
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
