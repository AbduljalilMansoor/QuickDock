package com.quickdock.app;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Calendar;
import java.util.TimeZone;

/**
 * ودجت مواقيت الصلاة: الصلاة القادمة والعدّ التنازلي وأوقات الصلوات الخمس.
 * الإعدادات: المدينة (بحث عبر الإنترنت أو قائمة جاهزة أو موقع السيارة) وطريقة الحساب
 * (تقويم الهادي، رابطة العالم الإسلامي، مؤسسة أم القرى). الحساب يتم على الجهاز بدون إنترنت.
 */
final class PrayerWidget extends Widget {
    private static final String K_CITY = "p_city", K_LAT = "p_lat", K_LON = "p_lon", K_TZ = "p_tz";
    private static final String K_METHOD = "p_method", K_24H = "p_24h", K_ADJ = "p_adj";
    private static final String[] EN_NAMES = {"Fajr", "Dhuhr", "Asr", "Maghrib", "Isha"};
    private static final String[] AR_NAMES = {"الفجر", "الظهر", "العصر", "المغرب", "العشاء"};

    /** {الاسم, خط العرض, خط الطول, المنطقة الزمنية} */
    private static final String[][] PRESETS = {
            {"Makkah", "21.4225", "39.8262", "Asia/Riyadh"}, {"Madinah", "24.4686", "39.6142", "Asia/Riyadh"},
            {"Riyadh", "24.7136", "46.6753", "Asia/Riyadh"}, {"Jeddah", "21.4858", "39.1925", "Asia/Riyadh"},
            {"Dammam", "26.4207", "50.0888", "Asia/Riyadh"}, {"Manama", "26.2285", "50.5860", "Asia/Bahrain"},
            {"Kuwait City", "29.3759", "47.9774", "Asia/Kuwait"}, {"Doha", "25.2854", "51.5310", "Asia/Qatar"},
            {"Abu Dhabi", "24.4539", "54.3773", "Asia/Dubai"}, {"Dubai", "25.2048", "55.2708", "Asia/Dubai"},
            {"Muscat", "23.5880", "58.3829", "Asia/Muscat"}, {"Baghdad", "33.3152", "44.3661", "Asia/Baghdad"},
            {"Najaf", "31.9961", "44.3314", "Asia/Baghdad"}, {"Karbala", "32.6160", "44.0249", "Asia/Baghdad"},
            {"Qom", "34.6399", "50.8759", "Asia/Tehran"}, {"Mashhad", "36.2605", "59.6168", "Asia/Tehran"},
            {"Tehran", "35.6892", "51.3890", "Asia/Tehran"}, {"Cairo", "30.0444", "31.2357", "Africa/Cairo"},
            {"Amman", "31.9454", "35.9284", "Asia/Amman"}, {"Istanbul", "41.0082", "28.9784", "Europe/Istanbul"},
            {"London", "51.5074", "-0.1278", "Europe/London"}
    };

    private final SharedPreferences prefs;
    private TextView vCity, vNext, vCount;
    private final TextView[] vName = new TextView[5], vTime = new TextView[5];
    private final LinearLayout[] cols = new LinearLayout[5];
    private String cacheKey = "";
    private PrayerCalc.Times today, tomorrow;
    private int lastNext = -2;

    PrayerWidget(android.app.Activity a, CarBridge c) {
        super(a, c);
        prefs = a.getSharedPreferences(Favorites.PREFS, Context.MODE_PRIVATE);
    }

    @Override String id() { return PRAYER; }
    @Override String title() { return "Prayer times"; }
    @Override String description() { return "Next prayer, countdown and today's five prayer times"; }
    @Override String icon() { return "🕌"; }
    @Override boolean hasSettings() { return true; }
    @Override void openSettings() { settingsMenu(); }

