package jp.yaman.comicexplorer;

import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;

/** Keeps page-button taps native, but hands drags and multi-touch to the reader. */
final class ReaderPageCanvas extends FrameLayout {
    private final View reader;
    private final int touchSlop;
    private MotionEvent buttonDown;
    private View gestureTarget;
    private boolean forwarding;

    ReaderPageCanvas(Context context, View reader) {
        super(context);
        this.reader = reader;
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        // A second finger over a button must stay with the first finger's reader.
        setMotionEventSplittingEnabled(false);
    }

    void bindPageButton(View button) {
        button.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                buttonDown = MotionEvent.obtain(event);
                buttonDown.offsetLocation(view.getLeft() - getScrollX(), view.getTop() - getScrollY());
                gestureTarget = reader;
            }
            return false;
        });
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) clearGesture();
        if (buttonDown != null && !forwarding && gestureTarget != null
                && (action == MotionEvent.ACTION_POINTER_DOWN
                || (action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_UP)
                && Math.hypot(event.getX() - buttonDown.getX(), event.getY() - buttonDown.getY()) > touchSlop)) {
            MotionEvent cancel = MotionEvent.obtain(event);
            cancel.setAction(MotionEvent.ACTION_CANCEL);
            super.dispatchTouchEvent(cancel);
            cancel.recycle();
            forwarding = true;
            dispatchToReader(buttonDown);
            // A coalesced stream can end beyond slop without a preceding MOVE.
            if (action == MotionEvent.ACTION_UP) {
                MotionEvent move = MotionEvent.obtain(event);
                move.setAction(MotionEvent.ACTION_MOVE);
                dispatchToReader(move);
                move.recycle();
            }
        }
        boolean handled;
        if (forwarding) {
            dispatchToReader(event);
            handled = true;
        } else {
            handled = super.dispatchTouchEvent(event);
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) clearGesture();
        return handled;
    }

    private void dispatchToReader(MotionEvent event) {
        MotionEvent translated = MotionEvent.obtain(event);
        translated.offsetLocation(getScrollX() - gestureTarget.getLeft(), getScrollY() - gestureTarget.getTop());
        gestureTarget.dispatchTouchEvent(translated);
        translated.recycle();
    }

    private void clearGesture() {
        if (buttonDown != null) buttonDown.recycle();
        buttonDown = null;
        gestureTarget = null;
        forwarding = false;
    }

    @Override protected void onDetachedFromWindow() {
        if (forwarding && buttonDown != null) {
            MotionEvent cancel = MotionEvent.obtain(buttonDown);
            cancel.setAction(MotionEvent.ACTION_CANCEL);
            dispatchToReader(cancel);
            cancel.recycle();
        }
        clearGesture();
        super.onDetachedFromWindow();
    }
}
