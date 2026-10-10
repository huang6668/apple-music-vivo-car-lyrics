package kotlin.coroutines;

public interface Continuation {
    CoroutineContext getContext();
    void resumeWith(Object result);
}
