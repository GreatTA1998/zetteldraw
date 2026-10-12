package com.zetteldraw.penpoc;

import android.content.Context;
import android.graphics.Rect;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;

/**
 * Finger-scrolls the page stack. Sits above {@link PageInkView}: stylus
 * gestures go to the ink surface unless they start on a page button.
 */
final class PageScroller extends ScrollView {
    interface Listener {
        void onScrolled(int scrollY);
    }

    /** Long-press on empty paper while the lasso clipboard is full. */
    interface PasteLongPressListener {
        void onPasteLongPress(float surfaceX, float surfaceY);
    }

    private final Rect hitRect = new Rect();
    private final GestureDetector longPressDetector;
    private View stylusTarget;
    private Listener listener;
    private PasteLongPressListener pasteLongPressListener;
    private boolean forwardingStylus;
    /** Raw coords at ACTION_DOWN; used to ignore long-press on chrome. */
    private float downRawX;
    private float downRawY;

    PageScroller(Context context) {
        super(context);
        setFillViewport(true);
        setVerticalScrollBarEnabled(false);
        setOverScrollMode(OVER_SCROLL_NEVER);
        longPressDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public void onLongPress(MotionEvent e) {
                if (pasteLongPressListener == null) {
                    return;
                }
                if (hitsClickable(PageScroller.this, downRawX, downRawY)) {
                    return;
                }
                pasteLongPressListener.onPasteLongPress(e.getX(), e.getY());
            }
        });
    }

    void setStylusTarget(View target) {
        stylusTarget = target;
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    void setPasteLongPressListener(PasteLongPressListener listener) {
        pasteLongPressListener = listener;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (stylusTarget instanceof PageInkView && ((PageInkView) stylusTarget).capturesTouches()) {
            forwardingStylus = false;
            return stylusTarget.dispatchTouchEvent(ev);
        }
        if (stylusTarget != null && PageInkView.isStylus(ev)) {
            if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
                forwardingStylus = !hitsClickable(this, ev.getRawX(), ev.getRawY());
            }
            if (forwardingStylus) {
                return stylusTarget.dispatchTouchEvent(ev);
            }
        }
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
            downRawX = ev.getRawX();
            downRawY = ev.getRawY();
        }
        // Finger long-press for Paste when clipboard is full; does not block scroll.
        if (!PageInkView.isStylus(ev)) {
            longPressDetector.onTouchEvent(ev);
        }
        return super.dispatchTouchEvent(ev);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        return !PageInkView.isStylus(ev) && super.onInterceptTouchEvent(ev);
    }

    @Override
    protected void onScrollChanged(int l, int t, int oldl, int oldt) {
        super.onScrollChanged(l, t, oldl, oldt);
        if (listener != null) {
            listener.onScrolled(t);
        }
    }

    private boolean hitsClickable(View view, float rawX, float rawY) {
        if (!view.isShown()) {
            return false;
        }
        if (view != this && view.isClickable()) {
            return view.getGlobalVisibleRect(hitRect) && hitRect.contains((int) rawX, (int) rawY);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = group.getChildCount() - 1; i >= 0; i--) {
                if (hitsClickable(group.getChildAt(i), rawX, rawY)) {
                    return true;
                }
            }
        }
        return false;
    }
}
