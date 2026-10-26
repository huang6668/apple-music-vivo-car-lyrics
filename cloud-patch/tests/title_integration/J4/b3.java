package J4;

public class b3 {
    public int notificationRefreshes;
    public k2 lastSession;
    public boolean lastForeground;

    public void g(k2 session, boolean foreground) {
        notificationRefreshes++;
        lastSession = session;
        lastForeground = foreground;
    }
}
