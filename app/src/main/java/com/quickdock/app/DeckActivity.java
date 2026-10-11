package com.quickdock.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.HashMap;
import java.util.Map;

/**
 * مصمّم شاشة العدادات (Dash Deck):
 *  • أعلى: محاكي شاشة العدادات (Display 2) بشبكة 24 عموداً.
 *  • أسفل: مكتبة الودجتات: اسحب أي ودجت وأفلته على المحاكي.
 *  • في المحاكي: انقل، غيّر الحجم، احذف، أو افتح إعدادات الودجت.
 *  • «Deploy» يحفظ التصميم ويعرضه على الشاشة المختارة.
 */
public class DeckActivity extends Activity implements DeckGridView.Host {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private CarBridge car;
    private HandlerThread pollThread;
    private Handler pollHandler;
    private volatile boolean polling;

    private FrameLayout rootFrame;
    private DeckGridView grid;
    private TextView status, displayBtn, simLabel, ghost;
    private HorizontalScrollView libScroll;
    private int displayId, rows = 3;
    private final Map<String, Widget> cache = new HashMap<>();

    // سحب من المكتبة
    private String dragType;
    private View dragCard;

    private int dp(float v) { return Ui.dp(this, v); }

    // ======================= دورة الحياة =======================
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        car = new CarBridge();
        displayId = DeckStore.display(this);

        rootFrame = new FrameLayout(this);
        rootFrame.setBackgroundColor(Ui.BG);

        LinearLayout main = new LinearLayout(this);
        main.setOrientation(LinearLayout.VERTICAL);
        main.setPadding(dp(18), dp(12), dp(18), dp(14));
        rootFrame.addView(main, new FrameLayout.LayoutParams(-1, -1));

        main.addView(buildTopBar(), new LinearLayout.LayoutParams(-1, -2));

        simLabel = Ui.text(this, "", 12, Ui.MUTE, true);
        simLabel.setPadding(dp(4), dp(12), 0, dp(6));
        main.addView(simLabel);

        grid = new DeckGridView(this);
        grid.setHost(this);
        main.addView(grid, new LinearLayout.LayoutParams(-1, dp(200)));

        TextView hint = Ui.text(this, "Drag a widget from the library onto the display · tap a widget to select it · drag to move · "
                + "drag ◢ to resize · ✕ removes · ⚙ opens its settings · press Deploy when you are done", 12, Ui.MUTE, false);
        hint.setPadding(dp(4), dp(8), dp(4), 0);
        main.addView(hint);

        TextView libLabel = Ui.text(this, "WIDGET LIBRARY", 12, Ui.MUTE, true);
        libLabel.setPadding(dp(4), dp(16), 0, dp(8));
        main.addView(libLabel);
        main.addView(buildLibrary(), new LinearLayout.LayoutParams(-1, -2));

        // شبح السحب
        ghost = Ui.text(this, "", 16, Ui.ON_ACCENT, true);
        ghost.setGravity(Gravity.CENTER);
        ghost.setBackground(Ui.shape(Ui.ACCENT, dp(12), 0, 0));
        ghost.setElevation(dp(12));
        ghost.setVisibility(View.GONE);
        rootFrame.addView(ghost, new FrameLayout.LayoutParams(dp(170), dp(56)));

        setContentView(rootFrame);

