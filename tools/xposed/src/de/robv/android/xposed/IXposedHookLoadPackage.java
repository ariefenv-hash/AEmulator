package de.robv.android.xposed;

import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Module entry point called once per package when the (future) hook engine loads a new
 * application process.
 */
public interface IXposedHookLoadPackage extends IXposedMod {
    void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable;
}
