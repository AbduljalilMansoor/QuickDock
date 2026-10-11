package com.quickdock.app;

import android.app.Activity;
import android.view.View;

/**
 * ودجت قابل للوضع في شاشة العدادات (Display 2). لإضافة ودجت جديد: صنف يرث Widget، ثم أضف معرّفه إلى ALL و create() و meta().
 */
abstract class Widget {
    static final String WEATHER = "weather", MEDIA = "media", BELT = "belt", PRAYER = "prayer";
    static final String[] ALL = {WEATHER, MEDIA, BELT, PRAYER};

    /** بيانات ثابتة عن نوع الودجت (تُستخدم في المكتبة والمحاكي دون إنشاء الودجت). */
    static final class Meta {
        final String id, title, desc, emoji;
        final int defCols, minCols;
        final float designW, designH;        // dp: المقاس الذي تُرسم عليه الواجهة ثم تُكبَّر/تُصغَّر لتناسب الخلية
        final String[] preview;              // نص تجريبي للمحاكي
        final boolean settings;

        Meta(String id, String title, String desc, String emoji, int defCols, int minCols,
             float designW, float designH, String[] preview, boolean settings) {
            this.id = id; this.title = title; this.desc = desc; this.emoji = emoji;
            this.defCols = defCols; this.minCols = minCols; this.designW = designW; this.designH = designH;
            this.preview = preview; this.settings = settings;
        }
    }

    static Meta meta(String id) {
        if (WEATHER.equals(id)) return new Meta(WEATHER, "Weather", "Temperature and conditions for your city or the car's location",
                "🌤", 3, 2, 230, 96, new String[]{"28°", "Partly cloudy"}, true);
        if (MEDIA.equals(id)) return new Meta(MEDIA, "Media player", "Track name and controls for any music app",
                "🎵", 5, 3, 440, 96, new String[]{"Now playing", "Artist · Track"}, false);
        if (BELT.equals(id)) return new Meta(BELT, "Seat belts", "Driver and passenger belt status, with an alarm when moving unbuckled",
                "🪢", 2, 2, 230, 96, new String[]{"Driver  🟢", "Passenger  🔴"}, true);
        if (PRAYER.equals(id)) return new Meta(PRAYER, "Prayer times", "Next prayer, countdown and today's five prayer times",
                "🕌", 6, 4, 540, 96, new String[]{"Next: Asr", "Fajr · Dhuhr · Asr · Maghrib · Isha"}, true);
        return null;
    }

    final Activity act;
    final CarBridge car;

    Widget(Activity a, CarBridge c) { act = a; car = c; }

    abstract String id();
    abstract String title();
    abstract String description();
    abstract String icon();

    float weight() { return 1f; }

    /** يبني واجهة الودجت بمقاس التصميم (meta.designW × designH). */
    abstract View createView();

    /** يُستدعى كل ثانية على الخيط الرئيسي. */
    void refresh() { }

    boolean hasSettings() { return false; }

    /** نافذة إعدادات الودجت (المدينة، الإشارات…). */
    void openSettings() { }

    static Widget create(String id, Activity a, CarBridge c) {
        if (WEATHER.equals(id)) return new WeatherWidget(a, c);
        if (MEDIA.equals(id)) return new MediaWidget(a, c);
        if (BELT.equals(id)) return new SeatBeltWidget(a, c);
        if (PRAYER.equals(id)) return new PrayerWidget(a, c);
        return null;
    }
}
