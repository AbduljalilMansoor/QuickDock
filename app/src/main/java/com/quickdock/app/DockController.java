package com.quickdock.app;

import android.animation.ObjectAnimator;
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
 *  • الدرج: 5 خانات مفضلة، زر «All apps»، وزر القلم للتعديل.
 *  • إخفاء تلقائي بعد 4 ثوانٍ بلا لمس، أو بالضغط خارج الدرج. يتوقف المؤقت أثناء التعديل وعرض كل التطبيقات.
 *  • التعديل: ضغط مطوّل أو القلم ← سحب وإفلات لإعادة الترتيب، ✕ للحذف، ضغط خانة فارغة للاختيار، ثم Done/Cancel.
 */
final class DockController {
    private static final int AUTO_HIDE_MS = 4000;
    private static final int SLOTS = Favorites.SLOTS;

    private final Context ctx;
    private final WindowManager wm;
    private final Handler h = new Handler(Looper.getMainLooper());

    // الحالة
    private String[] favs;
    private String[] draft;
    private boolean editing, isOpen, closing, allOpen;
    private int pickIdx = -1, overIdx = -1;
    private List<AppEntry> apps = new ArrayList<>();
    private Gest g;

    // الواجهة
    private FrameLayout edge;
    private TouchFrame root, drawer;
    private View scrim, timerFill;
    private TextView title, editBtn, allTitle, toastView, ghostLabel;
    private LinearLayout slotsBox, footView, footEdit, ghost;
    private ImageView ghostIcon;
    private FrameLayout allPage;
    private GridLayout grid;
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
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(dp(26), dp(120), Gravity.END | Gravity.CENTER_VERTICAL);
        zone.addView(tab, tlp);

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
        drawer.setTranslationX(dp(272));
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
        drawer.animate().translationX(dp(272)).setDuration(220).withEndAction(this::removeRoot).start();
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
        if (editing || allOpen) {                       // متوقف أثناء التعديل وعرض كل التطبيقات
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
        TextView b = Ui.text(ctx, label, 20, primary ? Ui.ON_ACCENT : Ui.TEXT, primary);
        b.setGravity(Gravity.CENTER);
        b.setBackground(Ui.shape(primary ? Ui.ACCENT : Ui.PANEL2, dp(14), primary ? Ui.ACCENT : Ui.LINE, dp(1)));
        b.setMinHeight(dp(56));
        return b;
    }

