package com.quickdock.app;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.widget.TextView;

/** الألوان والأدوات المشتركة (مطابقة لتصميم الدرج: خلفية داكنة ولون مميّز كهرماني). */
final class Ui {
    private Ui() { }

    static final int BG = 0xFF0E1113, PANEL = 0xFF181D20, PANEL2 = 0xFF222A2E, LINE = 0xFF2F393E;
    static final int TEXT = 0xFFECE7DC, MUTE = 0xFF8D968F, ACCENT = 0xFFE0A23A, DANGER = 0xFFD9534A;
    static final int ICON_BG = 0xFF2D373C, DASH = 0xFF3A464C, ON_ACCENT = 0xFF1A1405;

    // أسماء مستعارة يستخدمها كود الودجات المنقول من Car Home
    static final int CARD2 = PANEL2, MUTED = MUTE;

    static GradientDrawable round(int fill, float radiusPx) { return shape(fill, Math.round(radiusPx), 0, 0); }

    static int dp(Context c, float v) { return Math.round(v * c.getResources().getDisplayMetrics().density); }

    static TextView text(Context c, CharSequence s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    static GradientDrawable shape(int fill, int radiusPx, int strokeColor, int strokePx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(radiusPx);
        if (strokePx > 0) g.setStroke(strokePx, strokeColor);
        return g;
    }

    static GradientDrawable dashed(int radiusPx, int strokeColor, int strokePx, float dashPx, float gapPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(Color.TRANSPARENT);
        g.setCornerRadius(radiusPx);
        g.setStroke(strokePx, strokeColor, dashPx, gapPx);
        return g;
    }
}
