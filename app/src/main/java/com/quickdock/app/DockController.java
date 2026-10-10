package com.quickdock.app;

import android.animation.ObjectAnimator;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * الدرج الأيمن للتشغيل السريع، كطبقة (Overlay) فوق كل التطبيقات:
 *  • لسان على الحافة اليمنى + شريط سحب شفاف: اسحب لليسار من الحافة أو اضغط اللسان.
 *  • الدرج (مضغوط ويتوسط الحافة): 5 خانات مفضلة، زر «All apps»، زر مبدّل التطبيقات، وزر القلم.
 *  • إخفاء تلقائي بعد 4 ثوانٍ بلا لمس، أو بالضغط خارج الدرج. يتوقف المؤقت أثناء التعديل وعرض الصفحات الكاملة.
 *  • مبدّل التطبيقات: التطبيقات العاملة/الأخيرة (بدون QuickDock) ← استعادة، إغلاق، أو إغلاق الكل.
 */
final class DockController {
    private static final int AUTO_HIDE_MS = 4000;
    private static final int SLOTS = Favorites.SLOTS;
    private static final int DRAWER_W = 252;   // dp
    private static final int SLOT_H = 56;      // dp

    private final Context ctx;
    private final WindowManager wm;
    private final Handler h = new Handler(Looper.getMainLooper());

    // الحالة
    private String[] favs;
    private String[] draft;
    private boolean editing, isOpen, closing, allOpen, switchOpen;
    private int pickIdx = -1, overIdx = -1;
    private List<AppEntry> apps = new ArrayList<>();
    private List<RecentApps.Item> recents = new ArrayList<>();
    private Gest g;

    // الواجهة
    private FrameLayout edge;
    private TouchFrame root, drawer;
    private View scrim, timerFill;
    private TextView title, editBtn, allTitle, toastView, ghostLabel;
    private SwitcherIcon switchBtn;
    private LinearLayout slotsBox, footView, footEdit, ghost;
    private ImageView ghostIcon;
    private FrameLayout allPage, switchPage;
    private GridLayout grid, switchGrid;
    private TextView closeAllBtn;
    private final View[] slotViews = new View[SLOTS];
    private final boolean[] slotEmpty = new boolean[SLOTS];
    private final List<ObjectAnimator> wiggles = new ArrayList<>();

    private final Runnable autoClose = this::close;

    private static final class Gest {
        final int idx;
        final View el;
        final float sx, sy;
        boolean moved, lp, drag;
        Runnable lpTask;

        Gest(int idx, View el, float sx, float sy) { this.idx = idx; this.el = el; this.sx = sx; this.sy = sy; }
    }

    DockController(Context c) {
        ctx = c;
        wm = (WindowManager) c.getSystemService(Context.WINDOW_SERVICE);
        favs = sanitize(Favorites.load(c));
        AppRepo.loadAll(c, list -> h.post(() -> apps = list));
    }

    private int dp(float v) { return Ui.dp(ctx, v); }

    /** يستبدل التطبيقات التي لم تعد مثبّتة بخانات فارغة. */
    private String[] sanitize(String[] f) {
        String[] out = f.clone();
        for (int i = 0; i < out.length; i++) if (AppRepo.resolve(ctx, out[i]) == null) out[i] = "";
        return out;
    }

    private WindowManager.LayoutParams overlay(int w, int hgt) {
        return new WindowManager.LayoutParams(w, hgt, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
    }

    // =====================================================================
    //  اللسان على الحافة + شريط السحب
    // =====================================================================
    void showEdge() {
        if (edge != null) return;
        final FrameLayout zone = new FrameLayout(ctx);
        zone.setBackgroundColor(0x01000000);   // شبه شفاف حتى يستقبل اللمس

        final View tab = new View(ctx);
        float r = dp(14);
        GradientDrawable tabBg = new GradientDrawable();
        tabBg.setColor(Ui.PANEL2);
        tabBg.setCornerRadii(new float[]{r, r, 0, 0, 0, 0, r, r});
        tab.setBackground(tabBg);
        zone.addView(tab, new FrameLayout.LayoutParams(dp(26), dp(120), Gravity.END | Gravity.CENTER_VERTICAL));

        View bar = new View(ctx);   // الخط المميّز داخل اللسان
        GradientDrawable barBg = Ui.shape(Ui.ACCENT, dp(2), 0, 0);
        barBg.setAlpha(205);
        bar.setBackground(barBg);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(dp(4), dp(44), Gravity.END | Gravity.CENTER_VERTICAL);
        blp.rightMargin = dp(14);
        zone.addView(bar, blp);

        zone.setOnTouchListener(new View.OnTouchListener() {
            float x0, y0;
            boolean fired;

            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        x0 = e.getRawX(); y0 = e.getRawY(); fired = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (!fired && x0 - e.getRawX() > dp(36)) { fired = true; open(false); }   // سحب لليسار
                        return true;
                    case MotionEvent.ACTION_UP: {
                        boolean tap = Math.abs(e.getRawX() - x0) < dp(10) && Math.abs(e.getRawY() - y0) < dp(10);
                        int[] loc = new int[2];
                        tab.getLocationOnScreen(loc);
                        boolean onTab = y0 >= loc[1] - dp(8) && y0 <= loc[1] + tab.getHeight() + dp(8);
                        if (!fired && tap && onTab) open(false);   // أو الضغط على اللسان
                        return true;
                    }
                    default:
                        return true;
                }
            }
        });

