package app.aemu.manager;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

/**
 * Every privileged operation in the manager rides the guest's su (tools/romroot/su.c) —
 * a real passthrough shell on top of the engine shim, which already grants root ids to
 * every guest process. stdout and stderr come back together, with a hard timeout so a
 * wedged pm can never freeze a page.
 */
public final class Su {

    public static final class Result {
        public final int code;
        public final String out;

        Result(int code, String out) { this.code = code; this.out = out == null ? "" : out; }

        /** success by exit code AND output: pm answers errors on stdout, so callers match text too */
        public boolean ok() { return code == 0; }

        public boolean contains(String s) { return out.contains(s); }

        /** first line, for one-answer commands like id */
        public String line() {
            int i = out.indexOf('\n');
            return (i < 0 ? out : out.substring(0, i)).trim();
        }
    }

    private static final int MAX_OUT = 128 * 1024;

    public static Result run(String cmd) { return run(cmd, 30_000); }

    public static Result run(String cmd, long timeoutMs) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            return collect(p, timeoutMs);
        } catch (Exception e) {
            return new Result(-1, e.toString());
        }
    }

    /** the manager's own environment, no su wrapper (printenv BOOTCLASSPATH, plain id) */
    public static Result self(String[] argv, long timeoutMs) {
        try {
            Process p = Runtime.getRuntime().exec(argv);
            return collect(p, timeoutMs);
        } catch (Exception e) {
            return new Result(-1, e.toString());
        }
    }

    private static Result collect(Process p, long timeoutMs) throws Exception {
        StringBuilder sb = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()), 8192)) {
                String line;
                while ((line = r.readLine()) != null) {
                    synchronized (sb) {
                        if (sb.length() < MAX_OUT) sb.append(line).append('\n');
                    }
                }
            } catch (Exception ignored) {
                // the process died before its pipe drained: whatever was read is the answer
            }
        });
        reader.setDaemon(true);
        reader.start();
        if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            p.destroyForcibly();
            return new Result(-9, sb + "\n[timeout]");
        }
        reader.join(2000);
        String out;
        synchronized (sb) { out = sb.toString(); }
        return new Result(p.exitValue(), out);
    }

    private Su() {}
}
