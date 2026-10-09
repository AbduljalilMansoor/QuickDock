package com.quickdock.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** شاشة الإعداد: حالة الصلاحية والخدمة، تشغيل/إيقاف، فتح الدرج للتجربة، التشغيل التلقائي، التحديث. */
public class MainActivity extends Activity {
    static final String KEY_AUTOSTART = "autostart";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private TextView vPerm, vService, bGrant, bToggle, bAuto;

    private int dp(float v) { return Ui.dp(this, v); }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Updater.init(this);
        prefs = Favorites.prefs(this);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Ui.BG);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(48), dp(32), dp(48), dp(32));
        sv.addView(col);

        col.addView(Ui.text(this, "QuickDock", 34, Ui.TEXT, true));
        TextView sub = Ui.text(this, "Right-side quick launch drawer for Jetour G700", 16, Ui.MUTE, false);
        sub.setPadding(0, dp(4), 0, dp(20));
        col.addView(sub);

        vPerm = Ui.text(this, "", 18, Ui.TEXT, false);
        vService = Ui.text(this, "", 18, Ui.TEXT, false);
        col.addView(card(vPerm));
        col.addView(card(vService));

        bGrant = button("Grant overlay permission", true);
        bGrant.setOnClickListener(v -> grantOverlay());
        col.addView(bGrant, btnLp());

        bToggle = button("", true);
        bToggle.setOnClickListener(v -> {
            if (DockService.running) DockService.stop(this); else DockService.start(this);
            ui.postDelayed(this::refresh, 400);
        });
        col.addView(bToggle, btnLp());

        TextView open = button("Open drawer now", false);
        open.setOnClickListener(v -> { DockService.send(this, DockService.ACTION_OPEN); ui.postDelayed(this::refresh, 400); });
        col.addView(open, btnLp());

        TextView edit = button("Edit favorites", false);
        edit.setOnClickListener(v -> { DockService.send(this, DockService.ACTION_EDIT); ui.postDelayed(this::refresh, 400); });
        col.addView(edit, btnLp());

        bAuto = button("", false);
        bAuto.setOnClickListener(v -> {
            prefs.edit().putBoolean(KEY_AUTOSTART, !prefs.getBoolean(KEY_AUTOSTART, true)).apply();
            refresh();
        });
        col.addView(bAuto, btnLp());

        TextView upd = button("Check for update", false);
        upd.setOnClickListener(v -> onUpdateClicked());
        col.addView(upd, btnLp());

        TextView help = Ui.text(this,
                "How to use\n"
                        + "• Swipe left from the right edge of the screen, or tap the amber edge tab.\n"
                        + "• The drawer closes after 4 seconds without touch, or when you tap outside it.\n"
                        + "• Long-press an app (or tap ✎) to edit: drag to reorder, ✕ to remove, tap an empty slot to add.\n\n"
                        + "If the overlay permission cannot be granted from the screen, run over ADB:\n"
                        + "appops set " + getPackageName() + " SYSTEM_ALERT_WINDOW allow",
                15, Ui.MUTE, false);
        help.setPadding(0, dp(20), 0, 0);
        col.addView(help);

        setContentView(sv);

        // التشغيل التلقائي عند فتح التطبيق (مثلاً بعد التثبيت من صفحة التثبيت)
        if (Settings.canDrawOverlays(this) && prefs.getBoolean(KEY_AUTOSTART, true) && !DockService.running) {
            DockService.start(this);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
        ui.postDelayed(this::refresh, 600);
    }

    private LinearLayout card(TextView t) {
        LinearLayout c = new LinearLayout(this);
        c.setBackground(Ui.shape(Ui.PANEL, dp(14), Ui.LINE, dp(1)));
        c.setPadding(dp(18), dp(14), dp(18), dp(14));
        c.addView(t);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(10);
        c.setLayoutParams(lp);
        return c;
    }

    private TextView button(String label, boolean primary) {
        TextView b = Ui.text(this, label, 19, primary ? Ui.ON_ACCENT : Ui.TEXT, primary);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(dp(56));
        b.setBackground(Ui.shape(primary ? Ui.ACCENT : Ui.PANEL2, dp(14), primary ? Ui.ACCENT : Ui.LINE, dp(1)));
        return b;
    }

    private LinearLayout.LayoutParams btnLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(10);
        return lp;
    }

    private void refresh() {
        boolean perm = Settings.canDrawOverlays(this);
        vPerm.setText(perm ? "✅  Overlay permission granted" : "❌  Overlay permission missing (needed to draw over other apps)");
        vService.setText(DockService.running ? "✅  QuickDock is running — look for the amber tab on the right edge"
                : "⚪  QuickDock is stopped");
        bGrant.setVisibility(perm ? android.view.View.GONE : android.view.View.VISIBLE);
        bToggle.setText(DockService.running ? "Stop QuickDock" : "Start QuickDock");
        bAuto.setText("Start automatically with the car:  " + (prefs.getBoolean(KEY_AUTOSTART, true) ? "ON" : "OFF"));
    }

    private void grantOverlay() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            message("This screen does not offer the permission page.\n\nRun over ADB (or install from the install page, which does it for you):\n"
                    + "appops set " + getPackageName() + " SYSTEM_ALERT_WINDOW allow");
        }
    }

    private void message(String m) {
        new AlertDialog.Builder(this).setMessage(m).setPositiveButton("OK", null).show();
    }

    // ======================= التحديث الذاتي =======================
    private void onUpdateClicked() {
        if (!getPackageManager().canRequestPackageInstalls()) {
            message("To update itself the app needs the install-apps permission, which this screen does not show in Settings.\n\n"
                    + "Install once from the install page on a computer (it grants the permission):\n"
                    + Updater.installPage());
            return;
        }
        TextView status = Ui.text(this, "Checking for updates…", 18, Ui.TEXT, false);
        status.setPadding(dp(24), dp(20), dp(24), dp(20));
        final AlertDialog dlg = new AlertDialog.Builder(this).setView(status).setCancelable(false).show();
        new Thread(() -> {
            try {
                final Updater.Info i = Updater.fetchLatest();
                final long mine = Updater.installedCode(this);
                ui.post(() -> {
                    dlg.dismiss();
                    if (i == null || i.code <= mine) message("You have the latest version ✅\n\nCurrent: v" + Updater.installedName(this));
                    else offerUpdate(i);
                });
            } catch (Exception e) {
                ui.post(() -> { dlg.dismiss(); message("Could not reach GitHub. Check the screen's internet connection.\n\n" + e.getMessage()); });
            }
        }).start();
    }

    private void offerUpdate(final Updater.Info i) {
        String notes = i.notes == null || i.notes.trim().isEmpty() ? "" : "\n\nWhat's new:\n" + i.notes.trim();
        new AlertDialog.Builder(this).setTitle("New version available")
                .setMessage("Current: v" + Updater.installedName(this) + "\nNew: " + i.tag + notes)
                .setPositiveButton("Update now", (d, w) -> install(i))
                .setNegativeButton("Later", null).show();
    }

    private void install(final Updater.Info i) {
        final TextView status = Ui.text(this, "Downloading… 0%", 18, Ui.TEXT, false);
        status.setPadding(dp(24), dp(20), dp(24), dp(20));
        final AlertDialog dlg = new AlertDialog.Builder(this).setView(status).setCancelable(false).show();
        new Thread(() -> {
            try {
                final int[] last = {-1};
                Updater.downloadAndInstall(this, i, (done, total) -> {
                    int pct = total > 0 ? (int) (done * 100 / total) : -1;
                    if (pct != last[0]) {
                        last[0] = pct;
                        ui.post(() -> status.setText(pct >= 0 ? "Downloading… " + pct + "%" : "Downloading… " + (done / 1024) + " KB"));
                    }
                });
                ui.post(dlg::dismiss);
            } catch (Exception e) {
                ui.post(() -> { dlg.dismiss(); message("Download failed:\n" + e.getMessage()); });
            }
        }).start();
    }
}