        WindowManager.LayoutParams lp = overlay(dp(34), WindowManager.LayoutParams.MATCH_PARENT);
        lp.gravity = Gravity.END | Gravity.TOP;
        wm.addView(zone, lp);
        edge = zone;
    }

    // =====================================================================
    //  فتح الدرج وإغلاقه
    // =====================================================================
    void open(boolean startEditing) {
        if (isOpen) { if (startEditing && !editing) enterEdit(); return; }
        isOpen = true;
        closing = false;
        buildRoot();
        wm.addView(root, overlay(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT));
        if (edge != null) edge.setVisibility(View.INVISIBLE);
        renderSlots();
        drawer.setTranslationX(dp(DRAWER_W + 8));
        drawer.animate().translationX(0).setDuration(280).setInterpolator(new DecelerateInterpolator()).start();
        if (startEditing) enterEdit(); else armTimer();
        // حدّث قائمة التطبيقات لالتقاط ما ثُبّت حديثاً
        AppRepo.loadAll(ctx, list -> h.post(() -> { apps = list; if (allOpen) fillGrid(); }));
    }

    void close() {
        if (!isOpen || closing || editing) return;
        closing = true;
        h.removeCallbacks(autoClose);
        if (timerFill != null) timerFill.animate().cancel();
        drawer.animate().translationX(dp(DRAWER_W + 8)).setDuration(220).withEndAction(this::removeRoot).start();
    }

    private void removeRoot() {
        stopWiggle();
        if (root != null) {
            try { wm.removeView(root); } catch (Exception ignored) { }
        }
        root = null;
        isOpen = false;
        closing = false;
        allOpen = false;
        switchOpen = false;
        if (edge != null) edge.setVisibility(View.VISIBLE);
    }

    void destroy() {
        h.removeCallbacksAndMessages(null);
        removeRoot();
        if (edge != null) {
            try { wm.removeView(edge); } catch (Exception ignored) { }
            edge = null;
        }
    }

    // =====================================================================
    //  المؤقت (إخفاء تلقائي بعد 4 ثوانٍ)
    // =====================================================================
    private void armTimer() {
        h.removeCallbacks(autoClose);
        if (timerFill == null) return;
        timerFill.animate().cancel();
        timerFill.setScaleX(1f);
        if (editing || allOpen || switchOpen) {          // متوقف أثناء التعديل وعرض الصفحات الكاملة
            timerFill.setBackgroundColor(Ui.MUTE);
            return;
        }
        timerFill.setBackgroundColor(Ui.ACCENT);
        timerFill.animate().scaleX(0f).setDuration(AUTO_HIDE_MS).setInterpolator(new LinearInterpolator()).start();
        h.postDelayed(autoClose, AUTO_HIDE_MS);
    }

    // =====================================================================
    //  بناء الواجهة
    // =====================================================================
    private TextView button(String label, boolean primary) {
        TextView b = Ui.text(ctx, label, 17, primary ? Ui.ON_ACCENT : Ui.TEXT, primary);
        b.setGravity(Gravity.CENTER);
        b.setBackground(Ui.shape(primary ? Ui.ACCENT : Ui.PANEL2, dp(12), primary ? Ui.ACCENT : Ui.LINE, dp(1)));
        b.setMinHeight(dp(46));
        return b;
    }

    private void buildRoot() {
        root = new TouchFrame(ctx);
        root.setClipChildren(false);

        // خلفية معتمة: الضغط عليها يغلق الدرج
        scrim = new View(ctx);
        scrim.setBackgroundColor(0x47000000);
        scrim.setOnClickListener(v -> {
            if (allOpen || switchOpen) return;
            if (editing) toast("Tap Done or Cancel to leave edit mode");
            else close();
        });
        root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));

        // الدرج: مضغوط الارتفاع ويتوسط الحافة اليمنى
        drawer = new TouchFrame(ctx);
        float rr = dp(18);
        GradientDrawable dbg = new GradientDrawable();
        dbg.setColor(Ui.PANEL);
        dbg.setStroke(dp(1), Ui.LINE);
        dbg.setCornerRadii(new float[]{rr, rr, 0, 0, 0, 0, rr, rr});
        drawer.setBackground(dbg);
        drawer.setClipChildren(false);
        drawer.setClickable(true);                      // لا تمرّر اللمس للخلفية المعتمة
        drawer.onAnyTouch = () -> { if (isOpen && !closing) armTimer(); };
        root.addView(drawer, new FrameLayout.LayoutParams(dp(DRAWER_W), -2, Gravity.END | Gravity.CENTER_VERTICAL));

        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setClipChildren(false);
        col.setPadding(dp(14), dp(14), dp(14), dp(14));
        drawer.addView(col, new FrameLayout.LayoutParams(-1, -2));

        // الرأس: العنوان + المبدّل + القلم
        LinearLayout head = new LinearLayout(ctx);
        head.setGravity(Gravity.CENTER_VERTICAL);
        title = Ui.text(ctx, "Quick launch", 19, Ui.TEXT, true);
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));

        switchBtn = new SwitcherIcon(ctx);
        switchBtn.setBackground(Ui.shape(Ui.PANEL2, dp(11), Ui.LINE, dp(1)));
        switchBtn.setOnClickListener(v -> showSwitcher());
        LinearLayout.LayoutParams sbl = new LinearLayout.LayoutParams(dp(42), dp(42));
        sbl.rightMargin = dp(8);
        head.addView(switchBtn, sbl);

        editBtn = Ui.text(ctx, "✎", 20, Ui.TEXT, false);
        editBtn.setGravity(Gravity.CENTER);
        editBtn.setBackground(Ui.shape(Ui.PANEL2, dp(11), Ui.LINE, dp(1)));
        editBtn.setOnClickListener(v -> enterEdit());
        head.addView(editBtn, new LinearLayout.LayoutParams(dp(42), dp(42)));
        col.addView(head, new LinearLayout.LayoutParams(-1, dp(42)));

        // شريط المؤقت
        FrameLayout timer = new FrameLayout(ctx);
        timer.setBackground(Ui.shape(Ui.LINE, dp(2), 0, 0));
        timerFill = new View(ctx);
        timerFill.setBackgroundColor(Ui.ACCENT);
        timerFill.setPivotX(0f);
        timer.addView(timerFill, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, dp(3));
        tlp.topMargin = dp(10);
        col.addView(timer, tlp);

        // الخانات (ارتفاع ثابت صغير)
        slotsBox = new LinearLayout(ctx);
        slotsBox.setOrientation(LinearLayout.VERTICAL);
        slotsBox.setClipChildren(false);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, -2);
        slp.topMargin = dp(10);
        slp.bottomMargin = dp(10);
        col.addView(slotsBox, slp);

        // التذييل: All apps  |  Cancel + Done
        footView = new LinearLayout(ctx);
        TextView all = button("All apps", false);
        all.setOnClickListener(v -> showAll(-1));
        footView.addView(all, new LinearLayout.LayoutParams(0, -2, 1f));
        col.addView(footView, new LinearLayout.LayoutParams(-1, -2));

        footEdit = new LinearLayout(ctx);
        TextView cancel = button("Cancel", false);
        cancel.setOnClickListener(v -> cancelEdit());
        TextView done = button("Done", true);
        done.setOnClickListener(v -> commitEdit());
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, -2, 1f);
        cl.rightMargin = dp(5);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(0, -2, 1f);
        dl.leftMargin = dp(5);
        footEdit.addView(cancel, cl);
        footEdit.addView(done, dl);
        col.addView(footEdit, new LinearLayout.LayoutParams(-1, -2));

        buildAllPage();
        buildSwitchPage();

        // شبح السحب
        ghost = new LinearLayout(ctx);
        ghost.setGravity(Gravity.CENTER_VERTICAL);
        ghost.setPadding(dp(10), 0, dp(10), 0);
        ghost.setBackground(Ui.shape(Ui.ACCENT, dp(14), 0, 0));
        ghost.setElevation(dp(10));
        ghostIcon = new ImageView(ctx);
        ghost.addView(ghostIcon, new LinearLayout.LayoutParams(dp(36), dp(36)));
        ghostLabel = Ui.text(ctx, "", 17, Ui.ON_ACCENT, true);
        ghostLabel.setSingleLine(true);
        ghostLabel.setPadding(dp(10), 0, 0, 0);
        ghost.addView(ghostLabel, new LinearLayout.LayoutParams(0, -2, 1f));
        ghost.setVisibility(View.GONE);
        root.addView(ghost, new FrameLayout.LayoutParams(dp(DRAWER_W - 28), dp(SLOT_H)));

        // رسالة قصيرة
        toastView = Ui.text(ctx, "", 18, Ui.TEXT, false);
        toastView.setBackground(Ui.shape(0xCC000000, dp(12), Ui.LINE, dp(1)));
        toastView.setPadding(dp(20), dp(12), dp(20), dp(12));
        toastView.setAlpha(0f);
        FrameLayout.LayoutParams tvp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        tvp.bottomMargin = dp(30);
        root.addView(toastView, tvp);
    }

    private void toast(String s) {
        if (toastView == null) return;
        toastView.setText(s);
        toastView.animate().cancel();
        toastView.setAlpha(1f);
        toastView.animate().alpha(0f).setStartDelay(1800).setDuration(250).start();
    }

    // =====================================================================
    //  الخانات
    // =====================================================================
    private String[] list() { return editing ? draft : favs; }

    private void renderSlots() {
        if (slotsBox == null) return;
        stopWiggle();
        slotsBox.removeAllViews();
        String[] l = list();
        for (int i = 0; i < SLOTS; i++) {
            AppEntry a = AppRepo.resolve(ctx, l[i]);
            slotEmpty[i] = a == null;
            View s = makeSlot(i, a);
            slotViews[i] = s;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(SLOT_H));
            if (i < SLOTS - 1) lp.bottomMargin = dp(8);
            slotsBox.addView(s, lp);
            if (editing && a != null) {
                ObjectAnimator w = ObjectAnimator.ofFloat(s, View.ROTATION, -0.6f, 0.6f);
                w.setDuration(350);
                w.setRepeatMode(ObjectAnimator.REVERSE);
                w.setRepeatCount(ObjectAnimator.INFINITE);
                w.start();
                wiggles.add(w);
            }
        }
        title.setText(editing ? "Edit favorites" : "Quick launch");
        editBtn.setVisibility(editing ? View.GONE : View.VISIBLE);
        switchBtn.setVisibility(editing ? View.GONE : View.VISIBLE);
        footView.setVisibility(editing ? View.GONE : View.VISIBLE);
        footEdit.setVisibility(editing ? View.VISIBLE : View.GONE);
    }

    private void stopWiggle() {
        for (ObjectAnimator w : wiggles) w.cancel();
        wiggles.clear();
    }

    private void styleSlot(View slot, boolean empty, boolean over) {
        if (empty) {
            slot.setBackground(over ? Ui.shape(Color.TRANSPARENT, dp(14), Ui.ACCENT, dp(2))
                    : Ui.dashed(dp(14), Ui.DASH, dp(2), dp(8), dp(5)));
        } else {
            slot.setBackground(Ui.shape(Ui.PANEL2, dp(14), over ? Ui.ACCENT : Ui.LINE, dp(over ? 2 : 1)));
        }
    }

    private FrameLayout makeSlot(final int idx, AppEntry a) {
        boolean empty = a == null;
        FrameLayout slot = new FrameLayout(ctx);
        slot.setClipChildren(false);
        styleSlot(slot, empty, false);

        LinearLayout row = new LinearLayout(ctx);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), 0, dp(10), 0);
        TextView label;
        if (empty) {
            TextView plus = Ui.text(ctx, "+", 26, Ui.MUTE, false);
            plus.setGravity(Gravity.CENTER);
            plus.setBackground(Ui.dashed(dp(12), Ui.DASH, dp(2), dp(6), dp(4)));
            row.addView(plus, new LinearLayout.LayoutParams(dp(38), dp(38)));
            label = Ui.text(ctx, "Add app", 17, Ui.MUTE, false);
        } else {
            ImageView iv = new ImageView(ctx);
            iv.setImageDrawable(a.icon);
            iv.setPadding(dp(4), dp(4), dp(4), dp(4));
            iv.setBackground(Ui.shape(Ui.ICON_BG, dp(11), 0, 0));
            row.addView(iv, new LinearLayout.LayoutParams(dp(38), dp(38)));
            label = Ui.text(ctx, a.label, 17, Ui.TEXT, false);
        }
        label.setSingleLine(true);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        label.setPadding(dp(12), 0, 0, 0);
        row.addView(label, new LinearLayout.LayoutParams(0, -2, 1f));
        slot.addView(row, new FrameLayout.LayoutParams(-1, -1));

        if (!empty && editing) {   // شارة الحذف ✕
            TextView x = Ui.text(ctx, "✕", 14, 0xFFFFFFFF, true);
            x.setGravity(Gravity.CENTER);
            x.setBackground(Ui.shape(Ui.DANGER, dp(14), Ui.PANEL, dp(2)));
            x.setOnClickListener(v -> { draft[idx] = ""; renderSlots(); });
            FrameLayout.LayoutParams xl = new FrameLayout.LayoutParams(dp(28), dp(28), Gravity.TOP | Gravity.START);
            xl.setMargins(-dp(7), -dp(7), 0, 0);
            slot.addView(x, xl);
        }
        slot.setOnTouchListener((v, e) -> onSlotTouch(v, e, idx));
        return slot;
    }

    // ---------------- لمس الخانة: ضغطة، ضغط مطوّل، سحب ----------------
    private boolean onSlotTouch(View el, MotionEvent e, int idx) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                final Gest gest = new Gest(idx, el, e.getRawX(), e.getRawY());
                g = gest;
                if (!editing && !favs[idx].isEmpty()) {
                    gest.lpTask = () -> {
                        if (g == gest && !gest.moved) { gest.lp = true; enterEdit(); }   // ضغط مطوّل = وضع التعديل
                    };
                    h.postDelayed(gest.lpTask, 550);
                }
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (g == null) return true;
                float dx = e.getRawX() - g.sx, dy = e.getRawY() - g.sy;
                if (!g.moved && Math.hypot(dx, dy) > dp(10)) {
                    g.moved = true;
                    if (g.lpTask != null) h.removeCallbacks(g.lpTask);
                    if (editing && !draft[g.idx].isEmpty()) startDrag();
                }
                if (g.drag) updateDrag(e.getRawX(), e.getRawY());
                return true;
            }
            case MotionEvent.ACTION_UP:
                endGesture(e.getRawY(), false);
                return true;
            case MotionEvent.ACTION_CANCEL:
                endGesture(e.getRawY(), true);
                return true;
            default:
                return true;
        }
    }

    private void startDrag() {
        AppEntry a = AppRepo.resolve(ctx, draft[g.idx]);
        if (a == null) return;
        ghostIcon.setImageDrawable(a.icon);
        ghostLabel.setText(a.label);
        ghost.setVisibility(View.VISIBLE);
        g.drag = true;
        g.el.setAlpha(0.35f);
    }

    private void updateDrag(float rx, float ry) {
        int[] rl = new int[2];
        root.getLocationOnScreen(rl);
        ghost.setX(rx - rl[0] - dp((DRAWER_W - 28) / 2f));
        ghost.setY(ry - rl[1] - dp(SLOT_H / 2f));
        int over = slotAt(ry);
        if (over != overIdx) {
            if (overIdx >= 0) styleSlot(slotViews[overIdx], slotEmpty[overIdx], false);
            overIdx = over;
            if (over >= 0 && over != g.idx) styleSlot(slotViews[over], slotEmpty[over], true);
        }
    }

    private int slotAt(float rawY) {
        int[] loc = new int[2];
        for (int i = 0; i < SLOTS; i++) {
            slotViews[i].getLocationOnScreen(loc);
            if (rawY >= loc[1] && rawY <= loc[1] + slotViews[i].getHeight()) return i;
        }
        return -1;
    }

    private void endGesture(float rawY, boolean cancelled) {
        Gest s = g;
        g = null;
        if (s == null) return;
        if (s.lpTask != null) h.removeCallbacks(s.lpTask);
        if (s.drag) {
            ghost.setVisibility(View.GONE);
            int to = slotAt(rawY);
            overIdx = -1;
            if (!cancelled && to >= 0 && to != s.idx) {   // إعادة الترتيب: انقل العنصر إلى موضع الهدف
                List<String> l = new ArrayList<>(Arrays.asList(draft));
                String moved = l.remove(s.idx);
                l.add(to, moved);
                draft = l.toArray(new String[0]);
            }
            renderSlots();
            return;
        }
        if (cancelled || s.moved || s.lp) return;
        if (editing) {
            if (draft[s.idx].isEmpty()) showAll(s.idx);                     // خانة فارغة: اختر تطبيقاً
        } else if (!favs[s.idx].isEmpty()) {
            launch(favs[s.idx]);                                             // تشغيل ثم إغلاق الدرج
        } else {
            enterEdit();                                                     // خانة فارغة: تعديل + اختيار
            showAll(s.idx);
        }
    }

    // =====================================================================
    //  وضع التعديل
    // =====================================================================
    private void enterEdit() {
        if (editing) return;
        editing = true;
        draft = favs.clone();
        armTimer();
        renderSlots();
    }

    private void commitEdit() {
        favs = draft.clone();
        Favorites.save(ctx, favs);
        editing = false;
        draft = null;
        renderSlots();
        armTimer();
        toast("Favorites saved");
    }

    private void cancelEdit() {
        editing = false;
        draft = null;
        renderSlots();
        armTimer();
    }

    // =====================================================================
    //  كل التطبيقات / الاختيار
    // =====================================================================
    private LinearLayout fullPageColumn(FrameLayout page) {
        page.setBackgroundColor(0xF50A0D0F);
        page.setClickable(true);
        page.setVisibility(View.GONE);
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(60), dp(36), dp(60), dp(24));
        page.addView(col, new FrameLayout.LayoutParams(-1, -1));
        root.addView(page, new FrameLayout.LayoutParams(-1, -1));
        return col;
    }

    private void buildAllPage() {
        allPage = new FrameLayout(ctx);
        LinearLayout col = fullPageColumn(allPage);

        LinearLayout top = new LinearLayout(ctx);
        top.setGravity(Gravity.CENTER_VERTICAL);
        allTitle = Ui.text(ctx, "All apps", 32, Ui.TEXT, true);
        top.addView(allTitle, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView close = button("Close", false);
        close.setPadding(dp(22), dp(12), dp(22), dp(12));
        close.setOnClickListener(v -> hideAll());
        top.addView(close, new LinearLayout.LayoutParams(-2, -2));
        col.addView(top, new LinearLayout.LayoutParams(-1, -2));

        ScrollView sv = new ScrollView(ctx);
        grid = new GridLayout(ctx);
        grid.setColumnCount(6);
        sv.addView(grid, new FrameLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, 0, 1f);
        slp.topMargin = dp(22);
        col.addView(sv, slp);
    }

    private void showAll(int pick) {
        pickIdx = pick;
        allTitle.setText(pick < 0 ? "All apps" : "Choose an app");
        fillGrid();
        allPage.setVisibility(View.VISIBLE);
        allOpen = true;
        armTimer();   // يتوقف المؤقت
    }

    private void hideAll() {
        if (allPage != null) allPage.setVisibility(View.GONE);
        allOpen = false;
        pickIdx = -1;
        if (isOpen) armTimer();
    }

    private void fillGrid() {
        grid.removeAllViews();
        if (apps.isEmpty()) {
            grid.setColumnCount(1);
            grid.addView(Ui.text(ctx, "Loading apps…", 20, Ui.MUTE, false));
            return;
        }
        grid.setColumnCount(6);
        int totalW = ctx.getResources().getDisplayMetrics().widthPixels - dp(120);
        int gap = dp(22);
        int cell = (totalW - gap * 5) / 6;
        List<String> inFavs = Arrays.asList(list());
        for (final AppEntry a : apps) {
            LinearLayout c = new LinearLayout(ctx);
            c.setOrientation(LinearLayout.VERTICAL);
            c.setGravity(Gravity.CENTER);
            c.setBackground(Ui.shape(Ui.PANEL, dp(18), inFavs.contains(a.pkg) ? Ui.ACCENT : Ui.LINE, dp(1)));
            ImageView iv = new ImageView(ctx);
            iv.setImageDrawable(a.icon);
            c.addView(iv, new LinearLayout.LayoutParams(dp(60), dp(60)));
            TextView t = Ui.text(ctx, a.label, 17, Ui.TEXT, false);
            t.setGravity(Gravity.CENTER);
            t.setMaxLines(1);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            t.setPadding(dp(8), dp(8), dp(8), 0);
            c.addView(t, new LinearLayout.LayoutParams(-1, -2));
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = cell;
            lp.height = dp(140);
            lp.setMargins(0, 0, gap, gap);
            c.setLayoutParams(lp);
            c.setOnClickListener(v -> onAppPicked(a));
            grid.addView(c);
        }
    }

    private void onAppPicked(AppEntry a) {
        if (pickIdx < 0) {
            launch(a.pkg);
            return;
        }
        if (draft == null) return;
        for (int i = 0; i < draft.length; i++) if (draft[i].equals(a.pkg)) draft[i] = "";   // بلا تكرار
        draft[pickIdx] = a.pkg;
        hideAll();
        renderSlots();
    }

    // =====================================================================
    //  مبدّل التطبيقات (التطبيقات العاملة/الأخيرة)
    // =====================================================================
    private void buildSwitchPage() {
        switchPage = new FrameLayout(ctx);
        LinearLayout col = fullPageColumn(switchPage);

        LinearLayout top = new LinearLayout(ctx);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(Ui.text(ctx, "Running apps", 32, Ui.TEXT, true), new LinearLayout.LayoutParams(0, -2, 1f));

        closeAllBtn = Ui.text(ctx, "Close all", 17, 0xFFFFFFFF, true);
        closeAllBtn.setGravity(Gravity.CENTER);
        closeAllBtn.setBackground(Ui.shape(Ui.DANGER, dp(12), Ui.DANGER, dp(1)));
        closeAllBtn.setPadding(dp(22), dp(12), dp(22), dp(12));
        closeAllBtn.setOnClickListener(v -> closeApps(allRecentPackages()));
        LinearLayout.LayoutParams cal = new LinearLayout.LayoutParams(-2, -2);
        cal.rightMargin = dp(12);
        top.addView(closeAllBtn, cal);

        TextView close = button("Back", false);
        close.setPadding(dp(22), dp(12), dp(22), dp(12));
        close.setOnClickListener(v -> hideSwitcher());
        top.addView(close, new LinearLayout.LayoutParams(-2, -2));
        col.addView(top, new LinearLayout.LayoutParams(-1, -2));

        ScrollView sv = new ScrollView(ctx);
        switchGrid = new GridLayout(ctx);
        sv.addView(switchGrid, new FrameLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, 0, 1f);
        slp.topMargin = dp(22);
        col.addView(sv, slp);
    }

    private void showSwitcher() {
        switchOpen = true;
        recents = new ArrayList<>();
        fillSwitcher(true);
        switchPage.setVisibility(View.VISIBLE);
        armTimer();   // يتوقف المؤقت
        if (!RecentApps.hasAccess(ctx)) return;
        new Thread(() -> {
            final List<RecentApps.Item> list = RecentApps.load(ctx);
            h.post(() -> {
                if (!switchOpen) return;
                recents = list;
                fillSwitcher(false);
            });
        }).start();
    }

    private void hideSwitcher() {
        if (switchPage != null) switchPage.setVisibility(View.GONE);
        switchOpen = false;
        if (isOpen) armTimer();
    }

    private List<String> allRecentPackages() {
        List<String> l = new ArrayList<>();
        for (RecentApps.Item it : recents) l.add(it.app.pkg);
        return l;
    }

    private void fillSwitcher(boolean loading) {
        switchGrid.removeAllViews();
        closeAllBtn.setVisibility(recents.isEmpty() ? View.GONE : View.VISIBLE);
        if (!RecentApps.hasAccess(ctx)) {
            switchGrid.setColumnCount(1);
            TextView t = Ui.text(ctx, "Usage access is needed to list running apps.\n\nGrant it once over ADB (the install page does this for you):\n"
                    + "appops set " + ctx.getPackageName() + " GET_USAGE_STATS allow", 19, Ui.MUTE, false);
            switchGrid.addView(t);
            return;
        }
        if (recents.isEmpty()) {
            switchGrid.setColumnCount(1);
            switchGrid.addView(Ui.text(ctx, loading ? "Loading…" : "No running apps", 22, Ui.MUTE, false));
            return;
        }
        int totalW = ctx.getResources().getDisplayMetrics().widthPixels - dp(120);
        int gap = dp(20);
        int cols = Math.max(3, totalW / dp(230));
        int cell = (totalW - gap * (cols - 1)) / cols;
        switchGrid.setColumnCount(cols);
        for (final RecentApps.Item it : recents) {
            FrameLayout card = new FrameLayout(ctx);
            card.setClipChildren(false);
            card.setBackground(Ui.shape(Ui.PANEL, dp(18), it.top ? Ui.ACCENT : Ui.LINE, dp(it.top ? 2 : 1)));

            LinearLayout inner = new LinearLayout(ctx);
            inner.setOrientation(LinearLayout.VERTICAL);
            inner.setGravity(Gravity.CENTER);
            ImageView iv = new ImageView(ctx);
            iv.setImageDrawable(it.app.icon);
            inner.addView(iv, new LinearLayout.LayoutParams(dp(64), dp(64)));
            TextView name = Ui.text(ctx, it.app.label, 18, Ui.TEXT, true);
            name.setGravity(Gravity.CENTER);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            name.setPadding(dp(10), dp(8), dp(10), 0);
            inner.addView(name, new LinearLayout.LayoutParams(-1, -2));
            TextView sub = Ui.text(ctx, it.top ? "Current · now" : RecentApps.ago(it.lastUsed), 14, it.top ? Ui.ACCENT : Ui.MUTE, false);
            sub.setGravity(Gravity.CENTER);
            inner.addView(sub, new LinearLayout.LayoutParams(-1, -2));
            card.addView(inner, new FrameLayout.LayoutParams(-1, -1));
            card.setOnClickListener(v -> restore(it.app.pkg));      // استعادة

            TextView x = Ui.text(ctx, "✕", 16, 0xFFFFFFFF, true);   // إغلاق هذا التطبيق
            x.setGravity(Gravity.CENTER);
            x.setBackground(Ui.shape(Ui.DANGER, dp(18), Ui.PANEL, dp(2)));
            x.setOnClickListener(v -> closeApps(Arrays.asList(it.app.pkg)));
            FrameLayout.LayoutParams xl = new FrameLayout.LayoutParams(dp(36), dp(36), Gravity.TOP | Gravity.END);
            xl.setMargins(0, dp(8), dp(8), 0);
            card.addView(x, xl);

            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = cell;
            lp.height = dp(160);
            lp.setMargins(0, 0, gap, gap);
            card.setLayoutParams(lp);
            switchGrid.addView(card);
        }
    }

    /** استعادة تطبيق عامل: تشغيل واجهته يُرجع مهمته الموجودة للأمام. */
    private void restore(String pkg) {
        launch(pkg);
    }

    /**
     * إغلاق تطبيقات: تطبيق عادي لا يملك صلاحية إغلاق نافذة تطبيق آخر مباشرة. الحل: إن كان أحدها ظاهراً الآن نعود
     * للشاشة الرئيسية ليصبح في الخلفية، ثم نطلب من النظام إنهاء عملياته (KILL_BACKGROUND_PROCESSES).
     * التطبيق الذي يشغّل صوتاً أو ملاحة (خدمة أمامية) قد لا يُغلق بهذه الطريقة.
     */
    private void closeApps(final List<String> pkgs) {
        if (pkgs.isEmpty()) return;
        boolean needHome = false;
        for (RecentApps.Item it : recents) if (it.top && pkgs.contains(it.app.pkg)) needHome = true;
        for (String p : pkgs) RecentApps.markClosed(ctx, p);

        // أزلها من القائمة فوراً
        List<RecentApps.Item> rest = new ArrayList<>();
        for (RecentApps.Item it : recents) if (!pkgs.contains(it.app.pkg)) rest.add(it);
        recents = rest;
        fillSwitcher(false);
        toast(pkgs.size() > 1 ? "Closing " + pkgs.size() + " apps…" : "Closing app…");

        if (needHome) {
            try {
                ctx.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception ignored) { }
        }
        final ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        long[] delays = needHome ? new long[]{900, 2400} : new long[]{250, 1500};
        for (long d : delays) {
            h.postDelayed(() -> {
                for (String p : pkgs) {
                    try { am.killBackgroundProcesses(p); } catch (Exception ignored) { }
                }
            }, d);
        }
    }

    // =====================================================================
    //  التشغيل
    // =====================================================================
    private void launch(String pkg) {
        hideAll();
        hideSwitcher();
        editing = false;
        draft = null;
        Intent i = ctx.getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) {
            toast("App not installed");
            renderSlots();
            return;
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            ctx.startActivity(i);
        } catch (Exception e) {
            toast("Could not launch");
        }
        close();   // يغلق الدرج تلقائياً بعد التشغيل
    }
}
