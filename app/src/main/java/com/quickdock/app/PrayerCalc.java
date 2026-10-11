package com.quickdock.app;

import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

/**
 * حساب مواقيت الصلاة فلكياً (بدون إنترنت): معادلات موقع الشمس القياسية.
 * الطرق (زوايا الشمس تحت الأفق):
 *  • JAFARI — التقويم الجعفري، معهد ليفا للأبحاث (قم): الفجر 16°، المغرب 4°، العشاء 14°.
 *  • TEHRAN — معهد الجيوفيزياء، جامعة طهران: الفجر 17.7°، المغرب 4.5°، العشاء 14°.
 *  • MWL — رابطة العالم الإسلامي: الفجر 18°، العشاء 17°، المغرب عند الغروب.
 *  • UQU — مؤسسة أم القرى: الفجر 18.5°، العشاء = المغرب + 90 دقيقة، المغرب عند الغروب.
 * العصر: ظل المثل. لمن يختلف تقويمه بدقائق: تعديل يدوي لكل صلاة (حتى +15 دقيقة) في إعدادات الودجت.
 */
final class PrayerCalc {
    private PrayerCalc() { }

    static final int JAFARI = 0, TEHRAN = 1, MWL = 2, UQU = 3;
    static final String[] METHOD_NAMES = {
            "Jafari — Leva Research Institute, Qum (معهد ليفا للأبحاث - قم)",
            "Institute of Geophysics, University of Tehran (معهد الجيوفيزياء - جامعة طهران)",
            "Muslim World League (رابطة العالم الإسلامي)",
            "Umm al-Qura (مؤسسة أم القرى)"
    };
    static final String[] METHOD_SHORT = {"Leva/Qum", "Tehran", "MWL", "Umm al-Qura"};

    /** الأوقات بالساعات المحلية (0..24)، أو NaN إن تعذّر الحساب (خطوط العرض العالية). */
    static final class Times {
        double fajr, sunrise, dhuhr, asr, maghrib, isha;
    }

    private static final class Params {
        double fajr, ishaAngle, maghribAngle;
        boolean maghribByAngle, ishaByMinutes;
        double ishaMinutes;
    }

    private static Params params(int method) {
        Params p = new Params();
        switch (method) {
            case TEHRAN:
                p.fajr = 17.7; p.maghribByAngle = true; p.maghribAngle = 4.5; p.ishaAngle = 14;
                break;
            case MWL:
                p.fajr = 18; p.ishaAngle = 17;
                break;
            case UQU:
                p.fajr = 18.5; p.ishaByMinutes = true; p.ishaMinutes = 90;
                break;
            default:   // JAFARI (ليفا - قم)
                p.fajr = 16; p.maghribByAngle = true; p.maghribAngle = 4; p.ishaAngle = 14;
                break;
        }
        return p;
    }

    // ---------------- أدوات رياضية ----------------
    private static double dtr(double d) { return d * Math.PI / 180.0; }
    private static double rtd(double r) { return r * 180.0 / Math.PI; }
    private static double fixAngle(double a) { return a - 360.0 * Math.floor(a / 360.0); }
    private static double fixHour(double h) { return h - 24.0 * Math.floor(h / 24.0); }

    private static double julian(int year, int month, int day) {
        if (month <= 2) { year -= 1; month += 12; }
        double a = Math.floor(year / 100.0);
        double b = 2 - a + Math.floor(a / 4.0);
        return Math.floor(365.25 * (year + 4716)) + Math.floor(30.6001 * (month + 1)) + day + b - 1524.5;
    }

    /** {الميل بالدرجات, معادلة الزمن بالساعات} */
    private static double[] sunPosition(double jd) {
        double d = jd - 2451545.0;
        double g = fixAngle(357.529 + 0.98560028 * d);
        double q = fixAngle(280.459 + 0.98564736 * d);
        double l = fixAngle(q + 1.915 * Math.sin(dtr(g)) + 0.020 * Math.sin(dtr(2 * g)));
        double e = 23.439 - 0.00000036 * d;
        double ra = fixHour(rtd(Math.atan2(Math.cos(dtr(e)) * Math.sin(dtr(l)), Math.cos(dtr(l)))) / 15.0);
        double eqt = q / 15.0 - ra;
        double decl = rtd(Math.asin(Math.sin(dtr(e)) * Math.sin(dtr(l))));
        return new double[]{decl, eqt};
    }

