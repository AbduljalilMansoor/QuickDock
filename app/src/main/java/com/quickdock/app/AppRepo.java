package com.quickdock.app;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** قراءة التطبيقات المثبّتة. */
final class AppRepo {
    private AppRepo() { }

    interface Callback { void done(List<AppEntry> apps); }

    /** تطبيق واحد بحزمته، أو null إن لم يعد مثبّتاً أو لا يملك واجهة تشغيل. */
    static AppEntry resolve(Context c, String pkg) {
        if (pkg == null || pkg.isEmpty()) return null;
        PackageManager pm = c.getPackageManager();
        try {
            if (pm.getLaunchIntentForPackage(pkg) == null) return null;
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            AppEntry e = new AppEntry();
            e.pkg = pkg;
            e.label = String.valueOf(pm.getApplicationLabel(ai));
            e.icon = pm.getApplicationIcon(ai);
            return e;
        } catch (PackageManager.NameNotFoundException ex) {
            return null;
        }
    }

    /** كل التطبيقات القابلة للتشغيل مرتبة بالاسم (في خيط خلفي). */
    static void loadAll(final Context c, final Callback cb) {
        final PackageManager pm = c.getPackageManager();
        new Thread(() -> {
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> list = pm.queryIntentActivities(q, 0);
            List<AppEntry> out = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (ResolveInfo ri : list) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(c.getPackageName()) || !seen.add(pkg)) continue;
                AppEntry a = new AppEntry();
                a.pkg = pkg;
                a.label = String.valueOf(ri.loadLabel(pm));
                a.icon = ri.loadIcon(pm);
                out.add(a);
            }
            Collections.sort(out, (x, y) -> x.label.compareToIgnoreCase(y.label));
            cb.done(out);
        }).start();
    }
}
