package app.aemu.manager;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The module registry: /data/system/aemu-xposed/modules.conf, one "pkg=on|off" line per
 * module. It is the single place where a module's enabled state lives, writable by the
 * manager (guest side) and readable by anything that cares — today the manager, tomorrow
 * whatever loads module entry points. Written through su with base64 to sidestep every
 * shell-escaping question a package name could raise.
 */
public final class ModuleRegistry {

    public static final String DIR = "/data/system/aemu-xposed";
    public static final String FILE = DIR + "/modules.conf";

    private static final Pattern LINE = Pattern.compile("^([A-Za-z0-9_.]+)=(on|off)$");

    public static Map<String, Boolean> read() {
        Map<String, Boolean> map = new LinkedHashMap<>();
        Su.Result r = Su.run("cat " + FILE + " 2>/dev/null", 15_000);
        if (r.code != 0 && !r.contains("=")) return map;
        for (String ln : r.out.split("\n")) {
            Matcher m = LINE.matcher(ln.trim());
            if (m.matches()) map.put(m.group(1), "on".equals(m.group(2)));
        }
        return map;
    }

    /** merges pkg -> enabled into the file, creating the directory on the way */
    public static boolean write(Map<String, Boolean> state) {
        StringBuilder b = new StringBuilder("# aemu xposed module registry\n");
        for (Map.Entry<String, Boolean> e : state.entrySet()) {
            if (e.getKey() == null || !e.getKey().matches("[A-Za-z0-9_.]+")) continue;
            b.append(e.getKey()).append('=').append(e.getValue() ? "on" : "off").append('\n');
        }
        return writeContent(b.toString());
    }

    private static boolean writeContent(String content) {
        String b64 = android.util.Base64.encodeToString(
                content.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                android.util.Base64.NO_WRAP);
        Su.Result r = Su.run("mkdir -p " + DIR + " && echo " + b64
                + " | base64 -d > " + FILE + " && sync", 20_000);
        return r.code == 0;
    }

    private ModuleRegistry() {}
}