    private static double midDay(double jd, double t) {
        return fixHour(12 - sunPosition(jd + t)[1]);
    }

    private static double sunAngleTime(double jd, double lat, double angle, double t, boolean ccw) {
        double decl = sunPosition(jd + t)[0];
        double noon = midDay(jd, t);
        double x = (-Math.sin(dtr(angle)) - Math.sin(dtr(decl)) * Math.sin(dtr(lat)))
                / (Math.cos(dtr(decl)) * Math.cos(dtr(lat)));
        double a = rtd(Math.acos(x)) / 15.0;   // NaN إن كانت |x| > 1
        return noon + (ccw ? -a : a);
    }

    private static double asrTime(double jd, double lat, double factor, double t) {
        double decl = sunPosition(jd + t)[0];
        double angle = -rtd(Math.atan(1.0 / (factor + Math.tan(dtr(Math.abs(lat - decl))))));
        return sunAngleTime(jd, lat, angle, t, false);
    }

    /** مواقيت اليوم (year/month/day بحسب المنطقة الزمنية tz). */
    static Times compute(int method, double lat, double lng, TimeZone tz, int year, int month, int day) {
        Params p = params(method);
        Calendar noon = Calendar.getInstance(tz);
        noon.clear();
        noon.set(year, month - 1, day, 12, 0, 0);
        double tzHours = tz.getOffset(noon.getTimeInMillis()) / 3600000.0;
        double jd = julian(year, month, day) - lng / (15.0 * 24.0);
        double riseSet = 0.833;

        double[] f = {5, 6, 12, 13, 18, 18, 18};
        for (int i = 0; i < f.length; i++) f[i] /= 24.0;
        double fajr = 0, sunrise = 0, dhuhr = 0, asr = 0, sunset = 0, maghrib = 0, isha = 0;
        for (int pass = 0; pass < 2; pass++) {
            fajr = sunAngleTime(jd, lat, p.fajr, f[0], true);
            sunrise = sunAngleTime(jd, lat, riseSet, f[1], true);
            dhuhr = midDay(jd, f[2]);
            asr = asrTime(jd, lat, 1, f[3]);
            sunset = sunAngleTime(jd, lat, riseSet, f[4], false);
            maghrib = p.maghribByAngle ? sunAngleTime(jd, lat, p.maghribAngle, f[5], false) : sunset;
            isha = p.ishaByMinutes ? maghrib + p.ishaMinutes / 60.0 : sunAngleTime(jd, lat, p.ishaAngle, f[6], false);
            double[] v = {fajr, sunrise, dhuhr, asr, sunset, maghrib, isha};
            for (int i = 0; i < v.length; i++) f[i] = v[i] / 24.0;
        }
        double shift = tzHours - lng / 15.0;
        Times t = new Times();
        t.fajr = fajr + shift;
        t.sunrise = sunrise + shift;
        t.dhuhr = dhuhr + shift;
        t.asr = asr + shift;
        t.maghrib = maghrib + shift;
        t.isha = isha + shift;
        return t;
    }

    /** تنسيق الساعة (مقرّبة لأقرب دقيقة). */
    static String format(double h, boolean use24) {
        if (Double.isNaN(h)) return "--:--";
        h = fixHour(h + 0.5 / 60.0);
        int hh = (int) Math.floor(h);
        int mm = (int) Math.floor((h - hh) * 60.0);
        if (use24) return String.format(Locale.US, "%02d:%02d", hh, mm);
        int h12 = (hh + 11) % 12 + 1;
        return String.format(Locale.US, "%d:%02d %s", h12, mm, hh < 12 ? "AM" : "PM");
    }
}
