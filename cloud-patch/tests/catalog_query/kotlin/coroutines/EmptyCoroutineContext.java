package kotlin.coroutines;

public final class EmptyCoroutineContext implements CoroutineContext {
    public static final EmptyCoroutineContext INSTANCE = new EmptyCoroutineContext();
    private EmptyCoroutineContext() {}
    public Object get(Object key) { return null; }
    public Object fold(Object initial, Object operation) { return initial; }
    public CoroutineContext plus(CoroutineContext context) { return context; }
    public CoroutineContext minusKey(Object key) { return this; }
}
