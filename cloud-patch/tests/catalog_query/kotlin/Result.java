package kotlin;

public final class Result {
    public static final class Failure {
        public final Throwable exception;
        public Failure(Throwable error) { exception = error; }
    }
}
