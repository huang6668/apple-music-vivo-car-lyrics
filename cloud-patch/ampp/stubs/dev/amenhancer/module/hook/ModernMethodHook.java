package dev.amenhancer.module.hook;

public abstract class ModernMethodHook {
    public void beforeHookedMethod(MethodHookParam param) {
    }

    public void afterHookedMethod(MethodHookParam param) {
    }

    public static final class MethodHookParam {
        public java.lang.reflect.Executable getMethod() {
            throw new UnsupportedOperationException();
        }

        public Object getThisObject() {
            throw new UnsupportedOperationException();
        }

        public Object[] getArgs() {
            throw new UnsupportedOperationException();
        }

        public Object getResultValue() {
            throw new UnsupportedOperationException();
        }

        public void setResult(Object value) {
            throw new UnsupportedOperationException();
        }
    }
}
