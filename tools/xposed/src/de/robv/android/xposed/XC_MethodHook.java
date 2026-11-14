package de.robv.android.xposed;

import java.lang.reflect.Member;

/**
 * Callback class for method hooks. Present so module binaries that link the API resolve
 * against this jar; the engine side of the callback contract is described by
 * {@link XposedBridge#hookMethod}.
 */
public abstract class XC_MethodHook {

    /** Hook never expires (the default). */
    public static final int PRIORITY_DEFAULT = 0;
    public static final int PRIORITY_LOWEST = -0x40000000;
    public static final int PRIORITY_HIGHEST = 0x40000000;

    /** Information about the hooked call, handed to the two callback methods. */
    public static class MethodHookParam {
        public Member method;
        public Object thisObject;
        public Object[] args;
        public Object result;
        public Throwable throwable;
        public boolean returnEarly;

        public MethodHookParam() {
        }
    }

    /** Handle returned when a hook is registered, used to remove it again. */
    public class Unhook {
        private final Member hookingMethod;

        public Unhook(Member hookingMethod) {
            this.hookingMethod = hookingMethod;
        }

        public Member getHookedMethod() {
            return hookingMethod;
        }

        public void unhook() {
            XposedBridge.unhookMethod(hookingMethod, XC_MethodHook.this);
        }
    }

    /** Called before the hooked method runs. */
    protected abstract void beforeHookedMethod(MethodHookParam param) throws Throwable;

    /** Called after the hooked method ran (unless an earlier hook returned early). */
    protected abstract void afterHookedMethod(MethodHookParam param) throws Throwable;

    /** Optional hook priority; this declaration layer keeps one level. */
    public int getPriority() {
        return PRIORITY_DEFAULT;
    }
}
