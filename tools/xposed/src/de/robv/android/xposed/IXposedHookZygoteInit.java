package de.robv.android.xposed;

/**
 * Module entry point called once, early, from the zygote — before any application runs.
 */
public interface IXposedHookZygoteInit extends IXposedMod {

    /** Startup arguments handed to the module. */
    class StartupParam {
        /** Path of the module's APK on the guest filesystem. */
        public String modulePath;

        public StartupParam() {
        }
    }

    void handleInitZygote(StartupParam startupParam) throws Throwable;
}
