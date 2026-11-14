package app.aemu.manager;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Finds Xposed modules among the installed packages. Three generations of the ecosystem
 * are covered by the manifest meta-data:
 *   - the classic LSPosed/Xposed flag:      xposedmodule = true (+ xposedminversion)
 *   - the libxposed API generation:         io.github.libxposed.api = <level>
 * and the description/scope keys ride either generation (xposeddescription,
 * xposedscope — plain string, integer resource, or integer string-array resource).
 */
public final class ModuleScanner {

    public static final class Module {
        public String pkg;
        public String label;
        public String version;
        public String desc;
        public String scopes;      // comma-joined package names, "" when none declared
        public String[] scopeList; // same, split, for am force-stop
        public boolean preinstalled; // lives on /system (or /product) — cannot be uninstalled
        public boolean pmEnabled;
        public Boolean registered; // registry state: true on, false off, null unknown

        public String stateText(android.content.Context c) {
            if (!pmEnabled) return c.getString(R.string.mod_state_off);
            if (registered == Boolean.FALSE) return c.getString(R.string.mod_state_off);
            return c.getString(R.string.mod_state_on);
        }
    }

    public static List<Module> scan(Context ctx, Map<String, Boolean> registry) {
        PackageManager pm = ctx.getPackageManager();
        List<Module> out = new ArrayList<>();
        List<PackageInfo> all = pm.getInstalledPackages(PackageManager.GET_META_DATA);
        for (PackageInfo pi : all) {
            if (pi.applicationInfo == null) continue;
            ApplicationInfo ai = pi.applicationInfo;
            Bundle meta = ai.metaData;
            boolean classic = meta != null && meta.containsKey("xposedmodule");
            boolean libxposed = meta != null && meta.containsKey("io.github.libxposed.api");
            if (!classic && !libxposed) continue;

            Module m = new Module();
            m.pkg = pi.packageName;
            m.label = String.valueOf(pm.getApplicationLabel(ai));
            m.version = pi.versionName == null ? "?" : pi.versionName;
            m.desc = resolveDescription(pm, ai, meta);
            m.scopes = resolveScopes(pm, ai, meta);
            m.scopeList = m.scopes.isEmpty() ? new String[0]
                    : m.scopes.split(",\\s*");
            String src = ai.sourceDir == null ? "" : ai.sourceDir;
            m.preinstalled = src.startsWith("/system") || src.startsWith("/product")
                    || src.startsWith("/vendor");
            // DEFAULT(0) and ENABLED(1) both mean "on"; the two DISABLED states are the
            // only ones pm disable ever produces here
            m.pmEnabled = ai.enabled != PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    && ai.enabled != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER;
            m.registered = registry == null ? null : registry.get(m.pkg);
            out.add(m);
        }
        out.sort((a, b) -> Boolean.compare(b.preinstalled, a.preinstalled)
                | String.CASE_INSENSITIVE_ORDER.compare(a.label, b.label));
        return out;
    }

    private static String resolveDescription(PackageManager pm, ApplicationInfo ai, Bundle meta) {
        if (meta == null || !meta.containsKey("xposeddescription")) return null;
        Object v = meta.get("xposeddescription");
        try {
            if (v instanceof Integer) {
                return pm.getResourcesForApplication(ai).getString((Integer) v);
            }
            if (v instanceof String) return (String) v;
        } catch (Exception ignored) {
            // a broken resource id: fall through to the string form below
        }
        return String.valueOf(v);
    }

    private static String resolveScopes(PackageManager pm, ApplicationInfo ai, Bundle meta) {
        if (meta == null || !meta.containsKey("xposedscope")) return "";
        Object v = meta.get("xposedscope");
        try {
            if (v instanceof Integer) {
                String[] arr = pm.getResourcesForApplication(ai).getStringArray((Integer) v);
                if (arr != null) return String.join(", ", arr);
            }
            if (v instanceof String) return (String) v;
        } catch (Exception ignored) {
            // same story as the description: keep whatever form came through
        }
        return String.valueOf(v == null ? "" : v);
    }

    private ModuleScanner() {}
}
