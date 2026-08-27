package cn.kuwo.player;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Proxies the official Apple Music session through a session owned by the KuWo package.
 * The first build intentionally uses manual LRC input so the car protocol can be tested
 * independently from Apple Music's private lyric classes.
 */
final class BridgeCore {
    private static final String APPLE_PACKAGE = "com.apple.android.music";
    private static final String BRIDGE_PACKAGE = "cn.kuwo.player";
    private static final String META_LINE = "ucar.media.metadata.LYRICS_LINE";
    private static final String META_WHOLE = "ucar.media.metadata.LYRICS_WHOLE";
    private static final String META_STATUS = "ucar.media.metadata.LYRICS_STATUS";
    private static final String META_EXTRAS = "android.media.metadata.EXTRAS";
    private static final String EXTRA_LINE = "music.media.extras.LYRIC";
    private static final String EXTRA_ALLOWED = "music.media.extras.LYRIC_IS_ALLOWED";
    private static final String EXTRA_NOTICE = "music.media.extras.NOTICE_CAR";
    private static final Pattern LRC_TIME = Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?\\]");
    private static final long TICK_MS = 250L;
    private static BridgeCore instance;

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refreshLineAndState();
            main.postDelayed(this, TICK_MS);
        }
    };

    private MediaSessionManager sessionManager;
    private MediaController appleController;
    private MediaController.Callback appleCallback;
    private MediaSession proxySession;
    private AppleMusicNotificationListener listener;
    private String trackKey = "";
    private String sourceStatus = "等待通知读取权限";
    private String manualLyrics = "";
    private String manualLyricsTrackKey = "";
    private String sourceLyrics = "";
    private String wholeLyrics = "";
    private long[] lyricTimes = new long[0];
    private String[] lyricLines = new String[0];
    private String lastPublishedLine = null;
    private String lastMetadataKey = null;

    static synchronized BridgeCore get(Context context) {
        if (instance == null) {
            instance = new BridgeCore(context.getApplicationContext());
        }
        return instance;
    }

    private BridgeCore(Context context) {
        this.context = context;
    }

    synchronized void ensureSession(Context ignored) {
        if (proxySession != null) {
            if (!proxySession.isActive()) {
                proxySession.setActive(true);
            }
            main.removeCallbacks(tick);
            main.post(tick);
            return;
        }
        proxySession = new MediaSession(context, "KuWoMediaBridge");
        proxySession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        proxySession.setCallback(new MediaSession.Callback() {
            @Override
            public void onPlay() {
                sendToApple(new ControllerCommand() {
                    @Override
                    public void run(MediaController.TransportControls controls) {
                        controls.play();
                    }
                });
            }

            @Override
            public void onPause() {
                sendToApple(new ControllerCommand() {
                    @Override
                    public void run(MediaController.TransportControls controls) {
                        controls.pause();
                    }
                });
            }

            @Override
            public void onStop() {
                sendToApple(new ControllerCommand() {
                    @Override
                    public void run(MediaController.TransportControls controls) {
                        controls.stop();
                    }
                });
            }

            @Override
            public void onSkipToNext() {
                sendToApple(new ControllerCommand() {
                    @Override
                    public void run(MediaController.TransportControls controls) {
                        controls.skipToNext();
                    }
                });
            }

            @Override
            public void onSkipToPrevious() {
                sendToApple(new ControllerCommand() {
                    @Override
                    public void run(MediaController.TransportControls controls) {
                        controls.skipToPrevious();
                    }
                });
            }

            @Override
            public void onSeekTo(final long position) {
                final long target = Math.max(0L, position);
                sendToApple(new ControllerCommand() {
                    @Override
                    public void run(MediaController.TransportControls controls) {
                        controls.seekTo(target);
                    }
                });
                publishProxyPosition(target);
                publishLine(target, true);
                main.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        refreshFromApple(false);
                    }
                }, 180L);
            }

            @Override
            public void onFastForward() {
                sendToApple(new ControllerCommand() {
                    @Override
                    public void run(MediaController.TransportControls controls) {
                        controls.fastForward();
                    }
                });
                refreshAfterTransportCommand();
            }

            @Override
            public void onRewind() {
                sendToApple(new ControllerCommand() {
                    @Override
                    public void run(MediaController.TransportControls controls) {
                        controls.rewind();
                    }
                });
                refreshAfterTransportCommand();
            }
        });
        proxySession.setActive(true);
        main.post(tick);
    }

    synchronized MediaSession.Token getSessionToken() {
        ensureSession(context);
        return proxySession.getSessionToken();
    }

    synchronized void onListenerConnected(AppleMusicNotificationListener service) {
        listener = service;
        ensureSession(service);
        sessionManager = (MediaSessionManager) context.getSystemService(Context.MEDIA_SESSION_SERVICE);
        sourceStatus = "已连接通知读取服务";
        try {
            sessionManager.addOnActiveSessionsChangedListener(activeSessionsListener, main,
                    new ComponentName(context, AppleMusicNotificationListener.class));
        } catch (Throwable error) {
            sourceStatus = "媒体会话监听失败: " + error.getClass().getSimpleName();
        }
        refreshControllers();
    }

    synchronized void onListenerDisconnected() {
        sourceStatus = "通知读取服务已断开";
        detachAppleController();
    }

    synchronized void onListenerDestroyed() {
        if (sessionManager != null) {
            try {
                sessionManager.removeOnActiveSessionsChangedListener(activeSessionsListener);
            } catch (Throwable ignored) {
            }
        }
        listener = null;
        detachAppleController();
        sourceStatus = "等待重新启用通知读取权限";
    }

    synchronized void setManualLyrics(String raw) {
        ensureSession(context);
        manualLyrics = raw == null ? "" : raw;
        manualLyricsTrackKey = trackKey;
        parseLyrics(manualLyrics);
        lastMetadataKey = null;
        refreshFromApple(true);
        sourceStatus = manualLyrics.trim().isEmpty() ? "已清除手动歌词" : "已设置手动歌词";
    }

    synchronized void clearProxy() {
        manualLyrics = "";
        manualLyricsTrackKey = "";
        sourceLyrics = "";
        parseLyrics("");
        detachAppleController();
        if (proxySession != null) {
            proxySession.setActive(false);
        }
        sourceStatus = "代理会话已停止";
    }

    synchronized String status() {
        String player = appleController == null ? "无 Apple Music 会话" : "Apple Music 已连接";
        return sourceStatus + "\n" + player + "\n代理包名: " + BRIDGE_PACKAGE;
    }

    private final MediaSessionManager.OnActiveSessionsChangedListener activeSessionsListener =
            new MediaSessionManager.OnActiveSessionsChangedListener() {
                @Override
                public void onActiveSessionsChanged(List<MediaController> controllers) {
                    selectAppleController(controllers == null ? Collections.<MediaController>emptyList() : controllers);
                }
            };

    private synchronized void refreshControllers() {
        if (sessionManager == null || listener == null) {
            return;
        }
        try {
            List<MediaController> controllers = sessionManager.getActiveSessions(
                    new ComponentName(context, AppleMusicNotificationListener.class));
            selectAppleController(controllers == null ? Collections.<MediaController>emptyList() : controllers);
        } catch (Throwable error) {
            sourceStatus = "读取媒体会话失败: " + error.getClass().getSimpleName();
        }
    }

    private synchronized void selectAppleController(List<MediaController> controllers) {
        MediaController selected = null;
        for (MediaController controller : controllers) {
            if (APPLE_PACKAGE.equals(controller.getPackageName())) {
                selected = controller;
                break;
            }
        }
        if (selected == appleController) {
            refreshFromApple(false);
            return;
        }
        detachAppleController();
        appleController = selected;
        if (appleController == null) {
            sourceStatus = "未找到 Apple Music 媒体会话";
            publishEmptyState();
            return;
        }
        appleCallback = new MediaController.Callback() {
            @Override
            public void onMetadataChanged(MediaMetadata metadata) {
                refreshFromApple(true);
            }

            @Override
            public void onPlaybackStateChanged(PlaybackState state) {
                refreshFromApple(false);
            }
        };
        appleController.registerCallback(appleCallback, main);
        sourceStatus = "已找到 Apple Music 媒体会话";
        refreshFromApple(true);
    }

    private synchronized void detachAppleController() {
        if (appleController != null && appleCallback != null) {
            try {
                appleController.unregisterCallback(appleCallback);
            } catch (Throwable ignored) {
            }
        }
        appleController = null;
        appleCallback = null;
        trackKey = "";
        sourceLyrics = "";
        wholeLyrics = "";
        lyricTimes = new long[0];
        lyricLines = new String[0];
        lastPublishedLine = null;
        lastMetadataKey = null;
    }

    private synchronized void refreshFromApple(boolean forceMetadata) {
        ensureSession(context);
        if (appleController == null || proxySession == null) {
            publishEmptyState();
            return;
        }
        MediaMetadata source = appleController.getMetadata();
        String nextTrackKey = makeTrackKey(source);
        boolean trackChanged = !TextUtils.equals(trackKey, nextTrackKey);
        trackKey = nextTrackKey;
        if (trackChanged) {
            if (!manualLyrics.isEmpty() && manualLyricsTrackKey.isEmpty()) {
                manualLyricsTrackKey = nextTrackKey;
            } else if (!TextUtils.equals(manualLyricsTrackKey, nextTrackKey)) {
                manualLyrics = "";
                manualLyricsTrackKey = "";
                sourceLyrics = "";
                parseLyrics("");
            }
        }
        if (trackChanged || forceMetadata || !TextUtils.equals(lastMetadataKey, nextTrackKey)) {
            updateMetadata(source);
            lastMetadataKey = nextTrackKey;
        }
        updatePlaybackState();
        publishLine(currentPosition());
    }

    private synchronized void updateMetadata(MediaMetadata source) {
        if (source == null) {
            publishEmptyState();
            return;
        }
        MediaMetadata.Builder builder = new MediaMetadata.Builder();
        copyText(source, builder, MediaMetadata.METADATA_KEY_TITLE);
        copyText(source, builder, MediaMetadata.METADATA_KEY_ARTIST);
        copyText(source, builder, MediaMetadata.METADATA_KEY_ALBUM);
        copyText(source, builder, MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
        copyText(source, builder, MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
        copyText(source, builder, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
        copyText(source, builder, MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION);
        if (source.containsKey(MediaMetadata.METADATA_KEY_DURATION)) {
            builder.putLong(MediaMetadata.METADATA_KEY_DURATION,
                    source.getLong(MediaMetadata.METADATA_KEY_DURATION));
        }
        Bitmap art = source.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (art == null) {
            art = source.getBitmap(MediaMetadata.METADATA_KEY_ART);
        }
        if (art != null) {
            builder.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art);
            builder.putBitmap(MediaMetadata.METADATA_KEY_ART, art);
        }
        String whole = wholeLyricsFromSource(source);
        String line = currentLyricLine(currentPosition());
        // Custom string keys are a compatibility fallback for car builds that read
        // MediaMetadata directly instead of MediaController.getExtras().
        try {
            builder.putString(META_LINE, line);
            builder.putString(META_WHOLE, whole);
            builder.putLong(META_STATUS, whole.isEmpty() ? 1L : 0L);
        } catch (IllegalArgumentException ignored) {
            // Some platform builds reject non-standard metadata key types.
        }
        proxySession.setMetadata(builder.build());
        Bundle sessionExtras = new Bundle();
        sessionExtras.putString(META_LINE, line);
        sessionExtras.putString(META_WHOLE, whole);
        sessionExtras.putLong(META_STATUS, whole.isEmpty() ? 1L : 0L);
        sessionExtras.putBoolean(EXTRA_ALLOWED, true);
        sessionExtras.putBoolean(EXTRA_NOTICE, true);
        sessionExtras.putString(EXTRA_LINE, currentLyricLine(currentPosition()));
        proxySession.setExtras(sessionExtras);
    }

    private synchronized void updatePlaybackState() {
        if (appleController == null || proxySession == null) {
            return;
        }
        PlaybackState source = appleController.getPlaybackState();
        if (source == null) {
            return;
        }
        PlaybackState.Builder builder = new PlaybackState.Builder();
        builder.setActions(source.getActions());
        builder.setState(source.getState(), source.getPosition(), source.getPlaybackSpeed());
        builder.setBufferedPosition(source.getBufferedPosition());
        builder.setErrorMessage(source.getErrorMessage());
        proxySession.setPlaybackState(builder.build());
    }

    private synchronized void refreshLineAndState() {
        if (appleController == null || proxySession == null) {
            return;
        }
        updatePlaybackState();
        publishLine(currentPosition());
    }

    private synchronized void publishLine(long position) {
        publishLine(position, false);
    }

    private synchronized void publishLine(long position, boolean force) {
        String line = currentLyricLine(position);
        if (!force && TextUtils.equals(line, lastPublishedLine)) {
            return;
        }
        lastPublishedLine = line;
        if (proxySession == null) {
            return;
        }
        Bundle extras = new Bundle();
        extras.putString(META_LINE, line);
        String whole = effectiveWholeLyrics();
        extras.putString(META_WHOLE, whole);
        extras.putLong(META_STATUS, whole.isEmpty() ? 1L : 0L);
        extras.putBoolean(EXTRA_ALLOWED, true);
        extras.putBoolean(EXTRA_NOTICE, true);
        extras.putString(EXTRA_LINE, line);
        proxySession.setExtras(extras);
    }

    private synchronized void publishProxyPosition(long position) {
        if (proxySession == null) {
            return;
        }
        PlaybackState source = appleController == null ? null : appleController.getPlaybackState();
        if (source == null) {
            return;
        }
        PlaybackState.Builder builder = new PlaybackState.Builder();
        builder.setActions(source.getActions());
        builder.setState(source.getState(), position, source.getPlaybackSpeed());
        builder.setBufferedPosition(source.getBufferedPosition());
        builder.setErrorMessage(source.getErrorMessage());
        proxySession.setPlaybackState(builder.build());
    }

    private void refreshAfterTransportCommand() {
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                synchronized (BridgeCore.this) {
                    refreshFromApple(false);
                    publishLine(currentPosition(), true);
                }
            }
        }, 180L);
    }

    private synchronized void publishEmptyState() {
        if (proxySession == null) {
            return;
        }
        MediaMetadata.Builder builder = new MediaMetadata.Builder();
        proxySession.setMetadata(builder.build());
        Bundle sessionExtras = new Bundle();
        sessionExtras.putString(META_LINE, "");
        sessionExtras.putString(META_WHOLE, "");
        sessionExtras.putLong(META_STATUS, 1L);
        sessionExtras.putBoolean(EXTRA_ALLOWED, true);
        sessionExtras.putBoolean(EXTRA_NOTICE, true);
        sessionExtras.putString(EXTRA_LINE, "");
        proxySession.setExtras(sessionExtras);
    }

    private synchronized long currentPosition() {
        PlaybackState state = appleController == null ? null : appleController.getPlaybackState();
        if (state == null) {
            return 0L;
        }
        long delta = System.currentTimeMillis() - state.getLastPositionUpdateTime();
        if (state.getState() == PlaybackState.STATE_PLAYING && state.getPlaybackSpeed() > 0f) {
            return Math.max(0L, state.getPosition() + (long) (delta * state.getPlaybackSpeed()));
        }
        return Math.max(0L, state.getPosition());
    }

    private synchronized String wholeLyricsFromSource(MediaMetadata source) {
        if (hasManualLyricsForCurrentTrack()) {
            return wholeLyrics;
        }
        Bundle extras = source == null ? null : source.getBundle(META_EXTRAS);
        String sourceWhole = extras == null ? "" : safeString(extras.getString(META_WHOLE));
        if (!TextUtils.equals(sourceLyrics, sourceWhole)) {
            sourceLyrics = sourceWhole;
            parseLyrics(sourceWhole);
        }
        return sourceWhole;
    }

    private synchronized String currentLyricLine(long position) {
        if (lyricTimes.length > 0 && lyricLines.length > 0) {
            int low = 0;
            int high = lyricTimes.length - 1;
            int result = -1;
            while (low <= high) {
                int middle = (low + high) >>> 1;
                if (lyricTimes[middle] <= position) {
                    result = middle;
                    low = middle + 1;
                } else {
                    high = middle - 1;
                }
            }
            return result >= 0 && result < lyricLines.length ? lyricLines[result] : "";
        }
        MediaMetadata metadata = appleController == null ? null : appleController.getMetadata();
        Bundle extras = metadata == null ? null : metadata.getBundle(META_EXTRAS);
        return extras == null ? "" : safeString(extras.getString(META_LINE));
    }

    private synchronized String effectiveWholeLyrics() {
        return hasManualLyricsForCurrentTrack() ? wholeLyrics : sourceLyrics;
    }

    private synchronized boolean hasManualLyricsForCurrentTrack() {
        return !manualLyrics.trim().isEmpty()
                && TextUtils.equals(manualLyricsTrackKey, trackKey);
    }

    private synchronized void parseLyrics(String raw) {
        ArrayList<Long> times = new ArrayList<>();
        ArrayList<String> lines = new ArrayList<>();
        for (String row : raw.replace("\r", "").split("\n")) {
            Matcher matcher = LRC_TIME.matcher(row);
            String clean = matcher.replaceAll("").trim();
            matcher.reset();
            boolean found = false;
            while (matcher.find()) {
                found = true;
                long minutes = parseLong(matcher.group(1));
                long seconds = parseLong(matcher.group(2));
                long fraction = parseFraction(matcher.group(3));
                if (!clean.isEmpty()) {
                    times.add(minutes * 60000L + seconds * 1000L + fraction);
                    lines.add(clean);
                }
            }
            if (!found && !clean.isEmpty()) {
                times.add(0L);
                lines.add(clean);
            }
        }
        ArrayList<Integer> order = new ArrayList<>();
        for (int i = 0; i < times.size(); i++) {
            order.add(i);
        }
        Collections.sort(order, new Comparator<Integer>() {
            @Override
            public int compare(Integer left, Integer right) {
                return Long.compare(times.get(left), times.get(right));
            }
        });
        lyricTimes = new long[order.size()];
        lyricLines = new String[order.size()];
        StringBuilder normalized = new StringBuilder();
        for (int i = 0; i < order.size(); i++) {
            int sourceIndex = order.get(i);
            lyricTimes[i] = times.get(sourceIndex);
            lyricLines[i] = lines.get(sourceIndex);
            normalized.append(formatTime(lyricTimes[i])).append(lyricLines[i]);
            if (i + 1 < order.size()) {
                normalized.append('\n');
            }
        }
        wholeLyrics = normalized.toString();
        lastPublishedLine = null;
    }

    private void sendToApple(ControllerCommand command) {
        MediaController controller;
        synchronized (this) {
            controller = appleController;
        }
        if (controller != null) {
            try {
                command.run(controller.getTransportControls());
            } catch (Throwable ignored) {
            }
        }
    }

    private interface ControllerCommand {
        void run(MediaController.TransportControls controls);
    }

    private static void copyText(MediaMetadata source, MediaMetadata.Builder target, String key) {
        if (source.containsKey(key)) {
            CharSequence value = source.getText(key);
            if (value != null) {
                target.putText(key, value);
            }
        }
    }

    private static String makeTrackKey(MediaMetadata metadata) {
        if (metadata == null) {
            return "";
        }
        return safeString(metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)) + "|"
                + safeString(metadata.getString(MediaMetadata.METADATA_KEY_TITLE)) + "|"
                + safeString(metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)) + "|"
                + safeString(metadata.getString(MediaMetadata.METADATA_KEY_ALBUM));
    }

    private static String safeString(String value) {
        return value == null ? "" : value;
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    private static long parseFraction(String value) {
        if (TextUtils.isEmpty(value)) {
            return 0L;
        }
        long number = parseLong(value);
        if (value.length() == 1) {
            return number * 100L;
        }
        if (value.length() == 2) {
            return number * 10L;
        }
        return number;
    }

    private static String formatTime(long millis) {
        long safe = Math.max(0L, millis);
        long minutes = safe / 60000L;
        long seconds = (safe % 60000L) / 1000L;
        long centiseconds = (safe % 1000L) / 10L;
        return String.format(Locale.US, "[%02d:%02d.%02d]", minutes, seconds, centiseconds);
    }
}
