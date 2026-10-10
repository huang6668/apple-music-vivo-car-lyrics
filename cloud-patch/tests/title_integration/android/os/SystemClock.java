package android.os;

public final class SystemClock {
    private SystemClock() {}
    public static long uptimeMillis() { return Handler.now(); }
}
