package com.quickdock.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** أيقونة «Deck»: شاشة عريضة (شاشة العدادات) وبداخلها ثلاث بطاقات ودجت. */
final class DeckIcon extends View {
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();

    DeckIcon(Context c) {
        super(c);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(Ui.dp(c, 2));
        stroke.setColor(Ui.TEXT);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(Ui.ACCENT);
    }

    @Override
    protected void onDraw(Canvas c) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float w = Math.min(getWidth(), getHeight()) * 0.74f;
        float h = w * 0.58f;
        r.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2);
        c.drawRoundRect(r, w * 0.12f, w * 0.12f, stroke);
        float pad = w * 0.12f, gap = w * 0.06f;
        float bw = (w - 2 * pad - 2 * gap) / 3f;
        for (int i = 0; i < 3; i++) {
            float left = r.left + pad + i * (bw + gap);
            RectF t = new RectF(left, r.top + pad, left + bw, r.bottom - pad);
            c.drawRoundRect(t, bw * 0.2f, bw * 0.2f, fill);
        }
    }
}
