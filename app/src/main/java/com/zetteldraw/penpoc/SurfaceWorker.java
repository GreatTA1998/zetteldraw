package com.zetteldraw.penpoc;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import com.onyx.android.sdk.api.device.epd.EpdController;
import com.onyx.android.sdk.api.device.epd.UpdateMode;
import com.onyx.android.sdk.pen.RawInputCallback;
import com.onyx.android.sdk.pen.TouchHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * Every Onyx pen call and every surface paint, on one background thread, so
 * the main thread never blocks on the e-ink system. The main thread sets the
 * desired pen state and queues frames; this worker applies the newest of
 * each in order (pen off before a full repaint), retries calls that throw,
 * and reports when the pen is really off and when a frame is up.
 */
final class SurfaceWorker {
    private static final String TAG = "zd-stall";
    static final long SLOW_CALL_MS = 700;
    private static final long RETRY_MIN_MS = 250;
    private static final long RETRY_MAX_MS = 4_000;

    interface Host {
        /** Main thread: a frame was painted (or skipped because the surface is gone). */
        void onFramePainted(long frameId, boolean full);
    }

    /**
     * The Onyx calls that change how live ink looks. Production uses {@link TouchHelper};
     * tests set {@link #rawPenForTest} so the order of those calls can be asserted.
     */
    interface RawPen {
        void open(Rect limit, List<Rect> excludes);

        void close();

        void setLimit(Rect limit, List<Rect> excludes);

        void setHandwritingPenState(int state);

        void setStyle(Style style);

        void setRawDrawingEnabled(boolean enabled);

        void setRawDrawingRenderEnabled(boolean enabled);

        void enableSideBtnErase(boolean enabled);
    }

    /** How live ink looks; applied as a whole whenever it changes or the pen comes on. */
    static final class Style {
        final int strokeStyle;
        final float width;
        final boolean brush;
        final boolean render;

