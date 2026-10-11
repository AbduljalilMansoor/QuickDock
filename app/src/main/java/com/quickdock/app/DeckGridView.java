package com.quickdock.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * محاكي شاشة العدادات (Display 2): شبكة من 24 عموداً × عدة صفوف.
 *  • اضغط ودجتاً لتحديده، واسحبه لنقله، واسحب المقبض ◢ لتغيير حجمه، و ✕ لحذفه، و ⚙ لإعداداته.
 *  • كل التغييرات تلتصق بالشبكة، ولا يُسمح بتداخل ودجتين.
 *  • المكتبة تستخدم setDrop / commitDrop لإسقاط ودجت جديد.
 */
final class DeckGridView extends View {
    interface Host {
        void onChanged();
        void onSettings(DeckItem item);
        void onMessage(String msg);
    }

    private static final int COLS = DeckStore.COLS;
    private static final int M_NONE = 0, M_MOVE = 1, M_RESIZE = 2;

    private final List<DeckItem> items = new ArrayList<>();
    private int rows = 3;
    private DeckItem selected;
    private Host host;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final RectF r2 = new RectF();
    private float padX, padTop, padBottom, cw, ch;

    // اللمس
    private int mode = M_NONE;
    private DeckItem active;
    private float downX, downY;
    private boolean moved;
    private int oCol, oRow, oCs, oRs;
    private int cCol, cRow, cCs, cRs;
    private boolean cValid;

    // معاينة الإسقاط من المكتبة
    private boolean dropOn, dropValid;
    private int dCol, dRow, dCs, dRs;

    DeckGridView(Context c) {
        super(c);
        txt.setTypeface(Typeface.DEFAULT);
        setLayerType(LAYER_TYPE_SOFTWARE, null);   // لخطوط متقطعة صحيحة
    }

    void setHost(Host h) { host = h; }

    List<DeckItem> getItems() { return items; }

    void setItems(List<DeckItem> list) {
        items.clear();
        items.addAll(list);
        selected = null;
        invalidate();
    }

    int getRows() { return rows; }

    /** يغيّر عدد الصفوف ويقصّ الودجتات التي تخرج عن الشبكة. */
    void setRows(int n) {
        rows = Math.max(1, n);
        for (int i = items.size() - 1; i >= 0; i--) {
            DeckItem it = items.get(i);
            if (it.row >= rows) items.remove(i);
            else it.rs = Math.min(it.rs, rows - it.row);
        }
        if (selected != null && !items.contains(selected)) selected = null;
        invalidate();
    }

    private int dp(float v) { return Ui.dp(getContext(), v); }

    // ---------------------------------------------------------------- الهندسة
    private void geometry() {
        padX = dp(10);
        padTop = dp(24);
        padBottom = dp(10);
        cw = (getWidth() - 2 * padX) / COLS;
        ch = (getHeight() - padTop - padBottom) / rows;
    }

    private void cell(int col, int row, int cs, int rs, RectF out) {
        out.set(padX + col * cw, padTop + row * ch, padX + (col + cs) * cw, padTop + (row + rs) * ch);
    }

    private void tile(DeckItem it, RectF out) {
        cell(it.col, it.row, it.cs, it.rs, out);
        out.inset(dp(2), dp(2));
    }

