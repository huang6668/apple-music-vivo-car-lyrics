package com.apple.android.music.player;

import android.app.Application;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Html;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class VivoCarLyrics {
    private static final String BUILD_MARKER = "vivo-car-atomic-diag-off-r35-2026-09-02";
    private static final String META_LINE = "ucar.media.metadata.LYRICS_LINE";
    private static final String META_WHOLE = "ucar.media.metadata.LYRICS_WHOLE";
    private static final String META_STATUS = "ucar.media.metadata.LYRICS_STATUS";
    private static final String EXTRA_LINE = "music.media.extras.LYRIC";
    private static final String EXTRA_ALLOWED = "music.media.extras.LYRIC_IS_ALLOWED";
    private static final String EXTRA_NOTICE = "music.media.extras.NOTICE_CAR";
    private static final String ATOMIC_ACTION_KEY = "vivomusicmix.meida.extra.key.action";
    private static final String ATOMIC_LRC_CHANGE = "vivomusicmix.extra.lrc_change";
    private static final String ATOMIC_MEDIA_ID = "vivomusicmix.extra.key.meidia_id";
    private static final String ATOMIC_LYRIC = "vivomusicmix.extra.key.lyric";
    private static final String ATOMIC_SUPPORT_EVENTS = "vivomusicmix.media.metadata.support_event";
    private static final String PUBLIC_DURATION = "android.media.metadata.DURATION";
    private static final String ATOMIC_CONTROLLER_PACKAGE = "com.vivo.musicwidgetmix";
    private static final String PUBLIC_MEDIA_ID = "android.media.metadata.MEDIA_ID";
    private static final String APPLE_MEDIA_ID = "com.apple.android.music.playback.metadata.METADATA_KEY_MEDIA_ID";
    private static final String APPLE_QUEUE_ID = "com.apple.android.music.playback.metadata.ITEM_QUEUE_ID";

    private static final int STATUS_SUCCESS = 0;
    private static final int STATUS_NO_LYRICS = 1;
    private static final int STATUS_LOADING = 2;
    private static final int STATUS_FAILED = 3;
    private static final int MAX_LOAD_ATTEMPTS = 40;
    private static final long LOAD_RETRY_MS = 150L;
    private static final long LYRICS_RESULT_TIMEOUT_MS = 15000L;
    private static final long LINE_POLL_MS = 250L;
    private static final long[] METADATA_REAPPLY_DELAYS_MS = {150L, 600L, 1500L, 3000L};
    private static final long[] ATOMIC_REPLAY_DELAYS_MS = {1000L, 2000L, 4000L, 8000L, 15000L};
    private static final long[] ATOMIC_CONNECT_REPLAY_DELAYS_MS = {150L, 1000L, 2000L};
    private static final long ATOMIC_KEEPALIVE_MS = 25000L;
    private static final long ATOMIC_ACTION_CLEAR_MS = 750L;
    private static final long ATOMIC_LYRIC_SUPPORT_EVENT = 8L;
    /**
     * Baseline Atomic Player capability bits. Atomic's own getSupportEvent() falls back to 7
     * (bits 1|2|4) whenever it cannot query an app's vivo service, so 7 is what it assumes for
     * an ordinary cooperating player. Publishing only the lyric bit would report 8, which
     * actively declares those three baseline capabilities as unsupported and leaves the
     * progress bar widget disabled even though position and duration are readable from the
     * standard PlaybackState / METADATA_KEY_DURATION.
     */
    private static final long ATOMIC_BASELINE_SUPPORT_EVENTS = 7L;

    /**
     * Diagnostic build switch. Set true to prefix Atomic lyric payloads with probe lines.
     * All diagnostic paths are compiled in but gated here so the release build stays clean.
     */
    private static final boolean DIAGNOSTIC_MODE = false;
    private static volatile String sessionProbeDiag = "";
    private static volatile long sessionProbeGeneration = -1L;
    private static volatile String atomicConnectDiag = "conn=n";
    private static volatile long trackStartUptime = 0L;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final AtomicLong GENERATION = new AtomicLong();
    private static final AtomicLong ATOMIC_EVENT_SEQUENCE = new AtomicLong();
    private static final AtomicLong ATOMIC_STATE_SEQUENCE = new AtomicLong();
    private static final Object STATE_LOCK = new Object();
    private static final Pattern LRC_TIME = Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?\\]");
    private static final LyricsState EMPTY_LYRICS = new LyricsState(
            new long[0], new String[0], "", new long[0], new String[0], "");

    private static volatile Object currentManager;
    private static volatile String currentTrackKey = "";
    private static volatile long currentExpectedQueueId = -1L;
    private static volatile Object currentPlaybackItem;
    private static long currentPlaybackItemGeneration = -1L;
    private static volatile LyricsState lyricsState = EMPTY_LYRICS;
    private static String lastLine;
    private static String lastWhole;
    private static int lastStatus = Integer.MIN_VALUE;
    private static String metadataWhole;
    private static int metadataStatus = Integer.MIN_VALUE;
    private static volatile Object currentQueueItem;
    private static volatile long lastKnownDuration = 0L;
    private static boolean publishQueued;
    private static long pendingGeneration = -1L;
    private static Object pendingManager;
    private static boolean pendingForceMetadata;
    private static boolean pendingAtomicPublish;
    private static long activeLoadGeneration = -1L;
    private static Object activeLoadManager;
    private static int loadRetryCount;
    private static String currentAtomicMediaId = "";
    private static long currentAtomicMediaIdGeneration = -1L;
    private static String atomicWhole;
    private static int atomicStatus = Integer.MIN_VALUE;

    private VivoCarLyrics() {
    }

    private static final class LyricsState {
        final long[] times;
        final String[] texts;
        final String whole;
        final long[] clusterTimes;
        final String[] clusterTexts;
        final String clusterWhole;

        LyricsState(long[] times, String[] texts, String whole,
                    long[] clusterTimes, String[] clusterTexts, String clusterWhole) {
            this.times = times;
            this.texts = texts;
            this.whole = whole;
            this.clusterTimes = clusterTimes;
            this.clusterTexts = clusterTexts;
            this.clusterWhole = clusterWhole;
        }
    }

    /** Adds Atomic lyric capability and duration before Apple Music publishes its native MediaItem. */
    public static void onNativeMediaItem(Object mediaItem) {
        try {
            if (advertiseAtomicLyricSupport(mediaItem) && lastKnownDuration > 0L) {
                Object metadata = getFieldValue(mediaItem, "d");
                if (metadata != null) {
                    Bundle extras = (Bundle) getFieldValue(metadata, "I");
                    if (extras != null && !extras.containsKey(PUBLIC_DURATION)) {
                        extras.putLong(PUBLIC_DURATION, lastKnownDuration);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    public static void onCurrentItemChanged(Object playbackManager, Object newQueueItem) {
        try {
            long generation = GENERATION.incrementAndGet();
            currentManager = playbackManager;
            currentQueueItem = newQueueItem;
            currentTrackKey = queueKey(newQueueItem);
            long itemDur = extractDurationFromItem(newQueueItem);
            if (itemDur > 0L) {
                lastKnownDuration = itemDur;
            }
            // Keep lastKnownDuration across tracks: if the new item has no duration yet,
            // injecting the previous track's duration is better than injecting 0 or nothing.
            currentExpectedQueueId = longValue(invokeOptional(newQueueItem, "getPlaybackQueueId"), -1L);
            currentPlaybackItem = null;
            resetPublishCache();
            String mediaId = queueMediaId(newQueueItem);
            synchronized (STATE_LOCK) {
                if (!isCurrent(playbackManager, generation)) {
                    return;
                }
                currentAtomicMediaId = mediaId;
                currentAtomicMediaIdGeneration = generation;
            }
            publishAtomicClear(playbackManager, mediaId, generation);

            if (newQueueItem == null) {
                requestPublish(playbackManager, "", "-1", STATUS_NO_LYRICS, generation);
                return;
            }

            scheduleLoad(playbackManager, generation, currentExpectedQueueId, LOAD_RETRY_MS);
        } catch (Throwable ignored) {
        }
    }

    public static void onMetadataUpdated(Object playbackManager, Object queueItem) {
        try {
            String key = queueKey(queueItem);
            if (!key.isEmpty() && !key.equals(currentTrackKey)) {
                onCurrentItemChanged(playbackManager, queueItem);
                return;
            }
            long generation = GENERATION.get();
            long queueId = longValue(invokeOptional(queueItem, "getPlaybackQueueId"), -1L);
            if (queueId > 0L) {
                currentExpectedQueueId = queueId;
            }
            if (!metadataHasAtomicSupport(playbackManager, generation)) {
                scheduleMetadataReapply(playbackManager, generation);
            }
            if (shouldReloadLyrics()) {
                scheduleLoad(playbackManager, generation, currentExpectedQueueId, LOAD_RETRY_MS);
            }
        } catch (Throwable ignored) {
        }
    }

    public static void onPlaybackError(Object playbackManager) {
        try {
            long generation = GENERATION.incrementAndGet();
            currentManager = playbackManager;
            String mediaId;
            synchronized (STATE_LOCK) {
                mediaId = currentAtomicMediaId;
            }
            currentExpectedQueueId = -1L;
            currentPlaybackItem = null;
            resetPublishCache();
            synchronized (STATE_LOCK) {
                if (!isCurrent(playbackManager, generation)) {
                    return;
                }
                currentAtomicMediaId = mediaId;
                currentAtomicMediaIdGeneration = generation;
            }
            requestPublish(playbackManager, "", "-1", STATUS_FAILED, generation);
            scheduleMetadataReapply(playbackManager, generation);
        } catch (Throwable ignored) {
        }
    }

    /** Called from the media-session seek path so a drag jumps to the requested lyric immediately. */
    public static void onSeek(Object playbackManager, long position) {
        try {
            long generation = GENERATION.get();
            LyricsState state = lyricsState;
            if (!isCurrent(playbackManager, generation) || state.times.length == 0 || state.texts.length == 0) {
                return;
            }
            requestLinePublish(playbackManager,
                    lineForPosition(position, state.times, state.texts), generation, true);
            MAIN.postDelayed(new SeekRefreshTask(playbackManager, generation), 120L);
        } catch (Throwable ignored) {
        }
    }

    /** Fallback for callers that do not have the target position available. */
    public static void onSeek(Object playbackManager) {
        onSeek(playbackManager, controllerPosition(playbackManager));
    }

    /** Replays the current state after Atomic Player registers its MediaController callback. */
    public static void onAtomicControllerConnected(String packageName) {
        try {
            if (!ATOMIC_CONTROLLER_PACKAGE.equals(packageName)) {
                return;
            }
            if (DIAGNOSTIC_MODE) {
                long since = trackStartUptime > 0L
                        ? android.os.SystemClock.uptimeMillis() - trackStartUptime : -1L;
                atomicConnectDiag = "conn=y@" + since;
                // Probe exactly what Atomic sees at connection time. The session's compat layer
                // may not be wired up at first-lyric time (I3.l.c is live but c.D.a and c.E
                // are null), so we retry here with up to 3 x 300ms delays, then bust the cache
                // so the next lyric update surfaces the fresh result.
                Object mgr;
                synchronized (STATE_LOCK) {
                    mgr = currentManager;
                }
                if (mgr != null) {
                    final Object capturedMgr = mgr;
                    Runnable retry = new Runnable() {
                        int attempts = 0;
                        @Override public void run() {
                            String result = probeSessionAsAtomicSees(capturedMgr);
                            boolean succeeded = !result.contains("sing=null")
                                    && !result.contains("A=null")
                                    && !result.contains("A=sing=ok")
                                    && !result.startsWith("D1 A=sing=ok B=null");
                            synchronized (STATE_LOCK) {
                                sessionProbeDiag = result;
                                // Always bust the cache so the next lyric update re-reads this
                                sessionProbeGeneration = -1L;
                            }
                            if (!succeeded && ++attempts < 3) {
                                MAIN.postDelayed(this, 300L);
                            }
                        }
                    };
                    MAIN.postDelayed(retry, 100L);
                }
            }

            Object manager;
            long generation;
            String whole;
            int status;
            long stateSequence;
            synchronized (STATE_LOCK) {
                manager = currentManager;
                generation = GENERATION.get();
                whole = atomicWhole;
                status = atomicStatus;
                stateSequence = ATOMIC_STATE_SEQUENCE.get();
            }
            if (!isCurrent(manager, generation) || status == Integer.MIN_VALUE) {
                return;
            }

            scheduleMetadataReapply(manager, generation);
            for (long delay : ATOMIC_CONNECT_REPLAY_DELAYS_MS) {
                MAIN.postDelayed(new AtomicReplayTask(manager, generation, whole,
                        status, stateSequence, false), delay);
            }
        } catch (Throwable ignored) {
        }
    }

    private static final class SeekRefreshTask implements Runnable {
        private final Object manager;
        private final long generation;

        SeekRefreshTask(Object manager, long generation) {
            this.manager = manager;
            this.generation = generation;
        }

        @Override
        public void run() {
            LyricsState state = lyricsState;
            if (!isCurrent(manager, generation) || state.times.length == 0 || state.texts.length == 0) {
                return;
            }
            requestLinePublish(manager,
                    lineForPosition(controllerPosition(manager), state.times, state.texts), generation);
        }
    }

    private static final class MetadataReapplyTask implements Runnable {
        private final Object manager;
        private final long generation;

        MetadataReapplyTask(Object manager, long generation) {
            this.manager = manager;
            this.generation = generation;
        }

        @Override
        public void run() {
            if (!isCurrent(manager, generation) || metadataHasAtomicSupport(manager, generation)) {
                return;
            }

            String line;
            String whole;
            int status;
            synchronized (STATE_LOCK) {
                line = lastLine;
                whole = lastWhole;
                status = lastStatus;
            }
            if (status != Integer.MIN_VALUE) {
                requestPublish(manager, line == null ? "" : line, whole == null ? "" : whole,
                        status, generation, true);
            }
        }
    }

    private static void scheduleMetadataReapply(Object manager, long generation) {
        for (long delay : METADATA_REAPPLY_DELAYS_MS) {
            MAIN.postDelayed(new MetadataReapplyTask(manager, generation), delay);
        }
    }

    private static void scheduleLoad(Object manager, long generation, long expectedQueueId, long delay) {
        synchronized (STATE_LOCK) {
            if (!isCurrent(manager, generation)) {
                return;
            }
            if (activeLoadGeneration == generation && activeLoadManager == manager) {
                return;
            }
            activeLoadGeneration = generation;
            activeLoadManager = manager;
        }
        MAIN.postDelayed(new LoadTask(manager, generation, expectedQueueId, 0), delay);
    }

    private static boolean shouldReloadLyrics() {
        synchronized (STATE_LOCK) {
            long generation = GENERATION.get();
            boolean active = activeLoadGeneration == generation && activeLoadManager == currentManager;
            return !active && lyricsState.texts.length == 0 && lastStatus != STATUS_LOADING;
        }
    }

    private static void finishLoad(Object manager, long generation) {
        synchronized (STATE_LOCK) {
            if (activeLoadGeneration == generation && activeLoadManager == manager) {
                activeLoadGeneration = -1L;
                activeLoadManager = null;
            }
        }
    }

    private static void retryLoadAfterFailure(Object manager, long generation) {
        long expectedQueueId;
        long delay;
        synchronized (STATE_LOCK) {
            if (!isCurrent(manager, generation) || lyricsState.texts.length != 0 || loadRetryCount >= 2) {
                return;
            }
            loadRetryCount++;
            expectedQueueId = currentExpectedQueueId;
            delay = LOAD_RETRY_MS * (4L * loadRetryCount);
        }
        scheduleLoad(manager, generation, expectedQueueId, delay);
    }

    private static final class LoadTask implements Runnable {
        private final Object manager;
        private final long generation;
        private final long expectedQueueId;
        private final int attempt;

        LoadTask(Object manager, long generation, long expectedQueueId, int attempt) {
            this.manager = manager;
            this.generation = generation;
            this.expectedQueueId = expectedQueueId;
            this.attempt = attempt;
        }

        @Override
        public void run() {
            if (!isCurrent(manager, generation)) {
                return;
            }

            try {
                Object playbackItem = currentPlaybackItem(manager);
                long queueId = longValue(invokeOptional(playbackItem, "getQueueId"), 0L);
                long requiredQueueId = currentExpectedQueueId > 0L ? currentExpectedQueueId : expectedQueueId;
                if (playbackItem == null || (requiredQueueId > 0L && queueId > 0L && requiredQueueId != queueId)) {
                    if (attempt + 1 < MAX_LOAD_ATTEMPTS) {
                        MAIN.postDelayed(new LoadTask(manager, generation, requiredQueueId, attempt + 1), LOAD_RETRY_MS);
                        return;
                    }
                    if (playbackItem == null) {
                        retryOrFinish(STATUS_FAILED);
                        return;
                    }
                }

                synchronized (STATE_LOCK) {
                    if (!isCurrent(manager, generation)) {
                        return;
                    }
                    currentPlaybackItem = playbackItem;
                    currentPlaybackItemGeneration = generation;
                }
                requestPublish(manager, "", "", STATUS_LOADING, generation);

                boolean hasLyrics = booleanValue(invokeOptional(playbackItem, "hasLyrics"));
                boolean hasCustomLyrics = booleanValue(invokeOptional(playbackItem, "hasCustomLyrics"));
                if (hasCustomLyrics) {
                    String custom = stringValue(invokeOptional(playbackItem, "getCustomLyrics"));
                    if (!custom.trim().isEmpty()) {
                        consumeRawLyrics(manager, generation, custom, controllerDuration(manager));
                        finishLoad(manager, generation);
                        return;
                    }
                }

                if (!hasLyrics && !hasCustomLyrics) {
                    retryOrFinish(STATUS_NO_LYRICS);
                    return;
                }

                loadWithAppleViewModel(manager, generation, playbackItem);
            } catch (Throwable ignored) {
                retryOrFinish(STATUS_FAILED);
            }
        }

        private void retryOrFinish(int finalStatus) {
            if (!isCurrent(manager, generation)) {
                return;
            }
            if (attempt + 1 < MAX_LOAD_ATTEMPTS) {
                long requiredQueueId = currentExpectedQueueId > 0L ? currentExpectedQueueId : expectedQueueId;
                MAIN.postDelayed(new LoadTask(manager, generation, requiredQueueId, attempt + 1), LOAD_RETRY_MS);
            } else {
                finishLoad(manager, generation);
                requestPublish(manager, "", "-1", finalStatus, generation);
                if (finalStatus == STATUS_FAILED || finalStatus == STATUS_NO_LYRICS) {
                    retryLoadAfterFailure(manager, generation);
                }
            }
        }
    }

    private static void loadWithAppleViewModel(Object manager, long generation, Object playbackItem) throws Exception {
        Application application = appleApplication();
        Class<?> viewModelClass = Class.forName("com.apple.android.music.player.viewmodel.PlayerLyricsViewModel");
        Constructor<?> constructor = viewModelClass.getConstructor(Application.class);
        Object viewModel = constructor.newInstance(application);
        Object liveData = invokeRequired(viewModel, "getLyricsResult");
        Class<?> observerType = Class.forName("androidx.lifecycle.L");
        final LyricsObserver handler = new LyricsObserver(manager, generation, playbackItem, liveData, observerType);
        Object observer = Proxy.newProxyInstance(observerType.getClassLoader(), new Class<?>[]{observerType}, handler);
        handler.observer = observer;
        Method observeForever = liveData.getClass().getMethod("observeForever", observerType);
        observeForever.invoke(liveData, observer);
        MAIN.postDelayed(new Runnable() {
            @Override
            public void run() {
                handler.onTimeout();
            }
        }, LYRICS_RESULT_TIMEOUT_MS);
        try {
            invokeRequired(viewModel, "loadLyrics", playbackItem);
        } catch (Exception error) {
            handler.cancel();
            throw error;
        }
    }

    private static final class LyricsObserver implements InvocationHandler {
        private final Object manager;
        private final long generation;
        private final Object playbackItem;
        private final Object liveData;
        private final Class<?> observerType;
        private Object observer;
        private volatile boolean consumed;

        LyricsObserver(Object manager, long generation, Object playbackItem, Object liveData, Class<?> observerType) {
            this.manager = manager;
            this.generation = generation;
            this.playbackItem = playbackItem;
            this.liveData = liveData;
            this.observerType = observerType;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            String name = method.getName();
            if ("hashCode".equals(name)) {
                return System.identityHashCode(proxy);
            }
            if ("equals".equals(name)) {
                return args != null && args.length == 1 && proxy == args[0];
            }
            if ("toString".equals(name)) {
                return BUILD_MARKER + "-observer";
            }
            if (!"onChanged".equals(name) || consumed || args == null || args.length == 0 || args[0] == null) {
                return null;
            }

            boolean claimed = false;
            try {
                Object pair = args[0];
                Object songInfoPtr = invokeRequired(pair, "component1");
                Object error = invokeRequired(pair, "component2");
                if (songInfoPtr == null && error == null) {
                    return null;
                }
                claimed = claim();
                if (!claimed) {
                    return null;
                }
                removeObserver();
                if (!isCurrent(manager, generation)) {
                    return null;
                }
                if (error != null) {
                    finishLoad(manager, generation);
                    requestPublish(manager, "", "-1", STATUS_FAILED, generation);
                    retryLoadAfterFailure(manager, generation);
                } else if (songInfoPtr == null) {
                    String custom = stringValue(invokeOptional(playbackItem, "getCustomLyrics"));
                    if (custom.trim().isEmpty()) {
                        finishLoad(manager, generation);
                        requestPublish(manager, "", "-1", STATUS_NO_LYRICS, generation);
                    } else {
                        consumeRawLyrics(manager, generation, custom, controllerDuration(manager));
                        finishLoad(manager, generation);
                    }
                } else {
                    consumeSongInfo(manager, generation, songInfoPtr);
                    finishLoad(manager, generation);
                }
            } catch (Throwable ignored) {
                if (!claimed && !claim()) {
                    return null;
                }
                removeObserver();
                if (isCurrent(manager, generation)) {
                    finishLoad(manager, generation);
                    requestPublish(manager, "", "-1", STATUS_FAILED, generation);
                    retryLoadAfterFailure(manager, generation);
                }
            }
            return null;
        }

        void onTimeout() {
            if (!claim()) {
                return;
            }
            removeObserver();
            if (isCurrent(manager, generation)) {
                finishLoad(manager, generation);
                requestPublish(manager, "", "-1", STATUS_FAILED, generation);
                retryLoadAfterFailure(manager, generation);
            }
        }

        void cancel() {
            if (claim()) {
                removeObserver();
            }
        }

        private synchronized boolean claim() {
            if (consumed) {
                return false;
            }
            consumed = true;
            return true;
        }

        private void removeObserver() {
            try {
                Method remove = liveData.getClass().getMethod("removeObserver", observerType);
                remove.invoke(liveData, observer);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void consumeSongInfo(Object manager, long generation, Object songInfoPtr) throws Exception {
        Object songInfo = invokeRequired(songInfoPtr, "get");
        Object sections = invokeRequired(songInfo, "getSections");
        Class<?> lineAccessClass = Class.forName("com.apple.android.music.ttml.i");
        Object lineAccess = constructCompatible(lineAccessClass, sections);
        int count = intValue(invokeRequired(lineAccess, "b"), 0);
        ArrayList<Long> times = new ArrayList<Long>();
        ArrayList<String> texts = new ArrayList<String>();

        for (int index = 0; index < count; index++) {
            Object linePtr = invokeRequired(lineAccess, "a", Integer.valueOf(index));
            Object line = invokeRequired(linePtr, "get");
            long begin = Math.max(0L, longValue(invokeRequired(line, "getBegin"), 0L));
            String text = plainText(stringValue(invokeRequired(line, "getHtmlLineText")));
            appendLine(times, texts, begin, text);
        }

        long duration = controllerDuration(manager);
        if (duration <= 0L) {
            duration = longValue(invokeOptional(songInfo, "getDuration"), 0L);
        }
        publishLines(manager, generation, times, texts, duration);
    }

    private static void consumeRawLyrics(Object manager, long generation, String raw, long duration) {
        ArrayList<Long> times = new ArrayList<Long>();
        ArrayList<String> texts = new ArrayList<String>();
        String[] rows = raw.replace("\r", "").split("\n");
        for (String row : rows) {
            Matcher matcher = LRC_TIME.matcher(row);
            String clean = plainText(matcher.replaceAll(""));
            matcher.reset();
            boolean found = matcher.find();
            matcher.reset();
            while (matcher.find()) {
                long minutes = Long.parseLong(matcher.group(1));
                long seconds = Long.parseLong(matcher.group(2));
                long fraction = fractionMillis(matcher.group(3));
                appendLine(times, texts, minutes * 60000L + seconds * 1000L + fraction, clean);
            }
            if (!found && !clean.isEmpty()) {
                times.add(Long.valueOf(0L));
                texts.add(clean);
            }
        }
        publishLines(manager, generation, times, texts, duration);
    }

    private static void publishLines(Object manager, long generation, List<Long> sourceTimes,
                                     List<String> sourceTexts, long duration) {
        if (!isCurrent(manager, generation)) {
            return;
        }
        if (sourceTexts.isEmpty()) {
            requestPublish(manager, "", "-1", STATUS_NO_LYRICS, generation);
            return;
        }

        long[] times = new long[sourceTimes.size()];
        String[] texts = sourceTexts.toArray(new String[sourceTexts.size()]);
        boolean hasIncreasingTime = false;
        for (int index = 0; index < times.length; index++) {
            times[index] = sourceTimes.get(index).longValue();
            if (index > 0 && times[index] > times[index - 1]) {
                hasIncreasingTime = true;
            }
        }
        if (times.length > 1 && !hasIncreasingTime) {
            long step = duration > 0L ? Math.max(1000L, duration / times.length) : 5000L;
            for (int index = 0; index < times.length; index++) {
                times[index] = index * step;
            }
        }

        StringBuilder lrc = new StringBuilder();
        for (int index = 0; index < texts.length; index++) {
            lrc.append(formatTime(times[index])).append(texts[index]);
            if (index + 1 < texts.length) {
                lrc.append('\n');
            }
        }

        String whole = lrc.toString();
        ClusterLyricsPaginator.Result cluster =
                ClusterLyricsPaginator.paginate(times, texts, duration);
        synchronized (STATE_LOCK) {
            if (!isCurrent(manager, generation)) {
                return;
            }
            lyricsState = new LyricsState(times, texts, whole,
                    cluster.times, cluster.texts, cluster.whole);
            loadRetryCount = 0;
        }
        String currentLine = lineForPosition(controllerPosition(manager), times, texts);
        requestPublish(manager, currentLine, whole, STATUS_SUCCESS, generation);
        MAIN.postDelayed(new LineTick(manager, generation), LINE_POLL_MS);
    }

    private static final class LineTick implements Runnable {
        private final Object manager;
        private final long generation;

        LineTick(Object manager, long generation) {
            this.manager = manager;
            this.generation = generation;
        }

        @Override
        public void run() {
            if (!isCurrent(manager, generation)) {
                return;
            }
            LyricsState state = lyricsState;
            long[] times = state.times;
            String[] texts = state.texts;
            if (times.length == 0 || texts.length == 0) {
                return;
            }
            String currentLine = lineForPosition(controllerPosition(manager), times, texts);
            requestLinePublish(manager, currentLine, generation);
            MAIN.postDelayed(this, LINE_POLL_MS);
        }
    }

    private static void scheduleAtomicReplays(Object manager, long generation, String whole,
                                              int status, long stateSequence) {
        for (long delay : ATOMIC_REPLAY_DELAYS_MS) {
            MAIN.postDelayed(new AtomicReplayTask(manager, generation, whole,
                    status, stateSequence, false), delay);
        }
        MAIN.postDelayed(new AtomicReplayTask(manager, generation, whole,
                status, stateSequence, true), ATOMIC_KEEPALIVE_MS);
    }

    private static final class AtomicReplayTask implements Runnable {
        private final Object manager;
        private final long generation;
        private final String whole;
        private final int status;
        private final long stateSequence;
        private final boolean repeat;

        AtomicReplayTask(Object manager, long generation, String whole, int status,
                         long stateSequence, boolean repeat) {
            this.manager = manager;
            this.generation = generation;
            this.whole = whole;
            this.status = status;
            this.stateSequence = stateSequence;
            this.repeat = repeat;
        }

        @Override
        public void run() {
            String line;
            synchronized (STATE_LOCK) {
                if (!isCurrent(manager, generation)
                        || stateSequence != ATOMIC_STATE_SEQUENCE.get()
                        || status != atomicStatus
                        || !safeEquals(whole, atomicWhole)) {
                    return;
                }
                line = lastLine;
            }
            String lyric = status == STATUS_SUCCESS ? whole : "";
            publishAtomicExtras(manager, line == null ? "" : line,
                    resolveAtomicMediaId(manager, generation), lyric, generation);
            if (repeat) {
                MAIN.postDelayed(this, ATOMIC_KEEPALIVE_MS);
            }
        }
    }

    private static void requestLinePublish(Object manager, String line, long generation) {
        requestLinePublish(manager, line, generation, false);
    }

    private static void requestLinePublish(Object manager, String line, long generation,
                                           boolean forceExtras) {
        if (!isCurrent(manager, generation)) {
            return;
        }
        final String latestLine = line == null ? "" : line;
        synchronized (STATE_LOCK) {
            if (!forceExtras && lastStatus == STATUS_SUCCESS && safeEquals(latestLine, lastLine)) {
                return;
            }
            lastLine = latestLine;
            lastStatus = STATUS_SUCCESS;
        }

        Runnable publish = new Runnable() {
            @Override
            public void run() {
                if (isCurrent(manager, generation)) {
                    publishLineExtras(manager, latestLine, generation);
                }
            }
        };
        Handler handler = serviceHandler(manager);
        if (handler != null && Looper.myLooper() != handler.getLooper()) {
            handler.post(publish);
        } else {
            publish.run();
        }
    }

    private static void appendLine(List<Long> times, List<String> texts, long time, String text) {
        if (text == null || text.trim().isEmpty()) {
            return;
        }
        String clean = text.trim();
        int last = times.size() - 1;
        if (last >= 0 && times.get(last).longValue() == time) {
            texts.set(last, texts.get(last) + " / " + clean);
            return;
        }
        times.add(Long.valueOf(time));
        texts.add(clean);
    }

    private static void requestPublish(final Object manager, final String line, final String whole,
                                       final int status, final long generation) {
        requestPublish(manager, line, whole, status, generation, false);
    }

    private static void requestPublish(final Object manager, final String line, final String whole,
                                       final int status, final long generation,
                                       final boolean forceMetadata) {
        if (!isCurrent(manager, generation)) {
            return;
        }
        synchronized (STATE_LOCK) {
            if (!forceMetadata && status == lastStatus && safeEquals(line, lastLine)
                    && safeEquals(whole, lastWhole)) {
                return;
            }
            lastLine = line;
            lastWhole = whole;
            lastStatus = status;
            boolean atomicNeeded = status != atomicStatus || !safeEquals(whole, atomicWhole);
            if (atomicNeeded) {
                atomicWhole = whole;
                atomicStatus = status;
                ATOMIC_STATE_SEQUENCE.incrementAndGet();
            }
            pendingManager = manager;
            pendingGeneration = generation;
            pendingForceMetadata = pendingForceMetadata || forceMetadata;
            pendingAtomicPublish = pendingAtomicPublish || atomicNeeded;
            if (publishQueued) {
                return;
            }
            publishQueued = true;
        }

        Runnable publish = new Runnable() {
            @Override
            public void run() {
                String latestLine;
                String latestWhole;
                int latestStatus;
                Object latestManager;
                long latestGeneration;
                boolean forceLatestMetadata;
                boolean publishLatestAtomic;
                long latestAtomicStateSequence;
                LyricsState latestLyricsState;
                synchronized (STATE_LOCK) {
                    latestManager = pendingManager;
                    latestGeneration = pendingGeneration;
                    latestLine = lastLine;
                    latestWhole = lastWhole;
                    latestStatus = lastStatus;
                    forceLatestMetadata = pendingForceMetadata;
                    publishLatestAtomic = pendingAtomicPublish;
                    latestAtomicStateSequence = ATOMIC_STATE_SEQUENCE.get();
                    latestLyricsState = lyricsState;
                    pendingForceMetadata = false;
                    pendingAtomicPublish = false;
                    publishQueued = false;
                }
                if (!isCurrent(latestManager, latestGeneration)) {
                    return;
                }
                String publishMediaId = resolveAtomicMediaId(latestManager, latestGeneration);
                try {
                    String latestClusterWhole = latestStatus == STATUS_SUCCESS
                            ? latestLyricsState.clusterWhole : latestWhole;
                    String latestClusterLine = latestStatus == STATUS_SUCCESS
                            ? lineForPosition(controllerPosition(latestManager),
                                    latestLyricsState.clusterTimes, latestLyricsState.clusterTexts)
                            : "";
                    boolean metadataNeeded = latestStatus != STATUS_LOADING && (forceLatestMetadata
                            || latestStatus != metadataStatus
                            || !safeEquals(latestClusterWhole, metadataWhole));
                    if (metadataNeeded && publishMetadata(latestManager, latestClusterLine,
                            latestClusterWhole,
                            latestStatus, latestGeneration)) {
                        synchronized (STATE_LOCK) {
                            if (isCurrent(latestManager, latestGeneration)) {
                                metadataWhole = latestClusterWhole;
                                metadataStatus = latestStatus;
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
                if (!isCurrent(latestManager, latestGeneration)) {
                    return;
                }
                if (publishLatestAtomic) {
                    String atomicLyric = latestStatus == STATUS_SUCCESS ? latestWhole : "";
                    publishAtomicExtras(latestManager, latestLine, publishMediaId,
                            atomicLyric, latestGeneration);
                    if (latestStatus != STATUS_LOADING) {
                        scheduleAtomicReplays(latestManager, latestGeneration, latestWhole,
                                latestStatus, latestAtomicStateSequence);
                    }
                } else {
                    publishLineExtras(latestManager, latestLine, latestGeneration);
                }
            }
        };

        Handler handler = serviceHandler(manager);
        if (handler != null && Looper.myLooper() != handler.getLooper()) {
            handler.post(publish);
        } else {
            publish.run();
        }
    }

    /**
     * Never republishes MediaMetadata. Calling the playback manager's MediaItem publish path
     * (manager.I) rebuilds the session's MediaMetadata and resets the native PlaybackState, which
     * removes Atomic Player's progress bar and makes the cluster reload cover art. Both the car
     * head unit and the instrument cluster receive their lyrics through session Extras instead, and
     * the only MediaMetadata mutation is the in-place capability bit in
     * {@link #advertiseAtomicLyricSupport(Object)}.
     *
     * <p>Kept as an explicit no-op so the caller's dedupe bookkeeping stays intact and so a future
     * change does not silently reintroduce the MediaItem replacement.
     */
    private static boolean publishMetadata(Object manager, String line, String whole, int status,
                                           long generation) {
        return false;
    }

    /**
     * ORs the Atomic Player capability bits into the MediaItem's existing Metadata Extras in
     * place, so Apple Music's own native publish carries them. Mutating the existing Bundle
     * avoids replacing the MediaItem, which would reset the native progress bar and make the
     * cluster reload cover art.
     *
     * <p>The baseline bits are included alongside the lyric bit. Publishing the lyric bit alone
     * reports 8, which tells Atomic Player that bits 1, 2 and 4 are unsupported and leaves its
     * progress bar widget disabled; its own fallback for a cooperating player is 7.
     */
    /**
     * Finds Apple Music's own {@code MediaSessionCompat.Token} by walking object graph edges from
     * the session manager. Support-library class names survive obfuscation, so the token is matched
     * by type rather than by a guessed field name. Bounded in both depth and visit count.
     */
    private static Object findCompatToken(Object root, Class<?> tokenClass) {
        java.util.IdentityHashMap<Object, Boolean> seen = new java.util.IdentityHashMap<>();
        List<Object> level = new ArrayList<>();
        level.add(root);
        int visited = 0;
        for (int depth = 0; depth < 4 && !level.isEmpty(); depth++) {
            List<Object> next = new ArrayList<>();
            for (Object obj : level) {
                if (obj == null || seen.put(obj, Boolean.TRUE) != null || ++visited > 400) {
                    continue;
                }
                if (tokenClass.isInstance(obj)) {
                    return obj;
                }
                for (Method method : obj.getClass().getMethods()) {
                    if (method.getParameterTypes().length == 0
                            && tokenClass.isAssignableFrom(method.getReturnType())) {
                        try {
                            method.setAccessible(true);
                            Object token = method.invoke(obj);
                            if (token != null) {
                                return token;
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                }
                for (Class<?> cursor = obj.getClass();
                        cursor != null && cursor != Object.class; cursor = cursor.getSuperclass()) {
                    for (Field field : cursor.getDeclaredFields()) {
                        if (field.getType().isPrimitive()
                                || Modifier.isStatic(field.getModifiers())) {
                            continue;
                        }
                        try {
                            field.setAccessible(true);
                            Object value = field.get(obj);
                            if (value != null && !(value instanceof String)) {
                                next.add(value);
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
            level = next;
        }
        return null;
    }

    /**
     * Reads Apple Music's session the way Atomic Player reads it: through a MediaControllerCompat
     * built from the session's own compat token. This is deliberately not self-referential - it
     * reports the legacy metadata and PlaybackState the session actually exposes, rather than the
     * Bundle this helper wrote.
     */
    private static String probeSessionAsAtomicSees(Object manager) {
        // Probe multiple token paths in a single call so one APK install surfaces all
        // the diagnostic data we need. Each path is independent and failures are silent.
        StringBuilder diag = new StringBuilder();
        Object token = null;

        // Path A: I3.l.e() – the static getter Apple Music's own MediaRouter dialogs use.
        // Decoded from smali: .method public static e()
        //   Landroid/support/v4/media/session/MediaSessionCompat$Token;
        // Returns null when the internal singleton I3.l.c is not yet initialised.
        String pathA = "null";
        try {
            Class<?> tokenSource = Class.forName("I3.l");
            // Also check the singleton field state for timing diagnosis
            Field c = tokenSource.getDeclaredField("c");
            c.setAccessible(true);
            Object singleton = c.get(null);
            pathA = singleton == null ? "sing=null" : "sing=ok";
            Object t = invokeStaticOptional(tokenSource, "e");
            if (t != null) { token = t; pathA = "ok"; }
        } catch (Throwable ex) {
            pathA = "exc:" + ex.getClass().getSimpleName();
        }

        // Path B: manager.a (k0) → field h (MediaPlayerController, confirmed in smali) → walk
        String pathB = "skip";
        if (token == null) {
            try {
                Object sm = getFieldValue(manager, "a");      // P.a = k0
                Object mpc = getFieldValue(sm, "h");          // k0.h = MediaPlayerController
                Class<?> tclass = Class.forName(
                        "android.support.v4.media.session.MediaSessionCompat$Token");
                token = findCompatToken(mpc, tclass);
                pathB = token != null ? "ok" : "null";
            } catch (Throwable ex) {
                pathB = "exc:" + ex.getClass().getSimpleName();
            }
        }

        // Path C: k0 field b (LE3/P1$b = MediaLibrarySession) → walk
        String pathC = "skip";
        if (token == null) {
            try {
                Object sm = getFieldValue(manager, "a");      // P.a = k0
                Object sess = getFieldValue(sm, "b");         // k0.b = E3.P1$b
                Class<?> tclass = Class.forName(
                        "android.support.v4.media.session.MediaSessionCompat$Token");
                token = findCompatToken(sess, tclass);
                pathC = token != null ? "ok" : "null";
            } catch (Throwable ex) {
                pathC = "exc:" + ex.getClass().getSimpleName();
            }
        }

        // Path D: search k0.b (Media3 MediaLibrarySession) for the *framework* token
        // android.media.session.MediaSession$Token. MediaControllerCompat accepts that
        // directly via its second constructor. The compat token (I3.l.e) is never set
        // during local playback; the framework token always is.
        String pathD = "skip";
        Object frameworkToken = null;
        if (token == null) {
            try {
                Object sm = getFieldValue(manager, "a");      // P.a = k0
                Object sess = getFieldValue(sm, "b");         // k0.b = E3.P1$b
                Class<?> fwTokenClass = Class.forName(
                        "android.media.session.MediaSession$Token");
                frameworkToken = findCompatToken(sess, fwTokenClass);
                pathD = frameworkToken != null ? "ok" : "null";
            } catch (Throwable ex) {
                pathD = "exc:" + ex.getClass().getSimpleName();
            }
        }

        diag.append("D1 A=").append(pathA)
            .append(" B=").append(pathB)
            .append(" C=").append(pathC)
            .append(" D=").append(pathD);

        // Build a controller from whichever token worked.
        // MediaControllerCompat's constructor parameter type is obfuscated and can't be
        // matched by reflection. Use the standard framework MediaController instead --
        // it accepts android.media.session.MediaSession$Token directly, has the same
        // getMetadata()/getPlaybackState() surface, and is not obfuscated.
        Object controllerToUse = null;
        if (frameworkToken != null) {
            try {
                android.media.session.MediaController fwCtl =
                        new android.media.session.MediaController(
                                appleApplication(),
                                (android.media.session.MediaSession.Token) frameworkToken);
                controllerToUse = fwCtl;
            } catch (Throwable ex) {
                diag.append(" ctlErr:").append(ex.getClass().getSimpleName());
            }
        } else if (token != null) {
            // Compat token path: try scanning constructors as before but accept any 2-arg ctor
            try {
                Class<?> controllerClass = Class.forName(
                        "android.support.v4.media.session.MediaControllerCompat");
                android.content.Context ctx = appleApplication();
                for (Constructor<?> ctor : controllerClass.getDeclaredConstructors()) {
                    Class<?>[] pt = ctor.getParameterTypes();
                    if (pt.length == 2 && pt[0].isInstance(ctx)) {
                        try {
                            ctor.setAccessible(true);
                            controllerToUse = ctor.newInstance(ctx, token);
                            break;
                        } catch (Throwable ignored) {
                        }
                    }
                }
                if (controllerToUse == null) diag.append(" ctlErr:noCtor");
            } catch (Throwable ex) {
                diag.append(" ctlErr:").append(ex.getClass().getSimpleName());
            }
        }

        if (controllerToUse == null) {
            return diag.toString();
        }

        try {
            Object metadata = invokeOptional(controllerToUse, "getMetadata");
            long metaDuration = -1L;
            long metaSupport = -1L;
            int metaKeys = -1;
            boolean metaHasLine = false;
            if (metadata != null) {
                metaDuration = longValue(invokeOptional(metadata, "getLong", PUBLIC_DURATION), -1L);
                metaSupport = longValue(invokeOptional(metadata, "getLong", ATOMIC_SUPPORT_EVENTS), -1L);
                Object ks = invokeOptional(metadata, "keySet");
                if (ks instanceof java.util.Set) {
                    metaKeys = ((java.util.Set<?>) ks).size();
                    metaHasLine = ((java.util.Set<?>) ks).contains(META_LINE);
                }
            }

            Object state = invokeOptional(controllerToUse, "getPlaybackState");
            long statePos = -1L; long stateAct = -1L; int stateSt = -1;
            if (state != null) {
                statePos = longValue(invokeOptional(state, "getPosition"), -1L);
                stateAct = longValue(invokeOptional(state, "getActions"), -1L);
                stateSt = intValue(invokeOptional(state, "getState"), -1);
            }

            diag.append(" meta=").append(metadata != null ? "y" : "n")
                .append(" ps=").append(state != null ? "y" : "n").append('\n');
            diag.append("[00:00.30]D2 mDUR=").append(metaDuration)
                .append(" mSE=").append(metaSupport).append('\n');
            diag.append("[00:00.60]D3 psST=").append(stateSt)
                .append(" psPOS=").append(statePos)
                .append(" psACT=").append(Long.toHexString(stateAct)).append('\n');
            diag.append("[00:00.90]D4 keys=").append(metaKeys)
                .append(" ucarLine=").append(metaHasLine ? "y" : "n").append('\n');

            // D5: Read the same DURATION through the compat layer by converting the framework
            // token to a compat token via MediaSessionCompat.Token.fromToken(). This is the
            // exact path Atomic's c0 takes via MediaBrowserCompat -> MediaControllerCompat,
            // so if this value differs from mDUR above, that explains the missing progress bar.
            long compatDUR = -2L;
            String compatCtlStatus = "skip";
            try {
                Class<?> compatTokenClass = Class.forName(
                        "android.support.v4.media.session.MediaSessionCompat$Token");
                Object compatToken = invokeStaticOptional(compatTokenClass, "fromToken", frameworkToken);
                compatCtlStatus = compatToken != null ? "tok=y" : "tok=null";
                if (compatToken != null) {
                    Class<?> controllerCompatClass = Class.forName(
                            "android.support.v4.media.session.MediaControllerCompat");
                    // Print actual ctor param types so we know the exact signature next time
                    StringBuilder ctorTypes = new StringBuilder();
                    for (Constructor<?> ctor : controllerCompatClass.getDeclaredConstructors()) {
                        if (ctor.getParameterTypes().length == 2) {
                            ctorTypes.append(ctor.getParameterTypes()[1].getSimpleName()).append(',');
                        }
                    }
                    compatCtlStatus += " ctorP2=[" + ctorTypes + "]";
                    // Try each 2-arg ctor regardless of param type
                    Object compatCtl = null;
                    for (Constructor<?> ctor : controllerCompatClass.getDeclaredConstructors()) {
                        Class<?>[] pt = ctor.getParameterTypes();
                        if (pt.length == 2) {
                            try {
                                ctor.setAccessible(true);
                                compatCtl = ctor.newInstance(appleApplication(), compatToken);
                                break;
                            } catch (Throwable ignored) {}
                        }
                    }
                    if (compatCtl != null) {
                        Object compatMeta = invokeOptional(compatCtl, "getMetadata");
                        if (compatMeta != null) {
                            compatDUR = longValue(
                                    invokeOptional(compatMeta, "getLong", PUBLIC_DURATION), -1L);
                        }
                        compatCtlStatus += " ok";
                    } else {
                        compatCtlStatus += " noCtl";
                    }
                }
            } catch (Throwable ex) {
                compatCtlStatus = "exc:" + ex.getClass().getSimpleName();
            }
            diag.append("[00:01.20]D5e compat=").append(compatCtlStatus)
                .append(" cDUR=").append(compatDUR);
        } catch (Throwable ex) {
            diag.append(" readErr:").append(ex.getClass().getSimpleName());
        }
        return diag.toString();
    }

    /** Builds the LRC-formatted probe header, computed once per track. */
    private static String diagnosticHeader(Object manager, long generation) {
        String probe = sessionProbeDiag;
        if (sessionProbeGeneration != generation || probe.isEmpty()) {
            probe = probeSessionAsAtomicSees(manager);
            sessionProbeDiag = probe;
            sessionProbeGeneration = generation;
        }
        long ourSupport = -1L;
        long ourDuration = -1L;
        try {
            Object mediaItem = invokeRequired(manager, "a");
            Object metadata = getFieldValue(mediaItem, "d");
            Bundle extras = (Bundle) getFieldValue(metadata, "I");
            if (extras != null) {
                ourSupport = longValue(extras.get(ATOMIC_SUPPORT_EVENTS), -1L);
                ourDuration = longValue(extras.get(PUBLIC_DURATION), -1L);
            }
        } catch (Throwable ignored) {
        }
        return "[00:00.00]" + probe + '\n'
                + "[00:01.20]D5 ourSE=" + ourSupport + " ourDUR=" + ourDuration + '\n'
                + "[00:01.50]D6 " + atomicConnectDiag + '\n';
    }

    private static boolean advertiseAtomicLyricSupport(Object mediaItem) throws Exception {
        if (mediaItem == null) {
            return false;
        }
        Object metadata = getFieldValue(mediaItem, "d");
        if (metadata == null) {
            return false;
        }
        Bundle extras = (Bundle) getFieldValue(metadata, "I");
        if (extras == null) {
            return false;
        }
        long supportEvents = longValue(extras.get(ATOMIC_SUPPORT_EVENTS), 0L);
        extras.putLong(ATOMIC_SUPPORT_EVENTS, supportEvents
                | ATOMIC_BASELINE_SUPPORT_EVENTS | ATOMIC_LYRIC_SUPPORT_EVENT);
        return true;
    }

    private static boolean matchesExpectedMedia(Object manager, long generation, Bundle extras) {
        if (!isCurrent(manager, generation)) {
            return false;
        }
        long expectedQueueId = currentExpectedQueueId;
        return expectedQueueId > 0L && extras != null
                && extras.getLong(APPLE_QUEUE_ID, 0L) == expectedQueueId;
    }

    private static boolean metadataHasAtomicSupport(Object manager, long generation) {
        try {
            Object mediaItem = invokeRequired(manager, "a");
            if (mediaItem == null) {
                return false;
            }
            Object metadata = getFieldValue(mediaItem, "d");
            Bundle extras = (Bundle) getFieldValue(metadata, "I");
            // Only the capability bit lives in MediaMetadata. The ucar cluster keys travel through
            // session Extras, so requiring them here would never be satisfied and would retrigger
            // the reapply loop forever.
            return extras != null
                    && matchesExpectedMedia(manager, generation, extras)
                    && (longValue(extras.get(ATOMIC_SUPPORT_EVENTS), 0L)
                            & ATOMIC_LYRIC_SUPPORT_EVENT) != 0L;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void publishLineExtras(Object manager, String line, long generation) {
        dispatchSessionExtras(manager, generation, line, false, "", "", false);
    }

    private static void publishAtomicClear(Object manager, String mediaId, long generation) {
        dispatchSessionExtras(manager, generation, "", true,
                mediaId == null ? "" : mediaId, "", true);
    }

    private static void publishAtomicExtras(Object manager, String line, String mediaId,
                                            String whole, long generation) {
        String normalizedMediaId = mediaId == null ? "" : mediaId;
        dispatchSessionExtras(manager, generation, line, true,
                normalizedMediaId, whole, false);
    }

    private static void dispatchSessionExtras(final Object manager, final long generation,
                                              final String line, final boolean atomicEvent,
                                              final String mediaId, final String whole,
                                              final boolean atomicClear) {
        if (!isCurrent(manager, generation)) {
            return;
        }
        Runnable publish = new Runnable() {
            @Override
            public void run() {
                publishSessionExtras(manager, generation, line, atomicEvent,
                        mediaId, whole, atomicClear);
            }
        };
        Handler handler = serviceHandler(manager);
        if (handler != null && Looper.myLooper() != handler.getLooper()) {
            handler.post(publish);
        } else {
            publish.run();
        }
    }

    private static void publishSessionExtras(Object manager, long generation, String line,
                                             boolean atomicEvent, String mediaId, String whole,
                                             boolean atomicClear) {
        if (!isCurrent(manager, generation)) {
            return;
        }
        try {
            Object sessionManager = getFieldValue(manager, "a");
            Bundle extras = new Bundle();
            String safeLine = line == null ? "" : line;
            String safeWhole = whole == null ? "" : whole;
            int status;
            synchronized (STATE_LOCK) {
                status = lastStatus == Integer.MIN_VALUE ? STATUS_SUCCESS : lastStatus;
            }

            // Car head unit keys
            extras.putBoolean(EXTRA_ALLOWED, true);
            extras.putString(EXTRA_LINE, safeLine);
            extras.putBoolean(EXTRA_NOTICE, true);

            // Dashboard instrument cluster keys (ucar), delivered through session Extras only.
            // These must never travel in MediaMetadata: republishing the MediaItem resets the
            // native PlaybackState and removes Atomic Player's progress bar. Paginated values
            // are read from the shared lyrics state so line ticks keep carrying the full
            // paginated body instead of blanking it.
            LyricsState clusterState = lyricsState;
            boolean haveCluster = clusterState.clusterTexts.length > 0;
            String clusterWhole = haveCluster ? clusterState.clusterWhole : safeWhole;
            String clusterLine = haveCluster
                    ? lineForPosition(controllerPosition(manager),
                            clusterState.clusterTimes, clusterState.clusterTexts)
                    : safeLine;
            extras.putString(META_LINE, clusterLine);
            extras.putString(META_WHOLE, clusterWhole);
            extras.putLong(META_STATUS, (long) status);

            if (atomicEvent) {
                extras.putString(ATOMIC_ACTION_KEY, ATOMIC_LRC_CHANGE);
                extras.putString(ATOMIC_MEDIA_ID, mediaId == null ? "" : mediaId);
                extras.putString(ATOMIC_LYRIC, DIAGNOSTIC_MODE
                        ? diagnosticHeader(manager, generation) + safeWhole
                        : safeWhole);
            }
            if (!isCurrent(manager, generation)) {
                return;
            }
            invokeRequired(sessionManager, "j", extras);
        } catch (Throwable ignored) {
        }
    }

    private static boolean isExpectedAtomicMediaId(String mediaId, long generation) {
        synchronized (STATE_LOCK) {
            return generation == GENERATION.get()
                    && currentAtomicMediaIdGeneration == generation
                    && currentAtomicMediaId.equals(mediaId);
        }
    }

    private static final class AtomicActionClearTask implements Runnable {
        private final Object manager;
        private final long generation;
        private final long sequence;

        AtomicActionClearTask(Object manager, long generation, long sequence) {
            this.manager = manager;
            this.generation = generation;
            this.sequence = sequence;
        }

        @Override
        public void run() {
            if (!isCurrent(manager, generation) || sequence != ATOMIC_EVENT_SEQUENCE.get()) {
                return;
            }
            String line;
            synchronized (STATE_LOCK) {
                line = lastLine;
            }
            publishLineExtras(manager, line == null ? "" : line, generation);
        }
    }

    private static Object currentPlaybackItem(Object manager) throws Exception {
        Object mediaItem = invokeRequired(manager, "a");
        if (mediaItem == null) {
            return null;
        }
        Object metadata = getFieldValue(mediaItem, "d");
        Class<?> converter = Class.forName("com.apple.android.music.player.O");
        Method method = findCompatibleMethod(converter, "b", new Object[]{metadata}, true);
        return method.invoke(null, metadata);
    }

    private static Application appleApplication() throws Exception {
        Class<?> companion = Class.forName("com.apple.android.music.AppleMusicApplication$a");
        Object application = invokeStaticOptional(companion, "c");
        if (!(application instanceof Application)) {
            application = invokeStaticOptional(companion, "a");
        }
        if (!(application instanceof Application)) {
            throw new IllegalStateException("Apple Music application is unavailable");
        }
        return (Application) application;
    }

    private static long controllerPosition(Object manager) {
        return controllerLong(manager, "getCurrentPosition");
    }

    private static long controllerDuration(Object manager) {
        long dur = extractDurationFromItem(currentQueueItem);
        if (dur > 0L) {
            lastKnownDuration = dur;
            return dur;
        }
        dur = extractDurationFromItem(currentPlaybackItem);
        if (dur > 0L) {
            lastKnownDuration = dur;
            return dur;
        }
        dur = controllerLong(manager, "getDuration");
        if (dur > 0L) {
            lastKnownDuration = dur;
            return dur;
        }
        return lastKnownDuration;
    }

    private static long extractDurationFromItem(Object queueOrPlayerItem) {
        if (queueOrPlayerItem == null) return 0L;
        long dur = longValue(invokeOptional(queueOrPlayerItem, "getDuration"), 0L);
        if (dur > 0L) return dur;
        dur = longValue(invokeOptional(queueOrPlayerItem, "getDurationInMillis"), 0L);
        if (dur > 0L) return dur;
        Object innerItem = invokeOptional(queueOrPlayerItem, "getItem");
        if (innerItem != null) {
            dur = longValue(invokeOptional(innerItem, "getDuration"), 0L);
            if (dur > 0L) return dur;
            dur = longValue(invokeOptional(innerItem, "getDurationInMillis"), 0L);
            if (dur > 0L) return dur;
        }
        return 0L;
    }

    private static long controllerLong(Object manager, String method) {
        try {
            Object sessionManager = getFieldValue(manager, "a");
            Object controller = getFieldValue(sessionManager, "h");
            return longValue(invokeRequired(controller, method), 0L);
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private static Handler serviceHandler(Object manager) {
        try {
            Object handler = getFieldValue(manager, "b");
            return handler instanceof Handler ? (Handler) handler : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String queueKey(Object queueItem) {
        if (queueItem == null) {
            return "";
        }
        long queueId = longValue(invokeOptional(queueItem, "getPlaybackQueueId"), 0L);
        if (queueId > 0L) {
            return "queue:" + queueId;
        }
        Object item = invokeOptional(queueItem, "getItem");
        String id = stringValue(invokeOptional(item, "getSubscriptionStoreId"));
        if (id.isEmpty()) {
            id = stringValue(invokeOptional(item, "getPersistentId"));
        }
        if (!id.isEmpty()) {
            return "item:" + id;
        }
        String title = stringValue(invokeOptional(item, "getTitle"));
        return title.isEmpty() ? "" : "title:" + title;
    }

    private static String queueMediaId(Object queueItem) {
        Object item = invokeOptional(queueItem, "getItem");
        String id = stringValue(invokeOptional(item, "getSubscriptionStoreId"));
        if (id.isEmpty()) {
            long persistentId = longValue(invokeOptional(item, "getPersistentId"), 0L);
            if (persistentId != 0L) {
                id = String.valueOf(persistentId);
            }
        }
        return id;
    }

    private static String resolveAtomicMediaId(Object manager, long generation) {
        if (!isCurrent(manager, generation)) {
            return "";
        }
        String resolved = "";
        String fallback;
        Object playbackItem;
        long expectedQueueId;
        synchronized (STATE_LOCK) {
            if (!isCurrent(manager, generation)) {
                return "";
            }
            playbackItem = currentPlaybackItemGeneration == generation ? currentPlaybackItem : null;
            expectedQueueId = currentExpectedQueueId;
            fallback = currentAtomicMediaIdGeneration == generation ? currentAtomicMediaId : "";
        }
        if (expectedQueueId <= 0L) {
            return fallback;
        }

        try {
            Object mediaItem = invokeRequired(manager, "a");
            Object metadata = getFieldValue(mediaItem, "d");
            Bundle extras = (Bundle) getFieldValue(metadata, "I");
            long queueId = extras == null ? 0L : extras.getLong(APPLE_QUEUE_ID, 0L);
            if (queueId == expectedQueueId) {
                resolved = stringValue(extras.getString(PUBLIC_MEDIA_ID));
                if (resolved.isEmpty()) {
                    resolved = stringValue(extras.getString(APPLE_MEDIA_ID));
                }
                if (resolved.isEmpty()) {
                    resolved = stringValue(getFieldValue(mediaItem, "a"));
                }
            }
        } catch (Throwable ignored) {
        }
        long playbackQueueId = longValue(invokeOptional(playbackItem, "getQueueId"), 0L);
        if (resolved.isEmpty() && playbackItem != null && playbackQueueId == expectedQueueId) {
            resolved = playbackItemMediaId(playbackItem);
        }

        synchronized (STATE_LOCK) {
            if (!isCurrent(manager, generation)) {
                return "";
            }
            if (currentExpectedQueueId != expectedQueueId) {
                return currentAtomicMediaIdGeneration == generation ? currentAtomicMediaId : "";
            }
            if (!resolved.isEmpty()) {
                currentAtomicMediaId = resolved;
                currentAtomicMediaIdGeneration = generation;
            }
            return currentAtomicMediaIdGeneration == generation ? currentAtomicMediaId : "";
        }
    }

    private static String playbackItemMediaId(Object playbackItem) {
        String id = stringValue(invokeOptional(playbackItem, "getSubscriptionStoreId"));
        if (id.isEmpty()) {
            id = stringValue(invokeOptional(playbackItem, "getId"));
        }
        if (id.isEmpty()) {
            long persistentId = longValue(invokeOptional(playbackItem, "getPersistentId"), 0L);
            if (persistentId != 0L) {
                id = String.valueOf(persistentId);
            }
        }
        return id;
    }

    private static String lineForPosition(long position, long[] times, String[] texts) {
        int low = 0;
        int high = times.length - 1;
        int result = -1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            if (times[middle] <= position) {
                result = middle;
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        return result >= 0 && result < texts.length ? texts[result] : "";
    }

    private static String formatTime(long millis) {
        long safe = Math.max(0L, millis);
        long minutes = safe / 60000L;
        long seconds = (safe % 60000L) / 1000L;
        long centiseconds = (safe % 1000L) / 10L;
        return String.format(Locale.US, "[%02d:%02d.%02d]", minutes, seconds, centiseconds);
    }

    private static long fractionMillis(String fraction) {
        if (fraction == null || fraction.isEmpty()) {
            return 0L;
        }
        if (fraction.length() == 1) {
            return Long.parseLong(fraction) * 100L;
        }
        if (fraction.length() == 2) {
            return Long.parseLong(fraction) * 10L;
        }
        return Long.parseLong(fraction.substring(0, 3));
    }

    private static String plainText(String html) {
        if (html == null || html.isEmpty()) {
            return "";
        }
        try {
            return Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString().replace('\u00a0', ' ').trim();
        } catch (Throwable ignored) {
            return html.replaceAll("<[^>]+>", "").trim();
        }
    }

    private static boolean isCurrent(Object manager, long generation) {
        return manager != null && manager == currentManager && generation == GENERATION.get();
    }

    private static void resetPublishCache() {
        synchronized (STATE_LOCK) {
            lyricsState = EMPTY_LYRICS;
            lastLine = null;
            lastWhole = null;
            lastStatus = Integer.MIN_VALUE;
            metadataWhole = null;
            metadataStatus = Integer.MIN_VALUE;
            pendingForceMetadata = false;
            pendingAtomicPublish = false;
            activeLoadGeneration = -1L;
            activeLoadManager = null;
            loadRetryCount = 0;
            currentPlaybackItemGeneration = -1L;
            currentAtomicMediaId = "";
            if (DIAGNOSTIC_MODE) {
                sessionProbeDiag = "";
                sessionProbeGeneration = -1L;
                // atomicConnectDiag intentionally NOT reset: Atomic Player stays connected
                // across track changes. Only trackStartUptime resets so timestamps are
                // relative to the new track.
                trackStartUptime = android.os.SystemClock.uptimeMillis();
            }
            currentAtomicMediaIdGeneration = -1L;
            atomicWhole = null;
            atomicStatus = Integer.MIN_VALUE;
            ATOMIC_EVENT_SEQUENCE.incrementAndGet();
            ATOMIC_STATE_SEQUENCE.incrementAndGet();
        }
    }

    private static boolean safeEquals(Object left, Object right) {
        return left == right || (left != null && left.equals(right));
    }

    private static Object invokeRequired(Object target, String name, Object... args) throws Exception {
        if (target == null) {
            throw new NullPointerException(name + " target");
        }
        Method method = findCompatibleMethod(target.getClass(), name, args, false);
        return method.invoke(target, args);
    }

    private static Object invokeOptional(Object target, String name, Object... args) {
        try {
            return invokeRequired(target, name, args);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object invokeStaticOptional(Class<?> type, String name, Object... args) {
        try {
            Method method = findCompatibleMethod(type, name, args, true);
            return method.invoke(null, args);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Method findCompatibleMethod(Class<?> type, String name, Object[] args, boolean requireStatic)
            throws NoSuchMethodException {
        for (Class<?> cursor = type; cursor != null; cursor = cursor.getSuperclass()) {
            Method[] methods = cursor.getDeclaredMethods();
            for (Method method : methods) {
                if (!method.getName().equals(name) || method.getParameterTypes().length != args.length) {
                    continue;
                }
                if (requireStatic && !Modifier.isStatic(method.getModifiers())) {
                    continue;
                }
                if (parametersMatch(method.getParameterTypes(), args)) {
                    method.setAccessible(true);
                    return method;
                }
            }
        }
        throw new NoSuchMethodException(type.getName() + "." + name);
    }

    private static boolean parametersMatch(Class<?>[] parameterTypes, Object[] args) {
        for (int index = 0; index < parameterTypes.length; index++) {
            Object argument = args[index];
            if (argument == null) {
                if (parameterTypes[index].isPrimitive()) {
                    return false;
                }
                continue;
            }
            Class<?> parameter = wrap(parameterTypes[index]);
            if (!parameter.isInstance(argument)) {
                return false;
            }
        }
        return true;
    }

    private static Class<?> wrap(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        if (type == Integer.TYPE) return Integer.class;
        if (type == Long.TYPE) return Long.class;
        if (type == Boolean.TYPE) return Boolean.class;
        if (type == Float.TYPE) return Float.class;
        if (type == Double.TYPE) return Double.class;
        if (type == Short.TYPE) return Short.class;
        if (type == Byte.TYPE) return Byte.class;
        if (type == Character.TYPE) return Character.class;
        return type;
    }

    private static Object constructCompatible(Class<?> type, Object argument) throws Exception {
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            Class<?>[] parameters = constructor.getParameterTypes();
            if (parameters.length == 1 && (argument == null || wrap(parameters[0]).isInstance(argument))) {
                constructor.setAccessible(true);
                return constructor.newInstance(argument);
            }
        }
        throw new NoSuchMethodException(type.getName() + " constructor");
    }

    private static Object getFieldValue(Object target, String name) throws Exception {
        Field field = findField(target.getClass(), name);
        return field.get(target);
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> cursor = type; cursor != null; cursor = cursor.getSuperclass()) {
            try {
                Field field = cursor.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(type.getName() + "." + name);
    }

    private static boolean booleanValue(Object value) {
        return value instanceof Boolean && ((Boolean) value).booleanValue();
    }

    private static int intValue(Object value, int fallback) {
        return value instanceof Number ? ((Number) value).intValue() : fallback;
    }

    private static long longValue(Object value, long fallback) {
        return value instanceof Number ? ((Number) value).longValue() : fallback;
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
