package com.quickdock.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** أيقونة «مبدّل التطبيقات/الصفحات»: بطاقتان متراكبتان كزر النظرة العامة في أندرويد. */
final class SwitcherIcon extends View {
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();

    SwitcherIcon(Context c) {
        super(c);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(Ui.dp(c, 2));
        stroke.setColor(Ui.TEXT);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(Ui.PANEL2);
    }

    @Override
    protected void onDraw(Canvas c) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float u = Math.min(getWidth(), getHeight()) * 0.28f;
        float rad = u * 0.3f;
        r.set(cx - u * 0.55f, cy - u, cx + u, cy + u * 0.55f);     // البطاقة الخلفية
        c.drawRoundRect(r, rad, rad, stroke);
        r.set(cx - u, cy - u * 0.55f, cx + u * 0.55f, cy + u);     // البطاقة الأمامية
        c.drawRoundRect(r, rad, rad, fill);
        c.drawRoundRect(r, rad, rad, stroke);
    }
}
