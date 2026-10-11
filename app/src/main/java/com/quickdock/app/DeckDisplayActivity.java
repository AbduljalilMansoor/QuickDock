package com.quickdock.app;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * الشاشة التي تُعرض فعلياً على Display 2 (شاشة العدادات): تقرأ التصميم المنشور وترسم الودجتات الحيّة
 * في أماكنها، كل ودجت يُرسم بمقاس تصميمه ثم يُكبَّر/يُصغَّر ليملأ خليته.
 */
public class DeckDisplayActivity extends Activity {
    private static final List<DeckDisplayActivity> LIVE = new ArrayList<>();

    private final Handler ui = new Handler(Looper.getMainLooper());
    private FrameLayout root;
    private final List<Widget> widgets = new ArrayList<>();
    private CarBridge car;
    private HandlerThread pollThread;
    private Handler pollHandler;
    private volatile boolean polling;
    private int builtW = -1, builtH = -1;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            for (Widget w : widgets) {
                try { w.refresh(); } catch (Throwable ignored) { }
            }
            ui.postDelayed(this, 1000);
        }
    };

    /** يفتح الشاشة على العرض المطلوب. @return null عند النجاح، وإلا سبب الفشل. */
    static String launchOn(Context c, int displayId) {
        try {
            Intent i = new Intent(c, DeckDisplayActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            ActivityOptions o = ActivityOptions.makeBasic();
            o.setLaunchDisplayId(displayId);
            c.startActivity(i, o.toBundle());
            return null;
        } catch (Throwable t) {
            return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
        }
    }

    static void stopAll() {
        for (DeckDisplayActivity a : new ArrayList<>(LIVE)) a.finish();
    }

    private int dp(float v) { return Ui.dp(this, v); }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LIVE.add(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        car = new CarBridge();
        root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        setContentView(root);
        root.addOnLayoutChangeListener((v, l, t, r, bt, ol, ot, or, ob) -> {
            int w = r - l, h = bt - t;
            if (w > 0 && h > 0 && (w != builtW || h != builtH)) {
                builtW = w;
                builtH = h;
                root.post(this::build);
            }
        });
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        build();
    }

    @Override
    protected void onResume() {
        super.onResume();
        startPolling();
        ui.removeCallbacks(tick);
        ui.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(tick);
        stopPolling();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        LIVE.remove(this);
        stopPolling();
        car.release();
    }

    private void build() {
        widgets.clear();
        root.removeAllViews();
        List<DeckItem> items = DeckStore.load(this, true);
        int rows = Math.max(1, DeckStore.rows(this));
        int w = root.getWidth(), h = root.getHeight();
        if (w <= 0 || h <= 0) return;
        if (items.isEmpty()) {
            TextView t = Ui.text(this, "No widgets deployed yet — open QuickDock → Deck to design this display",
                    18, Ui.MUTE, false);
            t.setGravity(Gravity.CENTER);
            root.addView(t, new FrameLayout.LayoutParams(-1, -1));
            return;
        }
        float cw = w / (float) DeckStore.COLS, ch = h / (float) rows;
        int gap = dp(4);
        for (DeckItem it : items) {
            Widget wd = Widget.create(it.type, this, car);
            if (wd == null) continue;
            Widget.Meta m = Widget.meta(it.type);
            int tw = Math.round(it.cs * cw) - 2 * gap, th = Math.round(it.rs * ch) - 2 * gap;
            if (tw <= 0 || th <= 0) continue;

            FrameLayout holder = new FrameLayout(this);
            holder.setBackground(Ui.shape(Ui.PANEL2, dp(14), 0, 0));
            View v = wd.createView();
            int dw = dp(m.designW), dh = dp(m.designH);
            float s = Math.min(tw / (float) dw, th / (float) dh);
            v.setLayoutParams(new FrameLayout.LayoutParams(dw, dh));
            v.setPivotX(0);
            v.setPivotY(0);
            v.setScaleX(s);
            v.setScaleY(s);
            v.setTranslationX((tw - dw * s) / 2f);
            v.setTranslationY((th - dh * s) / 2f);
            holder.addView(v);

            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(tw, th);
            lp.leftMargin = Math.round(it.col * cw) + gap;
            lp.topMargin = Math.round(it.row * ch) + gap;
            root.addView(holder, lp);
            widgets.add(wd);
        }
        for (Widget wd : widgets) {
            try { wd.refresh(); } catch (Throwable ignored) { }
        }
    }

    private void startPolling() {
        if (polling) return;
        polling = true;
        pollThread = new HandlerThread("deck-poll");
        pollThread.start();
        pollHandler = new Handler(pollThread.getLooper());
        final Context app = getApplicationContext();
        pollHandler.post(new Runnable() {
            @Override public void run() {
                if (!polling) return;
                try {
                    car.connect(app);
                    car.poll();
                } catch (Throwable ignored) { }
                if (polling) pollHandler.postDelayed(this, 1000);
            }
        });
    }

    private void stopPolling() {
        polling = false;
        if (pollThread != null) { pollThread.quitSafely(); pollThread = null; }
    }
}
