package J4;

public class d3 {
    public int notificationRefreshes;
    public m2 lastSession;
    public boolean lastForeground;

    public void g(m2 session, boolean foreground) {
        notificationRefreshes++;
        lastSession = session;
        lastForeground = foreground;
    }
}
