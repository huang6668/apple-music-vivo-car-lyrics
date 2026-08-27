package cn.kuwo.player;

import android.media.browse.MediaBrowser;
import android.service.media.MediaBrowserService;

import java.util.Collections;

/** Minimal browser endpoint expected by some KuWo-specific car integrations. */
public final class KuwoBrowserService extends MediaBrowserService {
    @Override
    public void onCreate() {
        super.onCreate();
        BridgeCore core = BridgeCore.get(this);
        core.ensureSession(this);
        setSessionToken(core.getSessionToken());
    }

    @Override
    public BrowserRoot onGetRoot(String clientPackageName, int clientUid, android.os.Bundle rootHints) {
        return new BrowserRoot("root", null);
    }

    @Override
    public void onLoadChildren(String parentId, Result<java.util.List<MediaBrowser.MediaItem>> result) {
        result.sendResult(Collections.<MediaBrowser.MediaItem>emptyList());
    }
}
