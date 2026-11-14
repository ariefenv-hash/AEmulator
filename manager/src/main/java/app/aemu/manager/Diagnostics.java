package app.aemu.manager;

import java.util.ArrayList;
import java.util.List;

/**
 * The self-check battery behind the Diagnostics tab. Each check answers one question the
 * user actually hits — "is su real", "is the framework jar on the boot classpath", "why
 * is Shizuku not starting" — with the raw output kept so a report can carry evidence,
 * not just a tick.
 */
public final class Diagnostics {

    public static final int PASS = 0;
    public static final int FAIL = 1;
    public static final int WARN = 2;

    public static final class Item {
        public final String name;
        public final String detail;   // raw output / reason
        public final int state;
        Item(String name, String detail, int state) {
            this.name = name; this.detail = detail; this.state = state;
        }
    }

    public interface Listener {
        void onItem(Item item);
        void onDone(List<Item> all);
    }

    public static void run(Listener listener) {
        Thread t = new Thread(() -> {
            List<Item> all = new ArrayList<>();
            for (Check c : CHECKS) {
                Item item = c.run();
                all.add(item);
                listener.onItem(item);
            }
            listener.onDone(all);
        }, "aemu-diag");
        t.setDaemon(true);
        t.start();
    }

    private interface Check {
        Item run();
    }

    private static Item item(String name, boolean ok, String detail) {
        return new Item(name, detail.trim(), ok ? PASS : FAIL);
    }

    private static Item warn(String name, String detail) {
        return new Item(name, detail.trim(), WARN);
    }

    private static final Check[] CHECKS = {

        // ---- root ----
        () -> {
            Su.Result r = Su.run("id");
            return item("su 可用（su -c id）", r.code == 0 && r.contains("uid=0"),
                    "id → " + r.line());
        },
        () -> {
            Su.Result r = Su.self(new String[]{"/system/bin/id"}, 10_000);
            return item("进程自身 id（shim 授权）", r.code == 0 && r.contains("uid=0"),
                    "id → " + r.line());
        },
        () -> {
            Su.Result r = Su.run("ls -l /system/bin/su /system/xbin/su 2>&1");
            return item("su 位于标准路径", r.contains("/system/bin/su") || r.contains("/system/xbin/su"),
                    r.out);
        },
        () -> {
            Su.Result r = Su.run("getprop ro.aemu.root; getprop ro.debuggable; getprop ro.secure");
            return item("root 属性（ro.aemu.root / ro.debuggable=1 / ro.secure=0）",
                    r.contains("101") || r.contains("1"), r.out);
        },
        () -> {
            // /system must refuse writes from the guest: that is the read-only mount the
            // host keeps, and preinstalled modules depend on it
            Su.Result r = Su.run("touch /system/.aemu-probe 2>&1; ls /system/.aemu-probe 2>&1; rm -f /system/.aemu-probe 2>/dev/null");
            boolean blocked = !r.contains(".aemu-probe") || r.contains("Read-only") || r.contains("Permission");
            return blocked
                    ? warn("写入 /system（预期为只读）", "guest 内 /system 只读：预装模块的删除需在主机端进行")
                    : warn("写入 /system（预期为只读）", "警告：/system 可写（异常，请反馈）");
        },

        // ---- xposed ----
        () -> {
            String v;
            try {
                Class<?> xb = Class.forName("de.robv.android.xposed.XposedBridge");
                v = String.valueOf(xb.getMethod("getXposedVersion").invoke(null));
            } catch (Throwable e) {
                v = null;
            }
            return item("Xposed 声明层（BootClasspath 可见）", v != null,
                    v == null ? "de.robv.android.xposed.XposedBridge 不在本进程 classpath"
                              : "XposedBridge.getXposedVersion() = " + v + "（API 101）");
        },
        () -> {
            Su.Result r = Su.run("cat /system/framework/xposed.prop 2>&1");
            return item("xposed.prop", r.contains("apiversion"), r.out);
        },
        () -> {
            Su.Result r = Su.run("printenv BOOTCLASSPATH");
            boolean has = r.contains("XposedBridge.jar");
            if (!has) {
                Su.Result self = Su.self(new String[]{"/system/bin/printenv", "BOOTCLASSPATH"}, 10_000);
                has = self.contains("XposedBridge.jar");
                return item("BOOTCLASSPATH 注入", has,
                        has ? self.out : "未包含 XposedBridge.jar → " + (self.out.isEmpty() ? r.out : self.out));
            }
            return item("BOOTCLASSPATH 注入", true, r.line());
        },
        () -> {
            Su.Result r = Su.run("ls /system/app 2>&1");
            StringBuilder names = new StringBuilder();
            for (String m : new String[]{"CorePatch", "QAuxiliary", "ThreeFingerSwipe",
                    "Termux", "TermuxAPI", "TermuxBoot", "TermuxFloat", "TermuxWidget",
                    "AEmulatorManager"}) {
                if (r.contains(m)) names.append(m).append(' ');
            }
            boolean ok = names.length() > 0;
            return new Item("系统预装应用（/system/app）",
                    ok ? names.toString().trim() : r.out, ok ? PASS : WARN);
        },

        // ---- package manager / install path ----
        () -> {
            Su.Result r = Su.run("pm list packages 2>&1");
            return item("pm 可用（模块安装的基础）", r.code == 0 && r.contains("package:"),
                    "共 " + countLines(r.out, "package:") + " 个包");
        },
        () -> {
            Su.Result r = Su.run("mkdir -p " + ModuleRegistry.DIR
                    + " && echo dGVzdA== | base64 -d > " + ModuleRegistry.DIR + "/.probe"
                    + " && cat " + ModuleRegistry.DIR + "/.probe && rm -f " + ModuleRegistry.DIR + "/.probe");
            return item("模块注册表可写（" + ModuleRegistry.FILE + "）", r.contains("test"), r.out);
        },

        // ---- terminal family ----
        () -> {
            Su.Result r = Su.run("adb version 2>&1");
            return item("adb 客户端（/system/bin/adb）", r.contains("Android Debug Bridge"), r.line());
        },
        () -> {
            Su.Result r = Su.run("ps -A 2>&1 | grep -i shizuku");
            return r.contains("shizuku")
                    ? item("Shizuku server 运行中", true, r.out)
                    : warn("Shizuku server 未运行", "尚未启动属正常：打开 Shizuku App → root 模式启动；若启动失败请附本页完整输出");
        },
        () -> {
            Su.Result r = Su.run("pm list packages com.termux 2>&1");
            return r.contains("com.termux")
                    ? item("Termux 已预装", true, r.out)
                    : warn("Termux 未预装", "此镜像未捆绑 Termux（ROM v4 起才有）");
        },
        () -> {
            Su.Result r = Su.run("ls /data/local/tmp 2>&1");
            return item("/data/local/tmp 存在（Shizuku/app_process 的落点）", r.code == 0, r.out);
        },
    };

    private static int countLines(String s, String prefix) {
        int n = 0;
        for (String ln : s.split("\n")) if (ln.trim().startsWith(prefix)) n++;
        return n;
    }

    private Diagnostics() {}
}
