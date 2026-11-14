package app.aemu.manager;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AEmulator Manager — the guest-side root / Xposed / module surface. Five pages, built
 * in code (no layout XML): status, modules, install, diagnostics, help. Every privileged
 * action goes through Su, every screen reacts to what is actually true on this image.
 */
public class MainActivity extends Activity {

    // palette (matches themes.xml)
    private static final int FG = 0xFFE8EAED;
    private static final int FG_DIM = 0xFF9AA0A6;
    private static final int GREEN = 0xFF66BB6A;
    private static final int RED = 0xFFEF5350;
    private static final int AMBER = 0xFFFFCA28;

    private final Handler main = new Handler(Looper.getMainLooper());

    private LinearLayout[] pages;
    private TextView[] tabs;
    private int current = 0;

    // status
    private TextView rootState, rootOut, xpState;

    // modules
    private LinearLayout moduleList;
    private TextView moduleEmpty;
    private final Map<String, Boolean> registry = new HashMap<>();
    private List<ModuleScanner.Module> modules = new ArrayList<>();

    // install
    private LinearLayout installList;

    // diagnostics
    private LinearLayout diagList;
    private TextView diagSummary;
    private final List<Diagnostics.Item> diagItems = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        int pad = dp(16);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0E0F12);
        root.setPadding(pad, dp(10), pad, pad);

        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(FG);
        root.addView(title, row(dp(6)));

        // tab bar
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        String[] tabNames = {
                getString(R.string.tab_status), getString(R.string.tab_modules),
                getString(R.string.tab_install), getString(R.string.tab_diag),
                getString(R.string.tab_help)};
        tabs = new TextView[tabNames.length];
        pages = new LinearLayout[tabNames.length];
        for (int i = 0; i < tabNames.length; i++) {
            TextView t = pill(tabNames[i]);
            final int idx = i;
            t.setOnClickListener(v -> showTab(idx));
            tabs[i] = t;
            bar.addView(t);
        }
        hs.addView(bar);
        root.addView(hs, row(dp(8)));

        FrameLayout content = new FrameLayout(this);
        for (int i = 0; i < tabNames.length; i++) {
            ScrollView sv = new ScrollView(this);
            pages[i] = new LinearLayout(this);
            pages[i].setOrientation(LinearLayout.VERTICAL);
            pages[i].setPadding(0, dp(4), 0, dp(20));
            sv.addView(pages[i]);
            sv.setVisibility(i == 0 ? View.VISIBLE : View.GONE);
            content.addView(sv, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        root.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        buildStatusPage();
        buildModulesPage();
        buildInstallPage();
        buildDiagPage();
        buildHelpPage();
        paintTabs();
        refreshStatus();
    }

    // ---------------------------------------------------------------- tabs

    private void showTab(int i) {
        current = i;
        paintTabs();
        if (i == 1 && modules.isEmpty()) refreshModules();
        if (i == 2 && installList.getChildCount() == 0) scanDownload();
    }

    private void paintTabs() {
        for (int k = 0; k < pages.length; k++) {
            ((View) pages[k].getParent()).setVisibility(k == current ? View.VISIBLE : View.GONE);
            tabs[k].setTextColor(k == current ? GREEN : FG_DIM);
        }
    }

    // ---------------------------------------------------------------- status page

    private void buildStatusPage() {
        LinearLayout p = pages[0];
        p.removeAllViews();

        LinearLayout root = card();
        root.addView(header(R.string.root_card_title));
        rootState = big(GREEN);
        rootState.setText(R.string.root_checking);
        root.addView(rootState);
        root.addView(body(R.string.root_sub));

        Button test = new Button(this);
        test.setText(R.string.su_test);
        test.setOnClickListener(v -> {
            test.setEnabled(false);
            bg(() -> {
                Su.Result r = Su.run("id; getprop ro.aemu.root; getprop ro.debuggable; getprop ro.secure");
                main.post(() -> {
                    test.setEnabled(true);
                    rootOut.setText(r.out.trim());
                });
            });
        });
        root.addView(test);
        rootOut = mono();
        root.addView(rootOut);
        p.addView(cardWrap(root));

        LinearLayout xp = card();
        xp.addView(header(R.string.xposed_card_title));
        xpState = big(FG);
        xpState.setText(R.string.xposed_checking);
        xp.addView(xpState);
        xp.addView(body(R.string.xposed_sub));
        p.addView(cardWrap(xp));

        LinearLayout note = card();
        note.addView(header(R.string.note_card_title));
        note.addView(body(R.string.note_text));
        p.addView(cardWrap(note));
    }

    private void refreshStatus() {
        rootOut.setText("");
        bg(() -> {
            // su
            Su.Result r = Su.run("id");
            boolean ok = r.code == 0 && r.contains("uid=0");
            main.post(() -> {
                rootState.setText(ok ? R.string.root_ok : R.string.root_fail);
                rootState.setTextColor(ok ? GREEN : RED);
            });
            // xposed, in this very process: the boot classpath carries the declaration layer
            String v = null;
            try {
                Class<?> xb = Class.forName("de.robv.android.xposed.XposedBridge");
                v = String.valueOf(xb.getMethod("getXposedVersion").invoke(null));
            } catch (Throwable ignored) {
                // not on the classpath here — the honest answer, shown as such
            }
            final String ver = v;
            main.post(() -> {
                if (ver == null) {
                    xpState.setText(R.string.xposed_missing);
                    xpState.setTextColor(RED);
                } else {
                    xpState.setText(getString(R.string.xposed_api_fmt, ver));
                    xpState.setTextColor(GREEN);
                }
            });
        });
    }

    // ---------------------------------------------------------------- modules page

    private void buildModulesPage() {
        LinearLayout p = pages[1];
        p.removeAllViews();

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        Button refresh = new Button(this);
        refresh.setText(R.string.btn_refresh);
        refresh.setOnClickListener(v -> refreshModules());
        bar.addView(refresh);
        TextView hint = body(R.string.install_toast_scope);
        hint.setPadding(dp(8), 0, 0, 0);
        bar.addView(hint, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        p.addView(bar, row(dp(8)));

        moduleEmpty = body(R.string.modules_empty);
        moduleEmpty.setVisibility(View.GONE);
        p.addView(moduleEmpty);

        moduleList = new LinearLayout(this);
        moduleList.setOrientation(LinearLayout.VERTICAL);
        p.addView(moduleList);
    }

    private void refreshModules() {
        moduleList.removeAllViews();
        moduleEmpty.setVisibility(View.GONE);
        TextView scanning = info(R.string.modules_scanning);
        moduleList.addView(scanning);
        bg(() -> {
            Map<String, Boolean> reg = ModuleRegistry.read();
            List<ModuleScanner.Module> found = ModuleScanner.scan(this, reg);
            main.post(() -> {
                registry.clear();
                registry.putAll(reg);
                modules = found;
                renderModules();
            });
        });
    }

    private void renderModules() {
        moduleList.removeAllViews();
        if (modules.isEmpty()) {
            moduleEmpty.setVisibility(View.VISIBLE);
            return;
        }
        moduleEmpty.setVisibility(View.GONE);
        for (ModuleScanner.Module m : modules) moduleList.addView(cardWrap(moduleCard(m)));
    }

    private View moduleCard(ModuleScanner.Module m) {
        LinearLayout c = card();
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView name = new TextView(this);
        name.setText(m.label);
        name.setTextSize(16);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setTextColor(FG);
        head.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView badge = pill(m.preinstalled ? getString(R.string.mod_preinstall_badge)
                : getString(R.string.mod_user_badge));
        head.addView(badge);
        String state = m.stateText(this);
        boolean on = getString(R.string.mod_state_on).equals(state);
        TextView st = pill(state);
        st.setTextColor(on ? GREEN : AMBER);
        head.addView(st);
        c.addView(head);

        c.addView(small(m.pkg + "  ·  " + m.version));
        if (m.desc != null && !m.desc.trim().isEmpty()) c.addView(body(m.desc));
        c.addView(small(getString(R.string.mod_scope_label)
                + (m.scopes.isEmpty() ? getString(R.string.mod_scope_none) : m.scopes)));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button toggle = new Button(this);
        toggle.setText(on ? R.string.mod_disable : R.string.mod_enable);
        toggle.setOnClickListener(v -> {
            toggle.setEnabled(false);
            toast(getString(R.string.mod_working));
            bg(() -> {
                String cmd = on ? "pm disable " + m.pkg : "pm enable " + m.pkg;
                Su.Result r = Su.run(cmd, 60_000);
                boolean ok = r.code == 0;
                if (ok) {
                    registry.put(m.pkg, !on);
                    ModuleRegistry.write(registry);
                }
                String msg = ok
                        ? (on ? getString(R.string.mod_disable_ok, m.pkg)
                              : getString(R.string.mod_enable_ok, m.pkg))
                        : (on ? getString(R.string.mod_disable_fail, r.out.trim())
                              : getString(R.string.mod_enable_fail, r.out.trim()));
                main.post(() -> {
                    toggle.setEnabled(true);
                    toast(msg);
                    refreshModules();
                });
            });
        });
        row.addView(toggle);

        if (!m.preinstalled) {
            Button un = new Button(this);
            un.setText(R.string.mod_uninstall);
            un.setOnClickListener(v -> {
                un.setEnabled(false);
                bg(() -> {
                    Su.Result r = Su.run("pm uninstall " + m.pkg, 60_000);
                    boolean ok = r.code == 0 || r.contains("Success");
                    main.post(() -> {
                        toast(ok ? getString(R.string.mod_uninstall_ok, m.pkg)
                                : getString(R.string.mod_uninstall_fail, r.out.trim()));
                        refreshModules();
                    });
                });
            });
            row.addView(un);
        }

        if (m.scopeList.length > 0) {
            Button stop = new Button(this);
            stop.setText(R.string.mod_stop_scope);
            stop.setOnClickListener(v -> {
                stop.setEnabled(false);
                bg(() -> {
                    int n = 0;
                    for (String s : m.scopeList) {
                        if (Su.run("am force-stop " + s.trim(), 30_000).code == 0) n++;
                    }
                    final int done = n;
                    main.post(() -> {
                        stop.setEnabled(true);
                        toast(getString(R.string.mod_stop_done, done));
                    });
                });
            });
            row.addView(stop);
        }
        c.addView(row);
        return c;
    }

    // ---------------------------------------------------------------- install page

    private void buildInstallPage() {
        LinearLayout p = pages[2];
        p.removeAllViews();

        LinearLayout c = card();
        c.addView(header(R.string.install_title));
        c.addView(body(R.string.install_hint));
        Button scan = new Button(this);
        scan.setText(R.string.install_scan);
        scan.setOnClickListener(v -> scanDownload());
        c.addView(scan);
        p.addView(cardWrap(c), row(dp(8)));

        installList = new LinearLayout(this);
        installList.setOrientation(LinearLayout.VERTICAL);
        p.addView(installList);
    }

    private void scanDownload() {
        installList.removeAllViews();
        installList.addView(info(R.string.modules_scanning));
        bg(() -> {
            Su.Result r = Su.run(
                    "ls -1 /sdcard/Download/*.apk /storage/emulated/0/Download/*.apk 2>/dev/null | sort -u");
            List<String> apks = new ArrayList<>();
            if (r.code == 0 || r.out.contains(".apk")) {
                for (String ln : r.out.split("\n")) {
                    ln = ln.trim();
                    if (ln.endsWith(".apk") && ln.startsWith("/")) apks.add(ln);
                }
            }
            main.post(() -> renderInstall(apks));
        });
    }

    private void renderInstall(List<String> apks) {
        installList.removeAllViews();
        if (apks.isEmpty()) {
            installList.addView(info(R.string.install_empty));
            return;
        }
        for (String path : apks) {
            LinearLayout c = card();
            c.addView(body(path.substring(path.lastIndexOf('/') + 1)));
            c.addView(small(path));
            Button b = new Button(this);
            b.setText(R.string.install_btn);
            b.setOnClickListener(v -> {
                b.setEnabled(false);
                String name = path.substring(path.lastIndexOf('/') + 1);
                toast(getString(R.string.install_running, name));
                bg(() -> {
                    String safe = path.replace("'", "");
                    Su.Result r = Su.run("pm install -r '" + safe + "'", 180_000);
                    boolean ok = r.code == 0 && r.contains("Success");
                    main.post(() -> {
                        b.setEnabled(true);
                        toast(ok ? getString(R.string.install_ok, name)
                                : getString(R.string.install_fail, r.out.trim()));
                        if (ok) refreshModules();
                    });
                });
            });
            c.addView(b);
            installList.addView(cardWrap(c), row(dp(8)));
        }
    }

    // ---------------------------------------------------------------- diagnostics page

    private void buildDiagPage() {
        LinearLayout p = pages[3];
        p.removeAllViews();

        LinearLayout c = card();
        c.addView(header(R.string.diag_title));
        c.addView(body(R.string.diag_hint));
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        Button go = new Button(this);
        go.setText(R.string.diag_start);
        go.setOnClickListener(v -> runDiag());
        row.addView(go);
        Button copy = new Button(this);
        copy.setText(R.string.diag_copy);
        copy.setOnClickListener(v -> {
            StringBuilder sb = new StringBuilder("AEmulator Manager ")
                    .append(versionName()).append(" — diagnostics\n");
            for (Diagnostics.Item it : diagItems) {
                sb.append(it.state == Diagnostics.PASS ? "[PASS] "
                        : it.state == Diagnostics.FAIL ? "[FAIL] " : "[WARN] ")
                        .append(it.name).append('\n');
                if (!it.detail.isEmpty()) {
                    sb.append("      ").append(it.detail.replace("\n", "\n      ")).append('\n');
                }
            }
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("aemu-diag", sb.toString()));
            toast(R.string.diag_copied);
        });
        row.addView(copy);
        c.addView(row);
        diagSummary = small("");
        c.addView(diagSummary);
        p.addView(cardWrap(c), row(dp(8)));

        diagList = new LinearLayout(this);
        diagList.setOrientation(LinearLayout.VERTICAL);
        p.addView(diagList);
    }

    private void runDiag() {
        diagList.removeAllViews();
        diagItems.clear();
        diagSummary.setText(R.string.diag_running);
        Diagnostics.run(new Diagnostics.Listener() {
            @Override
            public void onItem(Diagnostics.Item item) {
                main.post(() -> {
                    diagItems.add(item);
                    diagList.addView(cardWrap(diagCard(item)), row(dp(8)));
                });
            }

            @Override
            public void onDone(List<Diagnostics.Item> all) {
                main.post(() -> {
                    int f = 0, w = 0;
                    for (Diagnostics.Item it : all) {
                        if (it.state == Diagnostics.FAIL) f++;
                        else if (it.state == Diagnostics.WARN) w++;
                    }
                    diagSummary.setText(getString(R.string.diag_summary_fmt,
                            all.size() - f - w, f, w));
                });
            }
        });
    }

    private View diagCard(Diagnostics.Item it) {
        LinearLayout c = card();
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = new TextView(this);
        name.setText(it.name);
        name.setTextSize(14);
        name.setTextColor(FG);
        head.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        int color = it.state == Diagnostics.PASS ? GREEN
                : it.state == Diagnostics.FAIL ? RED : AMBER;
        TextView st = pill(it.state == Diagnostics.PASS ? getString(R.string.diag_pass)
                : it.state == Diagnostics.FAIL ? getString(R.string.diag_fail)
                : getString(R.string.diag_warn));
        st.setTextColor(color);
        head.addView(st);
        c.addView(head);
        if (!it.detail.isEmpty()) c.addView(mono(it.detail));
        return c;
    }

    // ---------------------------------------------------------------- help page

    private void buildHelpPage() {
        LinearLayout p = pages[4];
        p.removeAllViews();
        int[][] sections = {
                {R.string.help_t1, R.string.help_p1},
                {R.string.help_t2, R.string.help_p2},
                {R.string.help_t3, R.string.help_p3},
                {R.string.help_t4, R.string.help_p4},
                {R.string.help_t5, R.string.help_p5},
                {R.string.help_t6, R.string.help_p6},
        };
        for (int[] s : sections) {
            LinearLayout c = card();
            c.addView(header(s[0]));
            c.addView(body(s[1]));
            p.addView(cardWrap(c), row(dp(10)));
        }
        TextView foot = small("AEmulator Manager " + versionName() + " — app.aemu.manager");
        foot.setGravity(Gravity.CENTER);
        foot.setPadding(0, dp(10), 0, 0);
        p.addView(foot);
    }

    // ---------------------------------------------------------------- widget helpers

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackgroundResource(R.drawable.card_bg);
        c.setPadding(dp(14), dp(12), dp(14), dp(12));
        return c;
    }

    /** the outer margin lives on a wrapper: a View added to two parents throws */
    private LinearLayout cardWrap(View inner) {
        LinearLayout w = new LinearLayout(this);
        w.setOrientation(LinearLayout.VERTICAL);
        w.addView(inner);
        return w;
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    private TextView header(int res) {
        TextView t = new TextView(this);
        t.setText(res);
        t.setTextSize(16);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(FG);
        t.setPadding(0, dp(2), 0, dp(6));
        return t;
    }

    private TextView big(int color) {
        TextView t = new TextView(this);
        t.setTextSize(18);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(color);
        t.setPadding(0, dp(2), 0, dp(2));
        return t;
    }

    private TextView body(int res) {
        TextView t = new TextView(this);
        t.setText(res);
        t.setTextSize(13);
        t.setTextColor(FG_DIM);
        t.setLineSpacing(dp(2), 1f);
        t.setPadding(0, dp(2), 0, dp(2));
        return t;
    }

    private TextView body(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(13);
        t.setTextColor(FG_DIM);
        t.setLineSpacing(dp(2), 1f);
        t.setPadding(0, dp(2), 0, dp(2));
        return t;
    }

    private TextView small(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(12);
        t.setTextColor(FG_DIM);
        t.setPadding(0, dp(4), 0, 0);
        return t;
    }

    private TextView info(int res) {
        TextView t = body(res);
        return t;
    }

    private TextView mono() { return mono(""); }

    private TextView mono(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(11);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextColor(FG_DIM);
        t.setBackgroundColor(0xFF101216);
        t.setPadding(dp(8), dp(6), dp(8), dp(6));
        t.setTextIsSelectable(true);
        return t;
    }

    private TextView pill(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(11);
        t.setTextColor(FG_DIM);
        t.setBackgroundResource(R.drawable.pill_bg);
        t.setPadding(dp(10), dp(4), dp(10), dp(4));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(6);
        t.setLayoutParams(lp);
        return t;
    }

    private LinearLayout.LayoutParams row(int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = top;
        return lp;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private void toast(int res) {
        Toast.makeText(this, res, Toast.LENGTH_LONG).show();
    }

    private void bg(Runnable r) {
        Thread t = new Thread(r, "aemu-mgr");
        t.setDaemon(true);
        t.start();
    }
}
