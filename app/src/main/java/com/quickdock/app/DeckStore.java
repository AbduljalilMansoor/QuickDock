package com.quickdock.app;

import android.content.Context;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.view.Display;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** حفظ تصميم شاشة العدادات: «مسودة» (أثناء التحرير) و«منشور» (المعروض فعلاً على Display 2). */
final class DeckStore {
    private DeckStore() { }

    static final int COLS = 24;
    static final int DEFAULT_DISPLAY = 2;
    private static final String K_DRAFT = "deck_draft", K_LIVE = "deck_live", K_DISPLAY = "deck_display", K_ROWS = "deck_rows";

    static List<DeckItem> load(Context c, boolean live) {
        List<DeckItem> out = new ArrayList<>();
        String key = live ? K_LIVE : K_DRAFT;
        String s = Favorites.prefs(c).getString(key, null);
        if (s == null && !live) s = Favorites.prefs(c).getString(K_LIVE, "[]");   // لا مسودة بعد: ابدأ من المنشور
        if (s == null) return out;
        try {
            JSONArray a = new JSONArray(s);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                if (Widget.meta(o.getString("t")) == null) continue;
                out.add(new DeckItem(o.getString("t"), o.getInt("c"), o.getInt("r"), o.getInt("cs"), o.getInt("rs")));
            }
        } catch (Exception ignored) { }
        return out;
    }

    static String toJson(List<DeckItem> items) {
        JSONArray a = new JSONArray();
        try {
            for (DeckItem it : items) {
                JSONObject o = new JSONObject();
                o.put("t", it.type).put("c", it.col).put("r", it.row).put("cs", it.cs).put("rs", it.rs);
                a.put(o);
            }
        } catch (Exception ignored) { }
        return a.toString();
    }

    static void save(Context c, boolean live, List<DeckItem> items) {
        Favorites.prefs(c).edit().putString(live ? K_LIVE : K_DRAFT, toJson(items)).apply();
    }

    static List<DeckItem> copy(List<DeckItem> src) {
        List<DeckItem> l = new ArrayList<>();
        for (DeckItem i : src) l.add(i.copy());
        return l;
    }

    static int display(Context c) { return Favorites.prefs(c).getInt(K_DISPLAY, DEFAULT_DISPLAY); }

    static void setDisplay(Context c, int id) { Favorites.prefs(c).edit().putInt(K_DISPLAY, id).apply(); }

    static int rows(Context c) { return Favorites.prefs(c).getInt(K_ROWS, 3); }

    static void setRows(Context c, int rows) { Favorites.prefs(c).edit().putInt(K_ROWS, rows).apply(); }

    /** مقاس الشاشة الحقيقي، أو null إن لم تُرَ للتطبيق. */
    static Point displaySize(Context c, int id) {
        DisplayManager dm = (DisplayManager) c.getSystemService(Context.DISPLAY_SERVICE);
        Display d = dm == null ? null : dm.getDisplay(id);
        if (d == null) return null;
        Point p = new Point();
        d.getRealSize(p);
        return p.x > 0 && p.y > 0 ? p : null;
    }

    /** عدد صفوف الشبكة بحيث تقترب الخلايا من المربع (بين 2 و4). */
    static int rowsFor(int w, int h) {
        return Math.max(2, Math.min(4, Math.round(COLS * (float) h / w)));
    }
}
