package de.robv.android.xposed.callbacks;

/**
 * Parameter object for the per-package load callback. Kept free of Android platform
 * types on purpose: this declaration layer ships before the hook engine does, and the
 * fields a checker or module binary reads are the identity ones.
 */
public abstract class XC_LoadPackage {

    /** What the callback receives: the package being loaded. */
    public static class LoadPackageParam {
        /** The package's name, as PM knows it. */
        public String packageName;

        /** The process the package is loading into (usually the package name). */
        public String processName;

        /** The classloader the package's own classes resolve through. */
        public ClassLoader classLoader;

        public LoadPackageParam() {
        }
    }
}
