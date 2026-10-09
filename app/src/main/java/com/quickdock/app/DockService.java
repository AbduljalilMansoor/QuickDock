package com.quickdock.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.provider.Settings;

/** خدمة أمامية تُبقي اللسان على الحافة اليمنى فوق كل التطبيقات، وتفتح الدرج عند الطلب. */
public class DockService extends Service {
    static final String ACTION_OPEN = "com.quickdock.app.OPEN", ACTION_EDIT = "com.quickdock.app.EDIT";
    private static final String CH = "quickdock";
    static volatile boolean running;

    private DockController ctl;

    static void start(Context c) {
        if (!Settings.canDrawOverlays(c)) return;
        c.startForegroundService(new Intent(c, DockService.class));
    }

    static void stop(Context c) { c.stopService(new Intent(c, DockService.class)); }

    static void send(Context c, String action) {
        if (!Settings.canDrawOverlays(c)) return;
        c.startForegroundService(new Intent(c, DockService.class).setAction(action));
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CH, "QuickDock", NotificationManager.IMPORTANCE_MIN));
        Notification n = new Notification.Builder(this, CH)
                .setSmallIcon(android.R.drawable.ic_menu_more)
                .setContentTitle("QuickDock is active").build();
        startForeground(51, n);

        if (!Settings.canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY; }
        if (ctl == null) {
            ctl = new DockController(this);
            ctl.showEdge();
            running = true;
        }
        String a = intent == null ? null : intent.getAction();
        if (ACTION_OPEN.equals(a)) ctl.open(false);
        else if (ACTION_EDIT.equals(a)) ctl.open(true);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        running = false;
        if (ctl != null) ctl.destroy();
        ctl = null;
    }
}
