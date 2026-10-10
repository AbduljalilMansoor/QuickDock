package com.quickdock.app;

import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.os.Process;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * قائمة «التطبيقات العاملة/الأخيرة» لمبدّل التطبيقات.
 * تطبيق عادي لا يستطيع قراءة قائمة مهام النظام، لذلك نستخدم إحصاءات الاستخدام (UsageStats):
 * كل تطبيق صار في الواجهة خلال آخر 12 ساعة ولم نغلقه نحن منذ ذلك الحين. تحتاج صلاحية «الوصول لبيانات الاستخدام»:
 *   appops set com.quickdock.app GET_USAGE_STATS allow
 */
final class RecentApps {
    private RecentApps() { }

    static final class Item {
        AppEntry app;
        long lastUsed;
        boolean top;   // التطبيق الظاهر حالياً
    }

    private static final long WINDOW_MS = 12L * 3600_000L;
    private static final int MAX_ITEMS = 12;
    private static final String CLOSED_PREFIX = "closed_";

    static boolean hasAccess(Context c) {
        AppOpsManager ops = (AppOpsManager) c.getSystemService(Context.APP_OPS_SERVICE);
        if (ops == null) return false;
        int mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.getPackageName());
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    /** يُستدعى من خيط خلفي. QuickDock والشاشات الرئيسية وواجهة النظام مستثناة. */
    static List<Item> load(Context c) {
        List<Item> out = new ArrayList<>();
        UsageStatsManager usm = (UsageStatsManager) c.getSystemService(Context.USAGE_STATS_SERVICE);
        if (usm == null) return out;
        long end = System.currentTimeMillis();
        UsageEvents ev = usm.queryEvents(end - WINDOW_MS, end);
        if (ev == null) return out;

        Map<String, Long> last = new LinkedHashMap<>();
        String top = null;
        UsageEvents.Event e = new UsageEvents.Event();
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e);
            int t = e.getEventType();
            String p = e.getPackageName();
            if (t == 1) {                       // ACTIVITY_RESUMED (تحوّل للواجهة)
                last.put(p, e.getTimeStamp());
                top = p;
            } else if (t == 2 && p.equals(top)) {   // ACTIVITY_PAUSED
                top = null;
            }
        }

        Set<String> skip = new HashSet<>();
        skip.add(c.getPackageName());
        skip.add("com.android.systemui");
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        for (ResolveInfo ri : c.getPackageManager().queryIntentActivities(home, 0)) skip.add(ri.activityInfo.packageName);

        for (Map.Entry<String, Long> en : last.entrySet()) {
            String pkg = en.getKey();
            if (skip.contains(pkg)) continue;
            long closedAt = Favorites.prefs(c).getLong(CLOSED_PREFIX + pkg, 0L);
            if (en.getValue() <= closedAt) continue;   // أغلقناه بعد آخر استخدام
            AppEntry a = AppRepo.resolve(c, pkg);
            if (a == null) continue;                   // ليس تطبيقاً قابلاً للتشغيل
            Item it = new Item();
            it.app = a;
            it.lastUsed = en.getValue();
            it.top = pkg.equals(top);
            out.add(it);
        }
        Collections.sort(out, (x, y) -> Long.compare(y.lastUsed, x.lastUsed));
        return out.size() > MAX_ITEMS ? new ArrayList<>(out.subList(0, MAX_ITEMS)) : out;
    }

    static void markClosed(Context c, String pkg) {
        Favorites.prefs(c).edit().putLong(CLOSED_PREFIX + pkg, System.currentTimeMillis()).apply();
    }

    static String ago(long t) {
        long m = Math.max(0, (System.currentTimeMillis() - t) / 60000);
        if (m < 1) return "Just now";
        if (m < 60) return m + " min ago";
        return (m / 60) + " h ago";
    }
}
