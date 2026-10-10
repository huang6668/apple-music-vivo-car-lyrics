package J4;

public class Y2 {
    public int notificationRefreshes;
    public f2 lastSession;
    public boolean lastForeground;

    public void g(f2 session, boolean foreground) {
        notificationRefreshes++;
        lastSession = session;
        lastForeground = foreground;
    }
}
