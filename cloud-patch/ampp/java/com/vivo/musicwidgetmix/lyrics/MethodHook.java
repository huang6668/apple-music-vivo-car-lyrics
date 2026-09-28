package com.vivo.musicwidgetmix.lyrics;

import java.lang.reflect.Executable;
import java.util.LinkedHashMap;
import java.util.Map;

public abstract class MethodHook {
    public void beforeHookedMethod(MethodHookParam param) {
    }

    public void afterHookedMethod(MethodHookParam param) {
    }

    public static final class MethodHookParam {
        private final Executable method;
        private final Object thisObject;
        private final Object[] args;
        private final Map<String, Object> extras = new LinkedHashMap<String, Object>();
        private Object resultValue;
        private boolean returnEarly;
        private Throwable throwable;

        public MethodHookParam(Executable method, Object thisObject, Object[] args) {
            this.method = method;
            this.thisObject = thisObject;
            this.args = args;
        }

        public Executable getMethod() {
            return method;
        }

        public Object getThisObject() {
            return thisObject;
        }

        public Object[] getArgs() {
            return args;
        }

        public Object getResultValue() {
            return resultValue;
        }

        public void setResult(Object value) {
            resultValue = value;
            throwable = null;
            returnEarly = true;
        }

        public boolean shouldReturnEarly() {
            return returnEarly;
        }

        public void setInvocationResult(Object value) {
            resultValue = value;
        }
    }
}
