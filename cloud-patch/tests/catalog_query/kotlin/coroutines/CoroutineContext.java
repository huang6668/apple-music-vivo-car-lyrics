package kotlin.coroutines;

public interface CoroutineContext {
    Object get(Object key);
    Object fold(Object initial, Object operation);
    CoroutineContext plus(CoroutineContext context);
    CoroutineContext minusKey(Object key);
}