    private void buildRoot() {
        root = new TouchFrame(ctx);
        root.setClipChildren(false);

        // خلفية معتمة: الضغط عليها يغلق الدرج
        scrim = new View(ctx);
        scrim.setBackgroundColor(0x47000000);
        scrim.setOnClickListener(v -> {
            if (allOpen) return;
            if (editing) toast("Tap Done or Cancel to leave edit mode");
            else close();
        });
        root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));

        // الدرج
        drawer = new TouchFrame(ctx);
        drawer.setBackgroundColor(Ui.PANEL);
        drawer.setClipChildren(false);
        drawer.setClickable(true);                      // لا تمرّر اللمس للخلفية المعتمة
        drawer.onAnyTouch = () -> { if (isOpen && !closing) armTimer(); };
        root.addView(drawer, new FrameLayout.LayoutParams(dp(272), -1, Gravity.END));

        View border = new View(ctx);
        border.setBackgroundColor(Ui.LINE);
        drawer.addView(border, new FrameLayout.LayoutParams(dp(1), -1, Gravity.START));

        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setClipChildren(false);
        col.setPadding(dp(18), dp(18), dp(18), dp(16));
        drawer.addView(col, new FrameLayout.LayoutParams(-1, -1));

        // الرأس: العنوان + القلم
        LinearLayout head = new LinearLayout(ctx);
        head.setGravity(Gravity.CENTER_VERTICAL);
        title = Ui.text(ctx, "Quick launch", 22, Ui.TEXT, true);
        head.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        editBtn = Ui.text(ctx, "✎", 22, Ui.TEXT, false);
        editBtn.setGravity(Gravity.CENTER);
        editBtn.setBackground(Ui.shape(Ui.PANEL2, dp(12), Ui.LINE, dp(1)));
        editBtn.setOnClickListener(v -> enterEdit());
        head.addView(editBtn, new LinearLayout.LayoutParams(dp(48), dp(48)));
        col.addView(head, new LinearLayout.LayoutParams(-1, dp(48)));

        // شريط المؤقت
        FrameLayout timer = new FrameLayout(ctx);
        timer.setBackground(Ui.shape(Ui.LINE, dp(2), 0, 0));
        timerFill = new View(ctx);
        timerFill.setBackgroundColor(Ui.ACCENT);
        timerFill.setPivotX(0f);
        timer.addView(timerFill, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, dp(3));
        tlp.topMargin = dp(12);
        col.addView(timer, tlp);

        // الخانات
        slotsBox = new LinearLayout(ctx);
        slotsBox.setOrientation(LinearLayout.VERTICAL);
        slotsBox.setClipChildren(false);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, 0, 1f);
        slp.topMargin = dp(12);
        slp.bottomMargin = dp(12);
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

        // شبح السحب
        ghost = new LinearLayout(ctx);
        ghost.setGravity(Gravity.CENTER_VERTICAL);
        ghost.setPadding(dp(14), 0, dp(14), 0);
        ghost.setBackground(Ui.shape(Ui.ACCENT, dp(16), 0, 0));
        ghost.setElevation(dp(10));
        ghostIcon = new ImageView(ctx);
        ghost.addView(ghostIcon, new LinearLayout.LayoutParams(dp(40), dp(40)));
        ghostLabel = Ui.text(ctx, "", 21, Ui.ON_ACCENT, true);
        ghostLabel.setSingleLine(true);
        ghostLabel.setPadding(dp(14), 0, 0, 0);
        ghost.addView(ghostLabel, new LinearLayout.LayoutParams(0, -2, 1f));
        ghost.setVisibility(View.GONE);
        root.addView(ghost, new FrameLayout.LayoutParams(dp(236), dp(80)));

        // رسالة قصيرة
        toastView = Ui.text(ctx, "", 19, Ui.TEXT, false);
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
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 0, 1f);
            if (i < SLOTS - 1) lp.bottomMargin = dp(10);
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
        footView.setVisibility(editing ? View.GONE : View.VISIBLE);
        footEdit.setVisibility(editing ? View.VISIBLE : View.GONE);
    }

    private void stopWiggle() {
        for (ObjectAnimator w : wiggles) w.cancel();
        wiggles.clear();
    }

    private void styleSlot(View slot, boolean empty, boolean over) {
        if (empty) {
            slot.setBackground(over ? Ui.shape(Color.TRANSPARENT, dp(16), Ui.ACCENT, dp(2))
                    : Ui.dashed(dp(16), Ui.DASH, dp(2), dp(8), dp(5)));
        } else {
            slot.setBackground(Ui.shape(Ui.PANEL2, dp(16), over ? Ui.ACCENT : Ui.LINE, dp(over ? 2 : 1)));
        }
    }

    private FrameLayout makeSlot(final int idx, AppEntry a) {
        boolean empty = a == null;
        FrameLayout slot = new FrameLayout(ctx);
        slot.setClipChildren(false);
        styleSlot(slot, empty, false);

        LinearLayout row = new LinearLayout(ctx);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), 0, dp(14), 0);
        TextView label;
        if (empty) {
            TextView plus = Ui.text(ctx, "+", 30, Ui.MUTE, false);
            plus.setGravity(Gravity.CENTER);
            plus.setBackground(Ui.dashed(dp(14), Ui.DASH, dp(2), dp(6), dp(4)));
            row.addView(plus, new LinearLayout.LayoutParams(dp(52), dp(52)));
            label = Ui.text(ctx, "Add app", 21, Ui.MUTE, false);
        } else {
            ImageView iv = new ImageView(ctx);
            iv.setImageDrawable(a.icon);
            iv.setPadding(dp(6), dp(6), dp(6), dp(6));
            iv.setBackground(Ui.shape(Ui.ICON_BG, dp(14), 0, 0));
            row.addView(iv, new LinearLayout.LayoutParams(dp(52), dp(52)));
            label = Ui.text(ctx, a.label, 21, Ui.TEXT, false);
        }
        label.setSingleLine(true);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        label.setPadding(dp(14), 0, 0, 0);
        row.addView(label, new LinearLayout.LayoutParams(0, -2, 1f));
        slot.addView(row, new FrameLayout.LayoutParams(-1, -1));

        if (!empty && editing) {   // شارة الحذف ✕
            TextView x = Ui.text(ctx, "✕", 16, 0xFFFFFFFF, true);
            x.setGravity(Gravity.CENTER);
            GradientDrawable xb = Ui.shape(Ui.DANGER, dp(16), Ui.PANEL, dp(2));
            x.setBackground(xb);
            x.setOnClickListener(v -> { draft[idx] = ""; renderSlots(); });
            FrameLayout.LayoutParams xl = new FrameLayout.LayoutParams(dp(32), dp(32), Gravity.TOP | Gravity.START);
            xl.setMargins(-dp(8), -dp(8), 0, 0);
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
        ghost.setX(rx - rl[0] - dp(118));
        ghost.setY(ry - rl[1] - dp(40));
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
    private void buildAllPage() {
        allPage = new FrameLayout(ctx);
        allPage.setBackgroundColor(0xF50A0D0F);
        allPage.setClickable(true);
        allPage.setVisibility(View.GONE);

        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(60), dp(36), dp(60), dp(24));

        LinearLayout top = new LinearLayout(ctx);
        top.setGravity(Gravity.CENTER_VERTICAL);
        allTitle = Ui.text(ctx, "All apps", 34, Ui.TEXT, true);
        top.addView(allTitle, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView close = button("Close", false);
        close.setPadding(dp(22), dp(14), dp(22), dp(14));
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

        allPage.addView(col, new FrameLayout.LayoutParams(-1, -1));
        root.addView(allPage, new FrameLayout.LayoutParams(-1, -1));
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
            c.addView(iv, new LinearLayout.LayoutParams(dp(64), dp(64)));
            TextView t = Ui.text(ctx, a.label, 18, Ui.TEXT, false);
            t.setGravity(Gravity.CENTER);
            t.setMaxLines(1);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            t.setPadding(dp(8), dp(8), dp(8), 0);
            c.addView(t, new LinearLayout.LayoutParams(-1, -2));
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = cell;
            lp.height = dp(150);
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
    //  التشغيل
    // =====================================================================
    private void launch(String pkg) {
        hideAll();
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
