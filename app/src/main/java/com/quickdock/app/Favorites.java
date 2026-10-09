package com.quickdock.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Arrays;

/** المفضلة: خمس خانات ثابتة تُحفظ محلياً (اسم الحزمة، أو نص فارغ للخانة الفارغة). */
final class Favorites {
    private Favorites() { }

    static final int SLOTS = 5;
    static final String PREFS = "quickdock";
    private static final String KEY = "favorites";

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    static String[] load(Context c) {
        String[] f = new String[SLOTS];
        Arrays.fill(f, "");
        String[] p = prefs(c).getString(KEY, "").split(",", -1);
        for (int i = 0; i < SLOTS && i < p.length; i++) f[i] = p[i];
        return f;
    }

    static void save(Context c, String[] f) {
        prefs(c).edit().putString(KEY, String.join(",", f)).apply();
    }
}
