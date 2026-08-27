package cn.kuwo.player;

import android.service.notification.NotificationListenerService;

/** Grants the bridge access to active media sessions after the user enables notification access. */
public final class AppleMusicNotificationListener extends NotificationListenerService {
    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        BridgeCore.get(this).onListenerConnected(this);
    }

    @Override
    public void onListenerDisconnected() {
        BridgeCore.get(this).onListenerDisconnected();
        super.onListenerDisconnected();
    }

    @Override
    public void onDestroy() {
        BridgeCore.get(this).onListenerDestroyed();
        super.onDestroy();
    }
}