    private boolean overlaps(int col, int row, int cs, int rs, DeckItem ignore) {
        for (DeckItem o : items) {
            if (o == ignore) continue;
            if (col < o.col + o.cs && o.col < col + cs && row < o.row + o.rs && o.row < row + rs) return true;
        }
        return false;
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    private DeckItem hit(float x, float y) {
        for (int i = items.size() - 1; i >= 0; i--) {
            tile(items.get(i), r);
            if (r.contains(x, y)) return items.get(i);
        }
        return null;
    }

    // ---------------------------------------------------------------- الرسم
    @Override
    protected void onDraw(Canvas c) {
        geometry();
        float w = getWidth(), h = getHeight();

        // الإطار والخلفية
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(0xFF0A0E10);
        r.set(dp(1), dp(1), w - dp(1), h - dp(1));
        c.drawRoundRect(r, dp(14), dp(14), fill);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(dp(1.5f));
        line.setColor(0xFF3B4A52);
        line.setPathEffect(null);
        c.drawRoundRect(r, dp(14), dp(14), line);

        // أرقام الأعمدة
        txt.setTextAlign(Paint.Align.CENTER);
        txt.setTextSize(dp(9));
        txt.setColor(Ui.MUTE);
        txt.setFakeBoldText(false);
        for (int i = 0; i < COLS; i++) {
            c.drawText(String.format(Locale.US, "%02d", i + 1), padX + (i + 0.5f) * cw, padTop - dp(8), txt);
        }

        // خطوط الشبكة
        line.setStrokeWidth(dp(1));
        line.setColor(0x33FFFFFF);
        line.setPathEffect(new DashPathEffect(new float[]{dp(3), dp(4)}, 0));
        for (int i = 0; i <= COLS; i++) c.drawLine(padX + i * cw, padTop, padX + i * cw, padTop + rows * ch, line);
        for (int j = 0; j <= rows; j++) c.drawLine(padX, padTop + j * ch, padX + COLS * cw, padTop + j * ch, line);
        line.setPathEffect(null);

        // الخلايا الفارغة: «+»
        txt.setTextSize(dp(13));
        txt.setColor(0x44FFFFFF);
        for (int j = 0; j < rows; j++) {
            for (int i = 0; i < COLS; i++) {
                if (!overlaps(i, j, 1, 1, null)) {
                    c.drawText("+", padX + (i + 0.5f) * cw, padTop + (j + 0.5f) * ch + dp(4), txt);
                }
            }
        }

        // الودجتات
        for (DeckItem it : items) {
            if (it == active && moved) continue;   // يُرسم كمعاينة
            tile(it, r);
            drawTile(c, r, it, it == selected ? Ui.ACCENT : Ui.LINE, it == selected ? dp(2) : dp(1), 255);
        }

        // معاينة النقل/تغيير الحجم
        if (active != null && moved) {
            cell(cCol, cRow, cCs, cRs, r);
            r.inset(dp(2), dp(2));
            drawTile(c, r, active, cValid ? Ui.ACCENT : Ui.DANGER, dp(2), 230);
        }

        // معاينة الإسقاط من المكتبة
        if (dropOn) {
            cell(dCol, dRow, dCs, dRs, r);
            r.inset(dp(2), dp(2));
            fill.setColor(dropValid ? 0x33E0A23A : 0x33D9534A);
            c.drawRoundRect(r, dp(8), dp(8), fill);
            line.setStrokeWidth(dp(2));
            line.setColor(dropValid ? Ui.ACCENT : Ui.DANGER);
            line.setPathEffect(new DashPathEffect(new float[]{dp(6), dp(4)}, 0));
            c.drawRoundRect(r, dp(8), dp(8), line);
            line.setPathEffect(null);
        }

        // مقابض الودجت المحدد
        if (selected != null && !(active == selected && moved)) {
            tile(selected, r);
            badge(c, r.right - dp(2), r.top + dp(2), Ui.DANGER, 0xFFFFFFFF, "✕");
            if (Widget.meta(selected.type).settings) badge(c, r.right - dp(28), r.top + dp(2), Ui.ACCENT, Ui.ON_ACCENT, "⚙");
            fill.setColor(Ui.ACCENT);
            r2.set(r.right - dp(16), r.bottom - dp(16), r.right - dp(3), r.bottom - dp(3));
            c.drawRoundRect(r2, dp(3), dp(3), fill);
            txt.setColor(Ui.ON_ACCENT);
            txt.setTextSize(dp(10));
            c.drawText("◢", r2.centerX(), r2.centerY() + dp(4), txt);
        }
    }

    private void badge(Canvas c, float cx, float cy, int bg, int fg, String s) {
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(bg);
        c.drawCircle(cx, cy, dp(11), fill);
        txt.setColor(fg);
        txt.setTextSize(dp(11));
        txt.setTextAlign(Paint.Align.CENTER);
        txt.setFakeBoldText(true);
        c.drawText(s, cx, cy + dp(4), txt);
        txt.setFakeBoldText(false);
    }

    private void drawTile(Canvas c, RectF rect, DeckItem it, int strokeColor, float strokeW, int alpha) {
        Widget.Meta m = Widget.meta(it.type);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(Ui.PANEL2);
        fill.setAlpha(alpha);
        c.drawRoundRect(rect, dp(9), dp(9), fill);
        fill.setAlpha(255);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(strokeW);
        line.setColor(strokeColor);
        c.drawRoundRect(rect, dp(9), dp(9), line);

        c.save();
        c.clipRect(rect);
        txt.setTextAlign(Paint.Align.LEFT);
        txt.setTextSize(dp(9));
        txt.setColor(Ui.MUTE);
        c.drawText(m.title.toUpperCase(Locale.US), rect.left + dp(8), rect.top + dp(14), txt);

        float emojiSize = Math.min(rect.height() * 0.36f, dp(30));
        txt.setTextSize(emojiSize);
        txt.setColor(Ui.TEXT);
        if (rect.width() >= dp(104)) {
            c.drawText(m.emoji, rect.left + dp(8), rect.centerY() + emojiSize * 0.45f + dp(4), txt);
            float tx = rect.left + dp(8) + emojiSize * 1.3f;
            txt.setTextSize(dp(12));
            txt.setFakeBoldText(true);
            txt.setColor(Ui.TEXT);
            c.drawText(m.preview[0], tx, rect.centerY() + dp(2), txt);
            txt.setFakeBoldText(false);
            txt.setTextSize(dp(10));
            txt.setColor(Ui.MUTE);
            c.drawText(m.preview[1], tx, rect.centerY() + dp(16), txt);
        } else {
            txt.setTextAlign(Paint.Align.CENTER);
            c.drawText(m.emoji, rect.centerX(), rect.centerY() + emojiSize * 0.4f + dp(4), txt);
        }
        c.restore();
    }

    // ---------------------------------------------------------------- اللمس
    private boolean near(float x, float y, float cx, float cy, float rad) {
        return Math.hypot(x - cx, y - cy) <= rad;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        geometry();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                downX = x; downY = y; moved = false; mode = M_NONE; active = null;
                if (selected != null) {
                    tile(selected, r);
                    if (near(x, y, r.right - dp(2), r.top + dp(2), dp(18))) { removeSelected(); return true; }
                    if (Widget.meta(selected.type).settings && near(x, y, r.right - dp(28), r.top + dp(2), dp(18))) {
                        if (host != null) host.onSettings(selected);
                        return true;
                    }
                    if (x >= r.right - dp(30) && x <= r.right + dp(6) && y >= r.bottom - dp(30) && y <= r.bottom + dp(6)) {
                        begin(selected, M_RESIZE);
                        return true;
                    }
                }
                DeckItem hit = hit(x, y);
                selected = hit;
                if (hit != null) begin(hit, M_MOVE);
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE:
                if (mode != M_NONE) {
                    if (!moved && Math.hypot(x - downX, y - downY) > dp(6)) moved = true;
                    if (moved) updateCandidate(x, y);
                    invalidate();
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (mode != M_NONE && moved) {
                    if (cValid) {
                        active.col = cCol; active.row = cRow; active.cs = cCs; active.rs = cRs;
                        if (host != null) host.onChanged();
                    } else if (host != null) {
                        host.onMessage("That space is used by another widget");
                    }
                }
                mode = M_NONE; moved = false; active = null;
                invalidate();
                return true;
            case MotionEvent.ACTION_CANCEL:
                mode = M_NONE; moved = false; active = null;
                invalidate();
                return true;
            default:
                return true;
        }
    }

    private void begin(DeckItem it, int m) {
        active = it;
        mode = m;
        oCol = it.col; oRow = it.row; oCs = it.cs; oRs = it.rs;
        cCol = oCol; cRow = oRow; cCs = oCs; cRs = oRs; cValid = true;
    }

    private void updateCandidate(float x, float y) {
        int dc = Math.round((x - downX) / cw), dr = Math.round((y - downY) / ch);
        if (mode == M_MOVE) {
            cCs = oCs; cRs = oRs;
            cCol = clamp(oCol + dc, 0, COLS - oCs);
            cRow = clamp(oRow + dr, 0, rows - oRs);
        } else {
            int min = Widget.meta(active.type).minCols;
            cCol = oCol; cRow = oRow;
            cCs = clamp(oCs + dc, Math.min(min, COLS - oCol), COLS - oCol);
            cRs = clamp(oRs + dr, 1, rows - oRow);
        }
        cValid = !overlaps(cCol, cRow, cCs, cRs, active);
    }

    private void removeSelected() {
        items.remove(selected);
        selected = null;
        invalidate();
        if (host != null) host.onChanged();
    }

    // ---------------------------------------------------------------- الإسقاط من المكتبة
    /** @return true إن كان الإصبع فوق الشبكة. */
    boolean setDrop(float rawX, float rawY, String type) {
        int[] loc = new int[2];
        getLocationOnScreen(loc);
        float x = rawX - loc[0], y = rawY - loc[1];
        geometry();
        if (x < 0 || y < padTop - dp(12) || x > getWidth() || y > getHeight()) {
            dropOn = false;
            invalidate();
            return false;
        }
        Widget.Meta m = Widget.meta(type);
        dCs = Math.min(m.defCols, COLS);
        dRs = rows;
        dCol = clamp((int) Math.floor((x - padX) / cw) - dCs / 2, 0, COLS - dCs);
        dRow = 0;
        dropValid = !overlaps(dCol, dRow, dCs, dRs, null);
        dropOn = true;
        invalidate();
        return true;
    }

    void clearDrop() {
        dropOn = false;
        invalidate();
    }

    /** يضيف الودجت عند آخر معاينة؛ إن كان المكان مشغولاً يبحث عن أقرب مكان فارغ. */
    boolean commitDrop(String type) {
        if (!dropOn) return false;
        Widget.Meta m = Widget.meta(type);
        int col = dCol, row = dRow, cs = dCs, rs = dRs;
        if (!dropValid) {
            int[] best = nearestFree(m, dCol, dRow);
            if (best == null) { dropOn = false; invalidate(); return false; }
            col = best[0]; row = best[1]; cs = best[2]; rs = best[3];
        }
        DeckItem it = new DeckItem(type, col, row, cs, rs);
        items.add(it);
        selected = it;
        dropOn = false;
        invalidate();
        if (host != null) host.onChanged();
        return true;
    }

    private int[] nearestFree(Widget.Meta m, int wantCol, int wantRow) {
        for (int cs = Math.min(m.defCols, COLS); cs >= m.minCols; cs--) {
            for (int rs = rows; rs >= 1; rs--) {
                int[] best = null;
                int bestDist = Integer.MAX_VALUE;
                for (int row = 0; row <= rows - rs; row++) {
                    for (int col = 0; col <= COLS - cs; col++) {
                        if (overlaps(col, row, cs, rs, null)) continue;
                        int d = Math.abs(col - wantCol) + Math.abs(row - wantRow);
                        if (d < bestDist) { bestDist = d; best = new int[]{col, row, cs, rs}; }
                    }
                }
                if (best != null) return best;
            }
        }
        return null;
    }
}