    // ---------------------------------------------------------------- الواجهة
    @Override
    View createView() {
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(Ui.dp(act, 12), Ui.dp(act, 6), Ui.dp(act, 10), Ui.dp(act, 6));

        LinearLayout info = new LinearLayout(act);
        info.setOrientation(LinearLayout.VERTICAL);
        vCity = Ui.text(act, "Set city (⚙)", 12, Ui.MUTE, false);
        vNext = Ui.text(act, "—", 17, Ui.ACCENT, true);
        vCount = Ui.text(act, "", 13, Ui.TEXT, false);
        for (TextView t : new TextView[]{vCity, vNext, vCount}) {
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
            info.addView(t);
        }
        box.addView(info, new LinearLayout.LayoutParams(0, -2, 1.5f));

        for (int i = 0; i < 5; i++) {
            LinearLayout col = new LinearLayout(act);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER);
            col.setPadding(Ui.dp(act, 2), Ui.dp(act, 6), Ui.dp(act, 2), Ui.dp(act, 6));
            vName[i] = Ui.text(act, AR_NAMES[i], 14, Ui.MUTE, false);
            vTime[i] = Ui.text(act, "--:--", 17, Ui.TEXT, true);
            vName[i].setGravity(Gravity.CENTER);
            vTime[i].setGravity(Gravity.CENTER);
            vTime[i].setSingleLine(true);
            col.addView(vName[i]);
            col.addView(vTime[i]);
            cols[i] = col;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
            lp.setMargins(Ui.dp(act, 2), 0, Ui.dp(act, 2), 0);
            box.addView(col, lp);
        }
        box.setOnClickListener(v -> settingsMenu());
        return box;
    }

    // ---------------------------------------------------------------- الموقع والحساب
    private boolean use24() { return prefs.getBoolean(K_24H, false); }

    private int method() { return prefs.getInt(K_METHOD, PrayerCalc.JAFARI); }

    /** {خط العرض, خط الطول} من المدينة المحفوظة، وإلا آخر موقع معروف للسيارة؛ أو null. */
    private double[] location() {
        String lat = prefs.getString(K_LAT, null), lon = prefs.getString(K_LON, null);
        if (lat != null && lon != null) {
            try { return new double[]{Double.parseDouble(lat), Double.parseDouble(lon)}; } catch (Exception ignored) { }
        }
        if (act.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null;
        LocationManager lm = (LocationManager) act.getSystemService(Context.LOCATION_SERVICE);
        for (String p : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER}) {
            try {
                Location l = lm.getLastKnownLocation(p);
                if (l != null) return new double[]{l.getLatitude(), l.getLongitude()};
            } catch (Exception ignored) { }
        }
        return null;
    }

    private TimeZone zone() {
        String id = prefs.getString(K_TZ, "");
        return id.isEmpty() ? TimeZone.getDefault() : TimeZone.getTimeZone(id);
    }

    @Override
    void refresh() {
        if (vNext == null) return;
        double[] loc = location();
        if (loc == null) {
            vCity.setText("Set city (⚙ settings)");
            vNext.setText("—");
            vCount.setText("");
            for (int i = 0; i < 5; i++) vTime[i].setText("--:--");
            return;
        }
        TimeZone tz = zone();
        Calendar now = Calendar.getInstance(tz);
        String key = loc[0] + "|" + loc[1] + "|" + tz.getID() + "|" + method() + "|" + use24() + "|"
                + now.get(Calendar.YEAR) + "-" + now.get(Calendar.DAY_OF_YEAR);
        if (!key.equals(cacheKey)) {
            cacheKey = key;
            today = PrayerCalc.compute(method(), loc[0], loc[1], tz, now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1, now.get(Calendar.DAY_OF_MONTH));
            Calendar tm = (Calendar) now.clone();
            tm.add(Calendar.DAY_OF_MONTH, 1);
            tomorrow = PrayerCalc.compute(method(), loc[0], loc[1], tz, tm.get(Calendar.YEAR), tm.get(Calendar.MONTH) + 1, tm.get(Calendar.DAY_OF_MONTH));
            lastNext = -2;
        }
        double[] t = {today.fajr + adj(0) / 60.0, today.dhuhr + adj(1) / 60.0, today.asr + adj(2) / 60.0,
                today.maghrib + adj(3) / 60.0, today.isha + adj(4) / 60.0};
        for (int i = 0; i < 5; i++) vTime[i].setText(PrayerCalc.format(t[i], use24()));
        String city = prefs.getString(K_CITY, "");
        vCity.setText((city.isEmpty() ? "Car location" : city) + " · " + shortMethod());

        double nowH = now.get(Calendar.HOUR_OF_DAY) + now.get(Calendar.MINUTE) / 60.0 + now.get(Calendar.SECOND) / 3600.0;
        int next = -1;
        double target = Double.NaN;
        for (int i = 0; i < 5; i++) {
            if (!Double.isNaN(t[i]) && t[i] > nowH) { next = i; target = t[i]; break; }
        }
        if (next < 0) { next = 0; target = tomorrow.fajr + adj(0) / 60.0 + 24; }   // بعد العشاء: فجر الغد
        if (Double.isNaN(target)) { vNext.setText("—"); vCount.setText(""); return; }
        vNext.setText(AR_NAMES[next]);
        int mins = (int) Math.max(0, Math.ceil((target - nowH) * 60.0));
        vCount.setText("in " + (mins >= 60 ? (mins / 60) + "h " + (mins % 60) + "m" : mins + "m"));
        if (next != lastNext) {   // تمييز الصلاة القادمة
            lastNext = next;
            for (int i = 0; i < 5; i++) {
                boolean on = i == next;
                cols[i].setBackground(on ? Ui.shape(Ui.ACCENT, Ui.dp(act, 10), 0, 0) : null);
                vName[i].setTextColor(on ? Ui.ON_ACCENT : Ui.MUTE);
                vTime[i].setTextColor(on ? Ui.ON_ACCENT : Ui.TEXT);
            }
        }
    }

    private String shortMethod() {
        int m = Math.max(0, Math.min(PrayerCalc.METHOD_SHORT.length - 1, method()));
        return PrayerCalc.METHOD_SHORT[m];
    }

    private int adj(int i) { return prefs.getInt(K_ADJ + i, 0); }

    // ---------------------------------------------------------------- الإعدادات
    private void settingsMenu() {
        String city = prefs.getString(K_CITY, "");
        String[] items = {
                "City:  " + (city.isEmpty() ? "car location / not set" : city),
                "Calculation method:  " + PrayerCalc.METHOD_NAMES[method()],
                "Adjust times:  add 0–15 min to each prayer",
                "Time format:  " + (use24() ? "24-hour" : "12-hour")
        };
        new AlertDialog.Builder(act).setTitle("Prayer times settings").setItems(items, (d, which) -> {
            if (which == 0) cityMenu();
            else if (which == 1) methodMenu();
            else if (which == 2) adjustMenu();
            else { prefs.edit().putBoolean(K_24H, !use24()).apply(); settingsMenu(); }
        }).setNegativeButton("Close", null).show();
    }

    /** تعديل يدوي لكل صلاة بإضافة من 0 إلى 15 دقيقة (للتقاويم التي تختلف قليلاً عن الحساب). */
    private void adjustMenu() {
        String[] items = new String[5];
        for (int i = 0; i < 5; i++) items[i] = AR_NAMES[i] + "  (" + EN_NAMES[i] + ")      " + (adj(i) == 0 ? "no change" : "+" + adj(i) + " min");
        new AlertDialog.Builder(act).setTitle("Adjust prayer times")
                .setItems(items, (d, which) -> minutesMenu(which))
                .setNeutralButton("Reset all", (d, w) -> {
                    SharedPreferences.Editor ed = prefs.edit();
                    for (int i = 0; i < 5; i++) ed.remove(K_ADJ + i);
                    ed.apply();
                    adjustMenu();
                })
                .setNegativeButton("Back", (d, w) -> settingsMenu()).show();
    }

    private void minutesMenu(final int prayer) {
        String[] opts = new String[16];
        for (int m = 0; m <= 15; m++) opts[m] = m == 0 ? "No change" : "+" + m + " min";
        new AlertDialog.Builder(act).setTitle(AR_NAMES[prayer] + " — add minutes")
                .setSingleChoiceItems(opts, adj(prayer), (d, which) -> {
                    prefs.edit().putInt(K_ADJ + prayer, which).apply();
                    d.dismiss();
                    adjustMenu();
                })
                .setNegativeButton("Back", (d, w) -> adjustMenu()).show();
    }

    private void methodMenu() {
        new AlertDialog.Builder(act).setTitle("Calculation method")
                .setSingleChoiceItems(PrayerCalc.METHOD_NAMES, method(), (d, which) -> {
                    prefs.edit().putInt(K_METHOD, which).apply();
                    cacheKey = "";
                    d.dismiss();
                    settingsMenu();
                })
                .setNegativeButton("Back", (d, w) -> settingsMenu()).show();
    }

    private void cityMenu() {
        String[] items = new String[PRESETS.length + 2];
        items[0] = "🔍  Search for a city (needs internet)…";
        items[1] = "📍  Use the car's location";
        for (int i = 0; i < PRESETS.length; i++) items[i + 2] = PRESETS[i][0];
        new AlertDialog.Builder(act).setTitle("Select city").setItems(items, (d, which) -> {
            if (which == 0) searchCity();
            else if (which == 1) {
                prefs.edit().remove(K_CITY).remove(K_LAT).remove(K_LON).remove(K_TZ).apply();
                cacheKey = "";
                settingsMenu();
            } else {
                String[] p = PRESETS[which - 2];
                saveCity(p[0], p[1], p[2], p[3]);
                settingsMenu();
            }
        }).setNegativeButton("Back", (d, w) -> settingsMenu()).show();
    }

    private void saveCity(String name, String lat, String lon, String tz) {
        prefs.edit().putString(K_CITY, name).putString(K_LAT, lat).putString(K_LON, lon).putString(K_TZ, tz).apply();
        cacheKey = "";
    }

    private void searchCity() {
        final EditText et = new EditText(act);
        et.setSingleLine(true);
        et.setHint("City name");
        new AlertDialog.Builder(act).setTitle("Search for a city").setView(et)
                .setPositiveButton("Search", (d, w) -> geocode(et.getText().toString().trim()))
                .setNegativeButton("Back", (d, w) -> cityMenu()).show();
    }

    private void geocode(final String name) {
        if (name.isEmpty()) return;
        new Thread(() -> {
            try {
                String json = http("https://geocoding-api.open-meteo.com/v1/search?count=1&language=en&format=json&name="
                        + URLEncoder.encode(name, "UTF-8"));
                JSONArray res = new JSONObject(json).optJSONArray("results");
                if (res == null || res.length() == 0) { toast("City not found"); return; }
                JSONObject r = res.getJSONObject(0);
                final String label = r.optString("name", name);
                saveCity(label, String.valueOf(r.getDouble("latitude")), String.valueOf(r.getDouble("longitude")),
                        r.optString("timezone", TimeZone.getDefault().getID()));
                act.runOnUiThread(() -> { toast("City set: " + label); settingsMenu(); });
            } catch (Exception e) {
                toast("Search failed (check internet)");
            }
        }).start();
    }

    private void toast(final String s) {
        act.runOnUiThread(() -> Toast.makeText(act, s, Toast.LENGTH_SHORT).show());
    }

    private static String http(String u) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(10000);
        try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] b = new byte[4096];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
            return out.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }
}
