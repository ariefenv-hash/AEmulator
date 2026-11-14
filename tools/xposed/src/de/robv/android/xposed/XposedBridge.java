package de.robv.android.xposed;

/**
 * The AEmulator Xposed API declaration layer (API 100, the level LSPosed expects).
 *
 * This jar lives on the guest's boot classpath, so every guest app sees the
 * de.robv.android.xposed classes through its parent classloader: a checker calling
 * {@link #getXposedVersion()} gets 100 and a module binary linking against the API
 * resolves. What this build does NOT bundle is the ART hook engine behind
 * {@link #hookMethod}: a module that actually calls it gets an explicit failure rather
 * than a silent no-op. Faking the engine would be worse than not shipping it — a hook
 * that neither fires nor fails is how people lose a day.
 */
public final class XposedBridge {

    /** The API level this build speaks: LSPosed treats anything >= 100 as current. */
    public static final int XPOSED_BRIDGE_VERSION = 100;

    private XposedBridge() {
        // never instantiated
    }

    /** The API level: 100, matching {@link #XPOSED_BRIDGE_VERSION}. */
    public static int getXposedVersion() {
        return XPOSED_BRIDGE_VERSION;
    }

    /** Which runtime backs the framework — ART everywhere AEmulator runs. */
    public static String getRuntime() {
        return "art";
    }

    /**
     * Modules call this to hook a method. Not wired to a hook engine in this build: the
     * failure names exactly what is missing so a module log explains itself.
     */
    public static XC_MethodHook.Unhook hookMethod(java.lang.reflect.Member method, XC_MethodHook callback) {
        throw new UnsupportedOperationException(
                "AEmulator ships the Xposed API declaration layer (API " + XPOSED_BRIDGE_VERSION
                        + "); the ART hook engine is not bundled in this build");
    }

    /** Companion of {@link #hookMethod}; same explicit failure. */
    public static void unhookMethod(java.lang.reflect.Member method, XC_MethodHook callback) {
        throw new UnsupportedOperationException(
                "AEmulator ships the Xposed API declaration layer; no hook engine is bundled");
    }

    /** Original-method invocation hook modules reach for; not wired in this build. */
    public static Object invokeOriginalMethodNative(java.lang.reflect.Member method,
            long methodId, Object thisObject, Object[] args) throws Throwable {
        throw new UnsupportedOperationException(
                "AEmulator ships the Xposed API declaration layer; no hook engine is bundled");
    }

    /** Bridge log: reaches the guest logcat through the framework loggers already running. */
    public static void log(String text) {
        System.out.println("[XposedBridge] " + text);
    }

    /** Bridge log with a stack trace attached. */
    public static void log(String text, Throwable t) {
        System.out.println("[XposedBridge] " + text + ": " + t);
        t.printStackTrace(System.out);
    }

    /** Kept for API completeness: modules probe for it to detect the framework. */
    public static boolean disableHiddenApiChecks() {
        return false;
    }
}
