package android.os;

import java.util.PriorityQueue;

/** Deterministic main queue: delayed tasks run only when the test advances time. */
public final class Handler {
    private static final PriorityQueue<Task> TASKS = new PriorityQueue<Task>();
    private static long now;
    private static long sequence;
    private final Looper looper;

    public Handler(Looper looper) { this.looper = looper; }
    public Looper getLooper() { return looper; }
    public boolean post(Runnable runnable) { return postDelayed(runnable, 0L); }
    public boolean postDelayed(Runnable runnable, long delay) {
        return enqueue(runnable, delay);
    }
    private static synchronized boolean enqueue(Runnable runnable, long delay) {
        TASKS.add(new Task(now + Math.max(0L, delay), sequence++, runnable));
        return true;
    }
    public static synchronized void reset() {
        TASKS.clear();
        now = 0L;
        sequence = 0L;
    }
    public static synchronized long now() { return now; }
    public static synchronized int pendingCount() { return TASKS.size(); }
    public static synchronized void drain() { advanceBy(0L); }
    public static synchronized void advanceBy(long elapsed) {
        if (elapsed < 0L) throw new IllegalArgumentException("negative time");
        long target = now + elapsed;
        int remaining = 1000;
        while (!TASKS.isEmpty() && TASKS.peek().when <= target) {
            if (--remaining == 0) throw new AssertionError("unbounded main queue");
            Task task = TASKS.remove();
            now = task.when;
            task.runnable.run();
        }
        now = target;
    }
    private static final class Task implements Comparable<Task> {
        final long when;
        final long sequence;
        final Runnable runnable;
        Task(long when, long sequence, Runnable runnable) {
            this.when = when;
            this.sequence = sequence;
            this.runnable = runnable;
        }
        public int compareTo(Task other) {
            int time = Long.compare(when, other.when);
            return time != 0 ? time : Long.compare(sequence, other.sequence);
        }
    }
}
