package app.aemu.core

import java.io.File

/**
 * The rootify surface of an image (tools/romroot/rootify.py): a real, transparent su in
 * the three standard places, an XposedBridge.jar (API 100 declaration layer) on
 * /system/framework, and a marker file recording what version of the surface this is.
 *
 * The root switch applies on the HOST side: `<root>/system` is a plain folder of files
 * here, so parking `su` beside itself as `su.off` needs no guest cooperation, works
 * while the guest is stopped, and the engine keeps showing /system read-only to the
 * guest either way. The switch rides the next boot, like the rest of the settings sheet.
 */
object RootSurface {
    /** Where rootify puts su; `su.off` marks a parked copy. */
    private val SU_PATHS = listOf("system/xbin/su", "system/bin/su", "su/bin/su")

    /** True when this image carries the rootify surface (marker + at least one su copy). */
    fun present(root: File): Boolean =
        File(root, "system/etc/aemu-root.marker").isFile && SU_PATHS.any { File(root, it).isFile }

    /** Whether su is on its standard paths right now (su, not su.off). */
    fun enabled(root: File): Boolean = SU_PATHS.any { File(root, it).isFile }

    /** The Xposed declaration layer jar, when the image ships one. */
    fun xposedJar(root: File): File? =
        File(root, "system/framework/XposedBridge.jar").takeIf { it.isFile }

    /**
     * Park or restore the su copies. Reports what it did: a switch that silently did
     * nothing is indistinguishable from a broken one.
     */
    fun apply(root: File, on: Boolean, log: (String) -> Unit = {}) {
        if (!present(root)) {
            log("rootsurface: image has no rootify surface, nothing to do")
            return
        }
        var touched = 0
        for (p in SU_PATHS) {
            val su = File(root, p)
            val off = File(root, "$p.off")
            if (on && off.isFile && off.renameTo(su)) touched++
            if (!on && su.isFile && su.renameTo(off)) touched++
        }
        log("rootsurface: ${if (on) "enabled" else "parked"} su ($touched copies moved)")
    }
}
