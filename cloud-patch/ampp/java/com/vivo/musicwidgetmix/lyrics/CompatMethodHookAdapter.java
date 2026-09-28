package com.vivo.musicwidgetmix.lyrics;

public final class CompatMethodHookAdapter extends dev.amenhancer.module.hook.ModernMethodHook {
    final MethodHook callback;

    public CompatMethodHookAdapter(MethodHook callback) {
        this.callback = callback;
    }

    @Override
    public void beforeHookedMethod(MethodHookParam param) {
        MethodHook.MethodHookParam compat = new MethodHook.MethodHookParam(
                param.getMethod(), param.getThisObject(), param.getArgs());
        callback.beforeHookedMethod(compat);
        if (compat.shouldReturnEarly()) {
            param.setResult(compat.getResultValue());
        }
    }

    @Override
    public void afterHookedMethod(MethodHookParam param) {
        MethodHook.MethodHookParam compat = new MethodHook.MethodHookParam(
                param.getMethod(), param.getThisObject(), param.getArgs());
        compat.setInvocationResult(param.getResultValue());
        callback.afterHookedMethod(compat);
        if (compat.shouldReturnEarly()) {
            param.setResult(compat.getResultValue());
        }
    }
}
