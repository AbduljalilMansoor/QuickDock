package com.quickdock.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** عند تشغيل السيارة: أعد اللسان على الحافة إن كان التشغيل التلقائي مفعّلاً. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        try {
            if (Favorites.prefs(ctx).getBoolean(MainActivity.KEY_AUTOSTART, true)) DockService.start(ctx);
        } catch (Exception ignored) { }
    }
}