        Style(int strokeStyle, float width, boolean brush, boolean render) {
            this.strokeStyle = strokeStyle;
            this.width = width;
            this.brush = brush;
            this.render = render;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Style)) {
                return false;
            }
            Style s = (Style) o;
            return strokeStyle == s.strokeStyle && width == s.width && brush == s.brush && render == s.render;
        }

        @Override
        public int hashCode() {
            return Objects.hash(strokeStyle, width, brush, render);
        }
    }

    /** One picture of the visible pages; immutable once queued. */
    static final class Frame {
        final long id;
        final boolean full;
        /** Dirty area, or null for the whole surface. */
        final Rect area;
        final UpdateMode mode;
        final List<Bitmap> bitmaps;
        final float[] lefts;
        final float[] tops;
        final RectF box;
        final Paint boxPaint;

        Frame(long id, boolean full, Rect area, UpdateMode mode, List<Bitmap> bitmaps, float[] lefts, float[] tops,
              RectF box, Paint boxPaint) {
            this.id = id;
            this.full = full;
            this.area = area;
            this.mode = mode;
            this.bitmaps = bitmaps;
            this.lefts = lefts;
            this.tops = tops;
            this.box = box;
            this.boxPaint = boxPaint;
        }

        /** {@code newer} on top of this one, still unpainted: the newest picture over both dirty areas. */
        Frame mergedWith(Frame newer) {
            Rect merged;
            if (area == null || newer.area == null) {
                merged = null;
            } else {
                merged = new Rect(area);
                merged.union(newer.area);
            }
            return new Frame(newer.id, full || newer.full, merged, newer.mode != null ? newer.mode : mode,
                    newer.bitmaps, newer.lefts, newer.tops, newer.box, newer.boxPaint);
        }
    }

    private final SurfaceView surfaceView;
    private final RawInputCallback callback;
    private final Host host;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Executor executor;
    private final HandlerThread thread;

    // Desired state, set by the main thread; guarded by this.
    private boolean wantOpen;
    private boolean wantEnabled;
    private Rect wantLimit = new Rect();
    private List<Rect> wantExcludes = new ArrayList<>();
    private Style wantStyle;
    private int wantPenState = Integer.MIN_VALUE;
    private Frame pendingFrame;
    private final ArrayList<Runnable> whenOff = new ArrayList<>();
    private boolean scheduled;
    private boolean dirty;
    private boolean closed;
    private long retryDelay = RETRY_MIN_MS;

    // Applied state, worker thread only.
    private RawPen device;
    private boolean enabled;
    private Rect limit;
    private List<Rect> excludes;
    private Style style;
    private int penState = Integer.MIN_VALUE;

    /** Tests: every new pen uses this instead of TouchHelper. */
    static volatile RawPen rawPenForTest;

    private volatile String busyOp;
    private volatile long busySince;

    SurfaceWorker(SurfaceView surfaceView, RawInputCallback callback, Host host) {
        this.surfaceView = surfaceView;
        this.callback = callback;
        this.host = host;
        if (UiExecutors.surfaceForTest != null) {
            thread = null;
            executor = UiExecutors.surfaceForTest;
        } else if (UiExecutors.surfaceThreads) {
            thread = new HandlerThread("zd-surface");
            thread.start();
            Handler handler = new Handler(thread.getLooper());
            executor = handler::post;
        } else {
            thread = null;
            executor = Runnable::run;
        }
        MainThreadWatchdog.watch(this);
    }

    // ---- main thread ----

    /** The pen as the main thread wants it now; cheap, never blocks. */
    synchronized void setPen(boolean open, boolean on, Rect limitRect, List<Rect> exclude, Style inkStyle,
                             int handwritingPenState) {
        wantOpen = open && !closed;
        wantEnabled = wantOpen && on;
        wantLimit = new Rect(limitRect);
        wantExcludes = new ArrayList<>(exclude);
        wantStyle = inkStyle;
        wantPenState = handwritingPenState;
        kickLocked();
    }

    /** Queues a picture; an unpainted earlier one is folded into it. */
    synchronized void paint(Frame frame) {
        pendingFrame = pendingFrame == null ? frame : pendingFrame.mergedWith(frame);
        kickLocked();
    }

    /**
     * Runs {@code action} on the main thread once raw drawing is actually off.
     * The Onyx SDK posts pen callbacks to the main queue too, so every stroke
     * read before the pen went off is handled before {@code action}.
     */
    synchronized void whenPenOff(Runnable action) {
        whenOff.add(action);
        kickLocked();
    }

    synchronized void close() {
        closed = true;
        wantOpen = false;
        wantEnabled = false;
        pendingFrame = null;
        kickLocked();
        MainThreadWatchdog.unwatch(this);
    }

    /** Debug watchdog: what the worker is stuck in, and since when (0 when idle). */
    String busyOp() {
        return busyOp;
    }

    long busySince() {
        return busySince;
    }

    Thread thread() {
        return thread;
    }

    private void kickLocked() {
        dirty = true;
        if (!scheduled) {
            scheduled = true;
            executor.execute(this::drain);
        }
    }

    // ---- worker thread ----

    private void drain() {
        while (true) {
            boolean open;
            boolean on;
            Rect lim;
            List<Rect> exc;
            Style st;
            int pen;
            Frame frame;
            synchronized (this) {
                if (!dirty) {
                    scheduled = false;
                    if (closed && thread != null) {
                        thread.quitSafely();
                    }
                    return;
                }
                dirty = false;
                open = wantOpen;
                on = wantEnabled;
                lim = wantLimit;
                exc = wantExcludes;
                st = wantStyle;
                pen = wantPenState;
                frame = pendingFrame;
                pendingFrame = null;
                if (frame != null && frame.full) {
                    on = false;
                }
            }
            if (frame != null && device == null) {
                // No pen yet means raw drawing is off: paint first, so the first picture
                // of the notes never waits for the Onyx setup below.
                paintNow(frame);
                Frame done = frame;
                main.post(() -> host.onFramePainted(done.id, done.full));
                frame = null;
            }
            try {
                applyPen(open, on, lim, exc, st, pen);
                retryDelay = RETRY_MIN_MS;
            } catch (RuntimeException | LinkageError e) {
                Log.w(TAG, "Onyx pen call failed; retrying in " + retryDelay + " ms", e);
                LaunchLog.once("onyx-failed", "Onyx pen call failed (retried with backoff): " + e);
                scheduleRetry();
            }
            ArrayList<Runnable> offActions = null;
            synchronized (this) {
                if (!enabled && !whenOff.isEmpty()) {
                    offActions = new ArrayList<>(whenOff);
                    whenOff.clear();
                }
                // A newer tool arrived while this pass ran. Apply it before painting,
                // so a slow frame cannot keep the firmware on the previous style.
                if (dirty && frame != null) {
                    pendingFrame = pendingFrame == null ? frame : frame.mergedWith(pendingFrame);
                    frame = null;
                }
            }
            if (offActions != null) {
                for (Runnable action : offActions) {
                    main.post(action);
                }
            }
            if (frame != null) {
                paintNow(frame);
                Frame done = frame;
                main.post(() -> host.onFramePainted(done.id, done.full));
            }
        }
    }

    private void scheduleRetry() {
        long delay = retryDelay;
        retryDelay = Math.min(RETRY_MAX_MS, retryDelay * 2);
        main.postDelayed(() -> {
            synchronized (this) {
                kickLocked();
            }
        }, delay);
    }

    private void applyPen(boolean open, boolean on, Rect lim, List<Rect> exc, Style st, int pen) {
        if (!open) {
            if (device != null) {
                RawPen closing = device;
                device = null;
                enabled = false;
                style = null;
                limit = null;
                excludes = null;
                closing.close();
            }
            return;
        }
        ensureDevice(lim, exc);
        RawPen h = device;
        if (!lim.equals(limit) || !exc.equals(excludes)) {
            h.setLimit(lim, exc);
            limit = new Rect(lim);
            excludes = new ArrayList<>(exc);
        }
        if (pen != penState) {
            h.setHandwritingPenState(pen);
            penState = pen;
        }
        // setRawDrawingEnabled(true) restores the default pen: brush on, render on,
        // and the stroke style the firmware last had (the lasso dash survives a
        // disable). The snapshot style is written after every enable and every
        // disable, so it is never left behind and an enable is never last.
        if (on && !enabled) {
            h.setRawDrawingEnabled(true);
            // Enabling raw drawing resets the side-button eraser channel.
            h.enableSideBtnErase(true);
            enabled = true;
            style = null;
            LaunchLog.once("pen-applied", "pen enabled on the e-ink system");
        }
        if (!on && enabled) {
            disableRaw(h);
        }
        // While drawing is off, keep render off so this write cannot resume ink.
        // The stroke style still changes, which is what the next stroke will use.
        Style write = st;
        if (write != null && !on && write.render) {
            write = new Style(write.strokeStyle, write.width, write.brush, false);
        }
        if (write != null && !write.equals(style)) {
            writeStyle(h, write);
        }
    }

    private void ensureDevice(Rect lim, List<Rect> exc) {
        if (device != null) {
            return;
        }
        RawPen test = rawPenForTest;
        if (test != null) {
            test.open(lim, exc);
            device = test;
        } else {
            TouchHelper created = timed("TouchHelper.create", () -> TouchHelper.create(surfaceView, callback));
            OnyxPen onyx = new OnyxPen(created);
            onyx.open(lim, exc);
            device = onyx;
        }
        limit = new Rect(lim);
        excludes = new ArrayList<>(exc);
        enabled = false;
        style = null;
    }

    private void disableRaw(RawPen h) {
        h.setRawDrawingEnabled(false);
        h.setRawDrawingRenderEnabled(false);
        enabled = false;
        style = null;
    }

    private void writeStyle(RawPen h, Style st) {
        h.setStyle(st);
        style = st;
    }

    /** TouchHelper behind {@link RawPen}. Every call is timed like the old direct path. */
    private final class OnyxPen implements RawPen {
        private final TouchHelper helper;

        OnyxPen(TouchHelper helper) {
            this.helper = helper;
        }

        @Override
        public void open(Rect limitRect, List<Rect> excludes) {
            call("openRawDrawing", () -> helper.setStrokeWidth(InkRenderer.BASE_WIDTH_PX)
                    .setStrokeColor(Color.BLACK)
                    .setLimitRect(limitRect, excludes)
                    .openRawDrawing());
            call("setPenUpRefreshEnabled", () -> helper.setPenUpRefreshEnabled(true));
            call("enableFingerTouch", () -> helper.enableFingerTouch(false));
            call("enableSideBtnErase", () -> helper.enableSideBtnErase(true));
            call("setRawDrawingEnabled", () -> helper.setRawDrawingEnabled(false));
        }

        @Override
        public void close() {
            call("closeRawDrawing", helper::closeRawDrawing);
        }

        @Override
        public void setLimit(Rect limitRect, List<Rect> excludeRects) {
            call("setLimitRect", () -> helper.setLimitRect(limitRect, excludeRects));
        }

        @Override
        public void setHandwritingPenState(int state) {
            call("setScreenHandWritingPenState", () -> EpdController.setScreenHandWritingPenState(surfaceView, state));
        }

        @Override
        public void setStyle(Style st) {
            call("setStrokeStyle", () -> helper.setStrokeStyle(st.strokeStyle));
            call("setStrokeWidth", () -> helper.setStrokeWidth(st.width));
            call("setStrokeColor", () -> helper.setStrokeColor(Color.BLACK));
            call("setBrushRawDrawingEnabled", () -> helper.setBrushRawDrawingEnabled(st.brush));
            call("setRawDrawingRenderEnabled", () -> helper.setRawDrawingRenderEnabled(st.render));
        }

        @Override
        public void setRawDrawingEnabled(boolean on) {
            call("setRawDrawingEnabled", () -> helper.setRawDrawingEnabled(on));
        }

        @Override
        public void setRawDrawingRenderEnabled(boolean on) {
            call("setRawDrawingRenderEnabled", () -> helper.setRawDrawingRenderEnabled(on));
        }

        @Override
        public void enableSideBtnErase(boolean on) {
            call("enableSideBtnErase", () -> helper.enableSideBtnErase(on));
        }
    }

    private void paintNow(Frame frame) {
        SurfaceHolder holder = surfaceView.getHolder();
        if (holder == null || !holder.getSurface().isValid()) {
            return;
        }
        Rect area = frame.area != null ? frame.area
                : new Rect(0, 0, surfaceView.getWidth(), surfaceView.getHeight());
        try {
            if (frame.mode != null) {
                call("setViewDefaultUpdateMode", () -> EpdController.setViewDefaultUpdateMode(surfaceView, frame.mode));
            }
            Canvas canvas = timed("lockCanvas", () -> holder.lockCanvas(area));
            if (canvas != null) {
                try {
                    canvas.drawColor(Color.WHITE);
                    for (int i = 0; i < frame.bitmaps.size(); i++) {
                        Bitmap bitmap = frame.bitmaps.get(i);
                        if (bitmap != null && !bitmap.isRecycled()) {
                            canvas.drawBitmap(bitmap, frame.lefts[i], frame.tops[i], null);
                        }
                    }
                    if (frame.box != null) {
                        canvas.drawRect(frame.box, frame.boxPaint);
                    }
                } finally {
                    call("unlockCanvasAndPost", () -> holder.unlockCanvasAndPost(canvas));
                }
            }
        } catch (RuntimeException e) {
            // The surface went away mid-frame; the next surfaceCreated repaints in full.
            Log.w(TAG, "surface paint skipped", e);
        } finally {
            if (frame.mode != null) {
                try {
                    call("resetViewUpdateMode", () -> EpdController.resetViewUpdateMode(surfaceView));
                } catch (RuntimeException | LinkageError ignored) {
                    // Not an Onyx device.
                }
            }
        }
    }

    private interface Call<T> {
        T run();
    }

    private void call(String name, Runnable action) {
        timed(name, () -> {
            action.run();
            return null;
        });
    }

    private <T> T timed(String name, Call<T> action) {
        long started = SystemClock.uptimeMillis();
        busyOp = name;
        busySince = started;
        try {
            return action.run();
        } finally {
            busySince = 0;
            long ms = SystemClock.uptimeMillis() - started;
            if (ms >= SLOW_CALL_MS) {
                Log.w(TAG, "surface call " + name + " took " + ms + " ms");
                LaunchLog.mark("slow surface call " + name + ": " + ms + " ms");
            }
        }
    }
}
