package de.robv.android.xposed;

/**
 * A special {@link XC_MethodHook} that replaces the hooked method entirely: when
 * {@link #replaceHookedMethod} returns, that value is the call's result.
 */
public abstract class XC_MethodReplacement extends XC_MethodHook {

    /** Run the replacement; the hooked method's original body never executes. */
    protected abstract Object replaceHookedMethod(MethodHookParam param) throws Throwable;

    @Override
    protected final void beforeHookedMethod(MethodHookParam param) throws Throwable {
        param.result = replaceHookedMethod(param);
        param.returnEarly = true;
    }

    @Override
    protected final void afterHookedMethod(MethodHookParam param) throws Throwable {
        // the replacement already produced the result in beforeHookedMethod
    }
}
