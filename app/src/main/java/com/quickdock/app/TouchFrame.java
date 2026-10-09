package com.quickdock.app;

import android.content.Context;
import android.view.MotionEvent;
import android.widget.FrameLayout;

/** إطار يخبرنا بأي لمسة تمر عبره (لإعادة ضبط مؤقت الإخفاء التلقائي) دون أن يستهلكها. */
final class TouchFrame extends FrameLayout {
    Runnable onAnyTouch;

    TouchFrame(Context c) { super(c); }

    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        if (onAnyTouch != null) onAnyTouch.run();
        return super.dispatchTouchEvent(e);
    }
}
