package J4;

public class Z2 {
    public int notificationRefreshes;
    public g2 lastSession;
    public boolean lastForeground;

    public void g(g2 session, boolean foreground) {
        notificationRefreshes++;
        lastSession = session;
        lastForeground = foreground;
    }
}