        grid.setItems(DeckStore.load(this, false));
        applySimulatorSize();
        updateStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        startPolling();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopPolling();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopPolling();
        car.release();
    }

    // ======================= الواجهة =======================
    private TextView button(String label, boolean primary) {
        TextView b = Ui.text(this, label, 14, primary ? Ui.ON_ACCENT : Ui.TEXT, primary);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(16), dp(10), dp(16), dp(10));
        b.setBackground(Ui.shape(primary ? Ui.ACCENT : Ui.PANEL2, dp(10), primary ? Ui.ACCENT : Ui.LINE, dp(1)));
        return b;
    }

    private LinearLayout.LayoutParams gap() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = dp(8);
        return lp;
    }

    private View buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);

        TextView back = Ui.text(this, "‹", 30, Ui.TEXT, false);
        back.setPadding(dp(4), 0, dp(14), dp(4));
        back.setOnClickListener(v -> finish());
        bar.addView(back);

        TextView title = Ui.text(this, "JETOUR G700 — DASH DECK", 17, Ui.TEXT, true);
        bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));

        status = Ui.text(this, "", 13, Ui.MUTE, false);
        bar.addView(status, gap());

        displayBtn = button("", false);
        displayBtn.setOnClickListener(v -> chooseDisplay());
        bar.addView(displayBtn, gap());

        TextView preview = button("Preview here", false);
        preview.setOnClickListener(v -> {
            DeckStore.save(this, true, grid.getItems());
            DeckStore.setRows(this, rows);
            startActivity(new Intent(this, DeckDisplayActivity.class));
        });
        bar.addView(preview, gap());

        TextView stop = button("Stop", false);
        stop.setOnClickListener(v -> { DeckDisplayActivity.stopAll(); toast("Deck stopped"); });
        bar.addView(stop, gap());

        TextView revert = button("Revert", false);
        revert.setOnClickListener(v -> revert());
        bar.addView(revert, gap());

        TextView deploy = button("Deploy", true);
        deploy.setOnClickListener(v -> deploy());
        bar.addView(deploy, gap());
        return bar;
    }

    private View buildLibrary() {
        libScroll = new HorizontalScrollView(this);
        libScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setClipChildren(false);
        for (String id : Widget.ALL) row.addView(libCard(Widget.meta(id)), cardLp());

        // خانة «ودجت جديد» (للمستقبل)
        LinearLayout soon = card();
        soon.setBackground(Ui.dashed(dp(14), Ui.DASH, dp(2), dp(8), dp(5)));
        soon.addView(Ui.text(this, "＋", 28, Ui.MUTE, true));
        TextView t = Ui.text(this, "New widget", 15, Ui.MUTE, true);
        soon.addView(t);
        soon.addView(Ui.text(this, "(coming soon)", 12, Ui.MUTE, false));
        row.addView(soon, cardLp());

        libScroll.addView(row);
        return libScroll;
    }

    private LinearLayout.LayoutParams cardLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(190), dp(104));
        lp.rightMargin = dp(12);
        return lp;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER);
        c.setPadding(dp(10), dp(8), dp(10), dp(8));
        c.setBackground(Ui.shape(Ui.PANEL2, dp(14), Ui.LINE, dp(1)));
        return c;
    }

    private View libCard(final Widget.Meta m) {
        LinearLayout c = card();
        c.addView(Ui.text(this, m.emoji, 28, Ui.TEXT, false));
        TextView name = Ui.text(this, m.title, 16, Ui.TEXT, true);
        name.setGravity(Gravity.CENTER);
        c.addView(name);
        c.addView(Ui.text(this, "(" + m.defCols + " columns)", 12, Ui.MUTE, false));
        c.setOnTouchListener(new View.OnTouchListener() {
            float sx, sy;
            boolean dragging;
            Runnable longPress;

            @Override public boolean onTouch(final View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        sx = e.getRawX();
                        sy = e.getRawY();
                        dragging = false;
                        longPress = () -> { dragging = true; beginDrag(v, m, sx, sy); };
                        ui.postDelayed(longPress, 320);   // ضغط مطوّل يبدأ السحب
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        float dx = e.getRawX() - sx, dy = e.getRawY() - sy;
                        if (!dragging) {
                            if (-dy > dp(14) && -dy > Math.abs(dx)) {   // سحب لأعلى = بدء السحب
                                ui.removeCallbacks(longPress);
                                dragging = true;
                                beginDrag(v, m, e.getRawX(), e.getRawY());
                            } else if (Math.abs(dx) > dp(10)) {
                                ui.removeCallbacks(longPress);          // تمرير أفقي للمكتبة
                            }
                        }
                        if (dragging) updateDrag(e.getRawX(), e.getRawY());
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        ui.removeCallbacks(longPress);
                        if (dragging) {
                            endDrag(e.getRawX(), e.getRawY(), e.getActionMasked() == MotionEvent.ACTION_UP);
                            dragging = false;
                        }
                        return true;
                    default:
                        return true;
                }
            }
        });
        return c;
    }

    // ======================= السحب من المكتبة =======================
    private void beginDrag(View card, Widget.Meta m, float rawX, float rawY) {
        dragType = m.id;
        dragCard = card;
        card.setAlpha(0.45f);
        if (card.getParent() != null) card.getParent().requestDisallowInterceptTouchEvent(true);
        if (libScroll != null) libScroll.requestDisallowInterceptTouchEvent(true);
        ghost.setText(m.emoji + "  " + m.title);
        ghost.setVisibility(View.VISIBLE);
        updateDrag(rawX, rawY);
    }

    private void updateDrag(float rawX, float rawY) {
        int[] rl = new int[2];
        rootFrame.getLocationOnScreen(rl);
        ghost.setX(rawX - rl[0] - dp(85));
        ghost.setY(rawY - rl[1] - dp(28));
        if (dragType != null) grid.setDrop(rawX, rawY, dragType);
    }

    private void endDrag(float rawX, float rawY, boolean dropped) {
        boolean over = dropped && grid.setDrop(rawX, rawY, dragType);
        boolean ok = over && grid.commitDrop(dragType);
        if (over && !ok) toast("No free space for this widget — resize or remove one first");
        grid.clearDrop();
        ghost.setVisibility(View.GONE);
        if (dragCard != null) dragCard.setAlpha(1f);
        dragType = null;
        dragCard = null;
    }

    // ======================= الشاشة المستهدفة =======================
    /** يضبط ارتفاع المحاكي وعدد الصفوف بحسب أبعاد الشاشة المستهدفة. */
    private void applySimulatorSize() {
        Point sz = DeckStore.displaySize(this, displayId);
        int w = sz != null ? sz.x : 3840, h = sz != null ? sz.y : 720;
        rows = DeckStore.rowsFor(w, h);
        int availW = getResources().getDisplayMetrics().widthPixels - dp(36);
        int simH = Math.round(availW * (h / (float) w)) + dp(36);
        int maxH = Math.round(getResources().getDisplayMetrics().heightPixels * 0.42f);
        simH = Math.min(Math.max(simH, dp(130)), maxH);
        grid.setLayoutParams(new LinearLayout.LayoutParams(-1, simH));
        grid.setRows(rows);
        simLabel.setText("INSTRUMENT DISPLAY SIMULATION — Display " + displayId
                + (sz != null ? "  (" + sz.x + "×" + sz.y + ")" : "  (not detected — default proportions)"));
        displayBtn.setText("Display " + displayId + " ▾");
    }

    private void chooseDisplay() {
        DisplayManager dm = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        final java.util.List<Integer> ids = new java.util.ArrayList<>();
        final java.util.List<String> labels = new java.util.ArrayList<>();
        if (dm != null) {
            for (Display d : dm.getDisplays()) {
                Point p = new Point();
                d.getRealSize(p);
                ids.add(d.getDisplayId());
                labels.add("Display " + d.getDisplayId() + " — " + d.getName() + "   " + p.x + "×" + p.y);
            }
        }
        if (!ids.contains(DeckStore.DEFAULT_DISPLAY)) {
            ids.add(DeckStore.DEFAULT_DISPLAY);
            labels.add("Display " + DeckStore.DEFAULT_DISPLAY + " — instrument cluster (not detected by this app)");
        }
        int checked = Math.max(0, ids.indexOf(displayId));
        new AlertDialog.Builder(this).setTitle("Deploy to which display?")
                .setSingleChoiceItems(labels.toArray(new String[0]), checked, (d, which) -> {
                    displayId = ids.get(which);
                    DeckStore.setDisplay(this, displayId);
                    applySimulatorSize();
                    onChanged();
                    d.dismiss();
                })
                .setNegativeButton("Cancel", null).show();
    }

    // ======================= النشر =======================
    private void deploy() {
        DeckStore.save(this, true, grid.getItems());
        DeckStore.setRows(this, rows);
        updateStatus();
        String err = DeckDisplayActivity.launchOn(this, displayId);
        if (err == null) {
            toast("Deployed to Display " + displayId);
            return;
        }
        new AlertDialog.Builder(this).setTitle("Saved, but Display " + displayId + " could not be opened")
                .setMessage("Android refused to open the deck on that display:\n" + err
                        + "\n\nYour layout is saved. You can start it over ADB:\nadb shell am start --display " + displayId
                        + " -n " + getPackageName() + "/.DeckDisplayActivity\n\nOr preview it on this screen.")
                .setPositiveButton("Preview here", (d, w) -> startActivity(new Intent(this, DeckDisplayActivity.class)))
                .setNegativeButton("Close", null).show();
    }

    private void revert() {
        grid.setItems(DeckStore.copy(DeckStore.load(this, true)));
        grid.setRows(rows);
        onChanged();
        toast("Reverted to the deployed layout");
    }

    // ======================= Host =======================
    @Override
    public void onChanged() {
        DeckStore.save(this, false, grid.getItems());
        updateStatus();
    }

    @Override
    public void onSettings(DeckItem item) {
        Widget w = cache.get(item.type);
        if (w == null) {
            w = Widget.create(item.type, this, car);
            if (w != null) cache.put(item.type, w);
        }
        if (w != null) w.openSettings();
    }

    @Override
    public void onMessage(String msg) { toast(msg); }

    private void updateStatus() {
        boolean dirty = !DeckStore.toJson(grid.getItems()).equals(DeckStore.toJson(DeckStore.load(this, true)));
        status.setText(dirty ? "● Draft — not deployed yet" : "✓ Same as deployed");
        status.setTextColor(dirty ? Ui.ACCENT : Ui.MUTE);
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    // ======================= قراءة السيارة (لإعدادات حزام الأمان) =======================
    private void startPolling() {
        if (polling) return;
        polling = true;
        pollThread = new HandlerThread("deck-edit-poll");
        pollThread.start();
        pollHandler = new Handler(pollThread.getLooper());
        final Context app = getApplicationContext();
        pollHandler.post(new Runnable() {
            @Override public void run() {
                if (!polling) return;
                try {
                    car.connect(app);
                    car.poll();
                } catch (Throwable ignored) { }
                if (polling) pollHandler.postDelayed(this, 1000);
            }
        });
    }

    private void stopPolling() {
        polling = false;
        if (pollThread != null) { pollThread.quitSafely(); pollThread = null; }
    }
}
