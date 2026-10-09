package com.zetteldraw.penpoc;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;

import com.onyx.android.sdk.api.device.epd.UpdateMode;
import com.onyx.android.sdk.data.note.TouchPoint;
import com.onyx.android.sdk.pen.EpdPenManager;
import com.onyx.android.sdk.pen.RawInputCallback;
import com.onyx.android.sdk.pen.TouchHelper;
import com.onyx.android.sdk.pen.data.TouchPointList;
import com.zetteldraw.penpoc.data.NotebookPaper;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Live ink for a vertical stack of pages. One SurfaceView + TouchHelper
 * covers the drawing area; it paints whichever pages are scrolled into view.
 * Strokes are stored in page-local coordinates.
 *
 * <p>Geometry is an immutable {@link InkViewport}. {@link #shown} is what the
 * surface displays and the only thing pen points are converted with. A new
 * scroll or page list is {@link #requestViewport requested}: raw drawing
 * pauses first (unless every shown page keeps its place), then the change is
 * applied by a message posted to the back of the main queue. The Onyx SDK
 * posts each pen callback to that same queue, so every stroke drawn on the
 * old picture is converted with the old geometry before the new one takes
 * over, however late the main thread gets to it.
 */
final class PageInkView extends FrameLayout {
    interface Listener {
        void onPageChanged(Board page);

        void onPageBecameNonEmpty(Board page);

        /** A lasso closed; {@code selected == 0} means it caught nothing. */
        void onLassoSelected(int selected);

        /** The selection was dragged and its points rewritten ({@link #onPageChanged} already ran). */
        void onLassoMoved(Lasso.Move move);

        /** The selection went away without moving. */
        void onLassoCancelled();

        /** Undo / redo availability may have changed. */
        void onHistoryChanged();
    }

    enum Tool { PEN, ERASER, LASSO }

    /** Why raw ink is held off; each reason is released on its own. */
    enum Hold { SCROLL, OVERLAY, LOADING }

    private static final int BITMAP_CACHE_SIZE = 3;
    /** Stock-app resume delay after a lasso on colour devices; shorter ones drop the first stroke. */
    private static final long LASSO_RESUME_MS = 500;
    private static final long DRAG_FRAME_MS = 40;

    private final SurfaceView surfaceView;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ArrayList<Rect> excludeRects = new ArrayList<>();
    private final LinkedHashMap<String, Bitmap> bitmaps = new LinkedHashMap<>(8, 0.75f, true);
    /** Page ids laid out into bitmaps since the last {@link #setPages}. */
    private final ArrayList<String> paintedPageIds = new ArrayList<>();
    private final InkHistory history = new InkHistory();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SurfaceWorker worker;
    private Listener listener;
    /** The SurfaceView has a surface (between surfaceCreated and surfaceDestroyed). */
    private boolean surfaceReady;
    /** The window is on screen; nothing is painted or inked while it is not. */
    private boolean windowVisible;
    private long frameSeq;
    /** Id of the full repaint not yet on screen, or 0; the pen stays off until it is. */
    private long fullFrameId;
    private int fullFramesPainted;
    private int handwritingPenState = Integer.MIN_VALUE;
    /** Eraser pass in progress: the SDK must not draw ink for it. */
    private boolean eraseRenderOff;
    private static final long PEN_OFF_DEADLINE_MS = 1_000;
    private final Runnable applyDespiteStuckPen = this::applyDespiteStuckPen;
    /** What the surface shows; pen points convert through this. Written on the main thread only. */
    private volatile InkViewport shown = InkViewport.EMPTY;
    /** {@link #shown} when the current pen stroke went down; touched only on the SDK's callback thread. */
    private InkViewport penDownViewport;
    /** Set while the first screen of a new page list renders off the main thread. */
    private Object renderToken;
    private InkViewport.Layout renderingLayout;
    /** Next viewport, or null; {@link #applyViewport} installs it behind queued pen callbacks. */
    private InkViewport requested;
    private boolean applyPosted;
    private final Runnable applyViewport = this::applyViewport;
    /** What raw drawing was last asked to be. */
    private boolean rawOn;
    private boolean penEverOn;
    private boolean live;
    private final EnumSet<Hold> holds = EnumSet.noneOf(Hold.class);
    private boolean paused;
    /** What the buttons show. Updated the moment a tool is tapped. */
    private volatile Tool tool = Tool.PEN;
    /**
     * What the firmware is set to. A stroke already on the glass keeps this
     * until the pen lifts, so the Boox finishes that stroke in the tool it
     * started with.
     */
    private volatile Tool firmwareTool = Tool.PEN;
    /** Captured at pen-down. The point list is committed as this, not as whatever the buttons say later. */
    private Tool strokeTool;
    private boolean strokeOpen;
    private boolean pointsCommitted;
    private boolean penLifted;
    private List<Rect> extraExcludeRects = new ArrayList<>();

    private final Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Selection selection;
    private boolean resumePending;
    private long lastFrameAt;
    private final Runnable resumeAfterLasso = this::onResumeAfterLasso;
    private final Runnable dragFrame = this::drawDragFrame;

    PageInkView(Context context) {
        super(context);
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(Color.BLACK);
        paint.setStrokeWidth(InkRenderer.BASE_WIDTH_PX);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        float density = context.getResources().getDisplayMetrics().density;
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setColor(Color.BLACK);
        boxPaint.setStrokeWidth(Math.max(1f, 1.5f * density));
        boxPaint.setPathEffect(new DashPathEffect(new float[]{6f * density, 4f * density}, 0f));
        surfaceView = new SurfaceView(context);
        surfaceView.setOnTouchListener((View v, MotionEvent event) -> isStylus(event));
        addView(surfaceView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        surfaceView.getHolder().addCallback(surfaceCallback);
        worker = new SurfaceWorker(surfaceView, rawInputCallback, this::onFramePainted);
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    static boolean isStylus(MotionEvent event) {
        int tool = event.getToolType(0);
        return tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER;
    }

    /** {@code pageHeights[i]} is the height of {@code next.get(i)}; {@code gap} separates pages. */
    void setPages(List<Board> next, int[] pageHeights, int gap) {
        setPages(next, pageHeights, gap, target().scrollY);
    }

    /**
     * Same as {@link #setPages(List, int[], int)}, and the first paint uses
     * {@code scrollY}. Pages above that offset are not laid out into bitmaps.
     */
    void setPages(List<Board> next, int[] pageHeights, int gap, int scrollY) {
        paintedPageIds.clear();
        if (selection != null) {
            scheduleResume();
            endSelection();
            if (listener != null) {
                listener.onLassoCancelled();
            }
        }
        requestViewport(new InkViewport(new InkViewport.Layout(next, pageHeights, gap), scrollY));
    }

    /** Page ids rendered into bitmaps since the last {@link #setPages}. */
    List<String> paintedPageIds() {
        return new ArrayList<>(paintedPageIds);
    }

    void setContentScrollY(int y) {
        requestViewport(target().withScroll(y));
    }

    /** The viewport the surface is heading to: the pending one, else the shown one. */
    private InkViewport target() {
        return requested != null ? requested : shown;
    }

    /**
     * Schedules {@code next} behind every pen callback already queued. When
     * the change would move a shown page, raw drawing pauses now so no stroke
     * is drawn on a picture that no longer matches the geometry it will be
     * converted with.
     */
    private void requestViewport(InkViewport next) {
        requested = next;
        boolean inkWasLive = rawOn;
        if (shown.isEmpty() && !penEverOn) {
            // Nothing was ever on screen to draw on, so no stroke can be waiting for the old
            // geometry: show the first list now instead of waiting for the surface worker,
            // whose first pass creates the Onyx TouchHelper.
            main.removeCallbacks(applyViewport);
            main.post(applyViewport);
            applyPosted = true;
            return;
        }
        if (!next.mapsLike(shown)) {
            syncRaw();
            if (!applyPosted) {
                // Applied once the worker has really switched the pen off, so it lands behind every
                // stroke the SDK read before that; a stuck worker only delays it by the deadline.
                applyPosted = true;
                worker.whenPenOff(applyViewport);
                main.removeCallbacks(applyDespiteStuckPen);
                main.postDelayed(applyDespiteStuckPen, PEN_OFF_DEADLINE_MS);
            }
            return;
        }
        if (!applyPosted || inkWasLive) {
            // Re-post so the change also lands behind strokes the pen sent since the last post.
            main.removeCallbacks(applyViewport);
            main.post(applyViewport);
            applyPosted = true;
        }
    }

    /**
     * A new page list first renders its visible pages off the main thread;
     * {@link #requested} stays set meanwhile, so the pen stays paused and the
     * old picture stays up until the new one can be painted in one go.
     */
    private void applyDespiteStuckPen() {
        if (applyPosted && requested != null) {
            Log.w("zd-stall", "pen did not report off within " + PEN_OFF_DEADLINE_MS + " ms; applying the new viewport");
            applyViewport();
        }
    }

    private void applyViewport() {
        applyPosted = false;
        main.removeCallbacks(applyDespiteStuckPen);
        InkViewport next = requested;
        if (next == null) {
            return;
        }
        if (renderToken != null && renderingLayout == next.layout) {
            return;
        }
        if (next.layout != shown.layout && !next.mapsLike(shown)) {
            List<RenderJob> jobs = missingBitmaps(next);
            if (!jobs.isEmpty()) {
                Object token = new Object();
                renderToken = token;
                renderingLayout = next.layout;
                int width = surfaceView.getWidth();
                UiExecutors.renderer.execute(() -> {
                    long started = SystemClock.uptimeMillis();
                    for (RenderJob job : jobs) {
                        job.bitmap = renderPage(job.strokes, width, job.height);
                    }
                    LaunchLog.once("render", "first screen of pages rendered: " + jobs.size() + " pages in "
                            + (SystemClock.uptimeMillis() - started) + " ms");
                    main.post(() -> finishRender(token, jobs));
                });
                return;
            }
        }
        requested = null;
        install(next);
    }

    private void finishRender(Object token, List<RenderJob> jobs) {
        boolean current = token == renderToken;
        InkViewport next = requested;
        if (current) {
            renderToken = null;
        }
        boolean usable = current && next != null && next.layout == renderingLayout;
        for (RenderJob job : jobs) {
            if (usable && sameStrokes(job.page.strokes, job.strokes)) {
                putBitmap(job.page.id, job.bitmap);
            } else {
                job.bitmap.recycle();
            }
        }
        if (usable) {
            renderingLayout = null;
            requested = null;
            install(next);
        } else if (current) {
            renderingLayout = null;
            if (next != null && !applyPosted) {
                applyPosted = true;
                main.post(applyViewport);
            }
        }
    }

    /** Visible pages of {@code next} with no cached bitmap, with stroke snapshots taken here on the main thread. */
    private List<RenderJob> missingBitmaps(InkViewport next) {
        ArrayList<RenderJob> jobs = new ArrayList<>();
        if (surfaceView.getWidth() <= 0 || next.isEmpty()) {
            return jobs;
        }
        int last = next.lastVisible(surfaceView.getHeight());
        for (int index = next.firstVisible(); index <= last; index++) {
            Board page = next.page(index);
            int height = next.layout.heights[index];
            // Paper ink is drawn from the sheet in the on-screen slice only.
            // Snapshotting page.strokes here would cache a blank bitmap.
            if (page.paper != null) {
                continue;
            }
            Bitmap cached = bitmaps.get(page.id);
            if (cached == null || cached.getHeight() != height) {
                jobs.add(new RenderJob(page, new ArrayList<>(page.strokes), height));
                paintedPageIds.add(page.id);
            }
        }
        return jobs;
    }

    private static boolean sameStrokes(List<InkRenderer.InkStroke> now, List<InkRenderer.InkStroke> snapshot) {
        if (now.size() != snapshot.size()) {
            return false;
        }
        for (int i = 0; i < now.size(); i++) {
            if (now.get(i) != snapshot.get(i)) {
                return false;
            }
        }
        return true;
    }

    private static final class RenderJob {
        final Board page;
        final List<InkRenderer.InkStroke> strokes;
        final int height;
        Bitmap bitmap;

        RenderJob(Board page, List<InkRenderer.InkStroke> strokes, int height) {
            this.page = page;
            this.strokes = strokes;
            this.height = height;
        }
    }

    private void install(InkViewport next) {
        boolean newLayout = next.layout != shown.layout;
        shown = next;
        if (newLayout) {
            Iterator<Map.Entry<String, Bitmap>> it = bitmaps.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, Bitmap> entry = it.next();
                int index = shown.layout.indexOf(entry.getKey());
                if (index < 0 || entry.getValue().getHeight() != shown.layout.heights[index]) {
                    // Not recycled: a queued frame may still draw it; the GC frees it.
                    it.remove();
                }
            }
            HashSet<String> ids = new HashSet<>();
            for (Board page : shown.layout.pages) {
                ids.add(page.id);
            }
            history.retainPages(ids);
            historyChanged();
        }
        redrawAll();
    }

    /** True while a requested viewport would convert pen points differently from the shown one. */
    private boolean geometryPending() {
        return requested != null && !requested.mapsLike(shown);
    }

    void invalidatePage(String pageId) {
        bitmaps.remove(pageId);
    }

    /** Full repaint; the pen stays off until it is on screen. */
    void redrawAll() {
        requestFrame(true, null, null);
    }

    /** Rectangles the pen reader is told to leave alone, in this view's coordinates. */
    List<Rect> penExcludes() {
        ArrayList<Rect> copy = new ArrayList<>(excludeRects.size());
        for (Rect rect : excludeRects) {
            copy.add(new Rect(rect));
        }
        return copy;
    }

    void setExtraExcludeRects(List<Rect> rects) {
        extraExcludeRects = rects == null ? new ArrayList<>() : rects;
        updateExcludeRects();
    }

    void setTool(Tool next) {
        if (selection != null && next != Tool.LASSO) {
            cancelSelection();
        }
        boolean repeat = next == tool;
        tool = next;
        // onCreate selects Pen while that is already the tool, before the pen has ever
        // come on. That is not a user tap and must not take the pen down.
        if (repeat && !penEverOn) {
            return;
        }
        // Behind anything already queued, including a pen-down the SDK posted before this tap.
        // That stroke then starts as the old tool; the tap still updates the button immediately.
        main.post(() -> commitTool(next));
    }

    private void commitTool(Tool next) {
        if (next != tool) {
            return;
        }
        // The button and the firmware style stay the same tool. A stroke already
        // down keeps the tool it started with via strokeTool, captured at pen-down.
        pushToolStyle();
    }

    /**
     * The firmware follows the button. Raw drawing stays as it is; only the
     * snapshot changes — except when that snapshot turns scribble render off.
     * On Boox, {@code setRawDrawingRenderEnabled(false)} drops the live overlay
     * (and often refreshes the panel). Without a full bitmap frame in the same
     * breath, every stroke vanishes from the glass while still sitting in
     * memory. Keep the two display layers in lockstep: suppress scribble only
     * together with a re-push of frozen ink.
     */
    private void pushToolStyle() {
        boolean droppingScribble = scribbleRenderOn() && tool == Tool.ERASER;
        firmwareTool = tool;
        if (droppingScribble && surfaceVisible() && surfaceView.getWidth() > 0) {
            // Full frame holds raw drawing off until the bitmaps are painted,
            // then onFramePainted syncs the eraser style with drawing back on.
            requestFrame(true, null, null);
        } else {
            syncRaw();
        }
    }

    /** Whether the current firmware snapshot would leave the scribble overlay painting. */
    private boolean scribbleRenderOn() {
        return firmwareTool != Tool.ERASER && !eraseRenderOff;
    }

    Tool tool() {
        return tool;
    }

    boolean hasSelection() {
        return selection != null;
    }

    /** While a selection is up, every touch (pen or finger) drags or cancels it; nothing scrolls. */
    boolean capturesTouches() {
        return selection != null;
    }

    boolean canUndo() {
        return history.canUndo();
    }

    boolean canRedo() {
        return history.canRedo();
    }

    /** Forget every edit, e.g. when another page list opens. */
    void clearHistory() {
        history.clear();
        historyChanged();
    }

    /** Reverts the newest stroke, erase or lasso edit. False when it changed nothing. */
    boolean undo() {
        if (selection != null) {
            cancelSelection();
        }
        return applyHistory(history.undo());
    }

    boolean redo() {
        if (selection != null) {
            cancelSelection();
        }
        return applyHistory(history.redo());
    }

    private boolean applyHistory(Set<Board> changed) {
        for (Board page : changed) {
            invalidatePage(page.id);
        }
        if (!changed.isEmpty()) {
            redrawAll();
        }
        historyChanged();
        for (Board page : changed) {
            notifyChanged(page);
        }
        return !changed.isEmpty();
    }

    private void historyChanged() {
        if (listener != null) {
            listener.onHistoryChanged();
        }
    }

    void setLive(boolean on) {
        if (live != on) {
            live = on;
            if (on) {
                requestFrame(true, null, null);
            }
        }
        syncRaw();
    }

    /** Stop raw ink until {@link #release} of the same reason. */
    void hold(Hold reason) {
        holds.add(reason);
        syncRaw();
    }

    void release(Hold reason) {
        holds.remove(reason);
        resumeScribble();
    }

    /** The callbacks TouchHelper is given; tests drive them like the SDK does. */
    RawInputCallback rawInput() {
        return rawInputCallback;
    }

    /** Whether ink is allowed now (holds, lasso, pending geometry); the surface gate comes on top. */
    boolean inkEnabled() {
        return canInk();
    }

    /** Whether the pen is actually asked to be on: allowed, surface visible and its last full repaint up. */
    boolean penOn() {
        return rawOn;
    }

    int fullFramesPainted() {
        return fullFramesPainted;
    }

    void pauseLive() {
        paused = true;
        syncRaw();
    }

    void resumeLive() {
        paused = false;
        resumeScribble();
    }

    void close() {
        main.removeCallbacks(resumeAfterLasso);
        removeCallbacks(dragFrame);
        main.removeCallbacks(applyViewport);
        main.removeCallbacks(applyDespiteStuckPen);
        applyPosted = false;
        renderToken = null;
        renderingLayout = null;
        endSelection();
        live = false;
        worker.close();
        bitmaps.clear();
    }

    private final SurfaceHolder.Callback surfaceCallback = new SurfaceHolder.Callback() {
        @Override
        public void surfaceCreated(SurfaceHolder holder) {
            onSurface(true);
        }

        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            updateExcludeRects();
            requestFrame(true, null, null);
        }

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
            onSurface(false);
        }
    };

    /** Package-private so tests can stand in for the SurfaceView callbacks. */
    void onSurface(boolean ready) {
        LaunchLog.once("surface", "drawing surface " + (ready ? "created" : "destroyed before it was created"));
        surfaceReady = ready;
        if (ready) {
            requestFrame(true, null, null);
        }
        syncRaw();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        boolean visible = visibility == View.VISIBLE;
        if (visible == windowVisible) {
            return;
        }
        windowVisible = visible;
        if (visible) {
            requestFrame(true, null, null);
        }
        syncRaw();
    }

    private boolean surfaceVisible() {
        return surfaceReady && windowVisible;
    }

    /**
     * Queues a picture of the visible pages for the worker. Nothing is
     * queued while the surface is not visible; becoming visible repaints in
     * full. A full repaint holds the pen off until {@link #onFramePainted}.
     */
    private void requestFrame(boolean full, RectF dirty, UpdateMode mode) {
        if (!surfaceVisible() || surfaceView.getWidth() <= 0) {
            return;
        }
        long id = ++frameSeq;
        if (full) {
            fullFrameId = id;
            syncRaw();
        }
        worker.paint(buildFrame(id, full, dirty, mode));
    }

    private void onFramePainted(long id, boolean full) {
        if (full) {
            if (!shown.isEmpty()) {
                LaunchLog.once("frame", "first full frame with pages painted: " + shown.layout.pages.size()
                        + " pages in the list");
            }
            fullFramesPainted++;
            if (id == fullFrameId) {
                fullFrameId = 0;
                syncRaw();
            }
        }
    }

    private SurfaceWorker.Frame buildFrame(long id, boolean full, RectF dirty, UpdateMode mode) {
        Rect area = null;
        if (!full && dirty != null && !dirty.isEmpty()) {
            area = new Rect();
            dirty.roundOut(area);
            int pad = (int) Math.ceil(InkRenderer.BASE_WIDTH_PX * 2f);
            area.inset(-pad, -pad);
        }
        ArrayList<Bitmap> layers = new ArrayList<>();
        ArrayList<Float> lefts = new ArrayList<>();
        ArrayList<Float> tops = new ArrayList<>();
        RectF box = null;
        InkViewport view = shown;
        int last = view.lastVisible(surfaceView.getHeight());
        boolean paperSelection = selection != null && selection.acrossPaper;
        for (int index = view.firstVisible(); index <= last && !view.isEmpty(); index++) {
            float top = view.pageTop(index);
            if (paperSelection) {
                Selection sel = selection;
                Bitmap base = sel.sliceBases == null ? null : sel.sliceBases.get(view.page(index).id);
                if (base == null) {
                    base = bitmapFor(view.page(index), view.layout.heights[index]);
                }
                if (base != null) {
                    layers.add(base);
                    lefts.add(0f);
                    tops.add(top);
                }
                continue;
            }
            if (selection != null && selection.index == index) {
                Selection sel = selection;
                layers.add(sel.base);
                lefts.add(0f);
                tops.add(top);
                layers.add(sel.sprite);
                lefts.add(sel.bounds.left + sel.dx);
                tops.add(top + sel.bounds.top + sel.dy);
                box = boxOnSurface(sel);
                continue;
            }
            Bitmap bitmap = bitmapFor(view.page(index), view.layout.heights[index]);
            if (bitmap != null) {
                layers.add(bitmap);
                lefts.add(0f);
                tops.add(top);
            }
        }
        if (paperSelection) {
            Selection sel = selection;
            layers.add(sel.sprite);
            lefts.add(sel.bounds.left + sel.dx);
            tops.add(sel.bounds.top + sel.dy - view.scrollY);
            box = boxOnSurface(sel);
        }
        float[] l = new float[layers.size()];
        float[] t = new float[layers.size()];
        for (int i = 0; i < l.length; i++) {
            l[i] = lefts.get(i);
            t[i] = tops.get(i);
        }
        return new SurfaceWorker.Frame(id, full, area, mode, layers, l, t, box, new Paint(boxPaint));
    }

    /** The single gate for raw drawing: every resume path asks this. */
    private boolean canInk() {
        return live && holds.isEmpty() && !paused && !resumePending && selection == null && !geometryPending()
                && !shown.isEmpty();
    }

    /** Hands the pen state the main thread wants to the worker; the worker skips calls that change nothing. */
    private void syncRaw() {
        boolean on = canInk() && surfaceVisible() && fullFrameId == 0;
        rawOn = on;
        if (on) {
            penEverOn = true;
            LaunchLog.once("pen", "pen on");
        }
        boolean lasso = firmwareTool == Tool.LASSO;
        boolean eraser = firmwareTool == Tool.ERASER;
        SurfaceWorker.Style style = new SurfaceWorker.Style(
                lasso ? TouchHelper.STROKE_STYLE_DASH : TouchHelper.STROKE_STYLE_FOUNTAIN,
                lasso ? Math.max(2f, InkRenderer.BASE_WIDTH_PX * 0.5f) : InkRenderer.BASE_WIDTH_PX,
                !eraser, !eraser && !eraseRenderOff);
        worker.setPen(live && surfaceReady, on, canvasLimit(), excludeRects, style, handwritingPenState);
    }

    /**
     * On a stylus Boox the SDK already posts every callback to the main
     * thread; other renderers call from their reader thread. Either way all
     * ink state is touched on the main thread only, in callback order.
     */
    private void onMain(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run();
        } else {
            main.post(action);
        }
    }

    private final RawInputCallback rawInputCallback = new RawInputCallback() {
        @Override
        public void onBeginRawDrawing(boolean shortcutErase, TouchPoint point) {
            penDownViewport = shown;
            // Captured now, not when the point list arrives: a tool tap in between
            // must not reclassify this stroke. shortcutErase is the side button.
            Tool mode = shortcutErase ? Tool.ERASER : firmwareTool;
            onMain(() -> beginStroke(mode));
        }

        @Override
        public void onEndRawDrawing(boolean shortcutErase, TouchPoint point) {
            // The side button may already be up, and the toolbar tool may already
            // have changed. Neither one rewrites the stroke that is in flight.
            penDownViewport = null;
        }

        @Override
        public void onRawDrawingTouchPointMoveReceived(TouchPoint point) {
        }

        @Override
        public void onRawDrawingTouchPointListReceived(TouchPointList touchPointList) {
            if (touchPointList == null) {
                return;
            }
            ArrayList<TouchPoint> points = InkRenderer.copyPoints(touchPointList.getPoints());
            InkViewport drawnOn = penDownViewport != null ? penDownViewport : shown;
            onMain(() -> commitPoints(points, drawnOn, false));
        }

        @Override
        public void onBeginRawErasing(boolean shortcutErase, TouchPoint point) {
            penDownViewport = shown;
            onMain(() -> beginStroke(Tool.ERASER));
        }

        @Override
        public void onEndRawErasing(boolean shortcutErase, TouchPoint point) {
            penDownViewport = null;
        }

        @Override
        public void onRawErasingTouchPointMoveReceived(TouchPoint point) {
        }

        @Override
        public void onRawErasingTouchPointListReceived(TouchPointList touchPointList) {
            if (touchPointList == null) {
                return;
            }
            ArrayList<TouchPoint> points = InkRenderer.copyPoints(touchPointList.getPoints());
            InkViewport drawnOn = penDownViewport != null ? penDownViewport : shown;
            onMain(() -> commitPoints(points, drawnOn, true));
        }

        @Override
        public void onPenUpRefresh(RectF refreshRect) {
            main.post(() -> {
                penLifted = true;
                if (pointsCommitted || strokeTool == null) {
                    closeStroke();
                }
                requestFrame(false, refreshRect, UpdateMode.HAND_WRITING_REPAINT_MODE);
            });
        }
    };

    private void beginStroke(Tool mode) {
        strokeTool = mode;
        strokeOpen = true;
        pointsCommitted = false;
        penLifted = false;
        // Side button while the pen is the firmware tool: render off for this stroke.
        // That is a snapshot change. Raw drawing stays on; enabling it would restore
        // the default pen and paint ink.
        if (mode == Tool.ERASER && firmwareTool != Tool.ERASER) {
            eraseRenderOff = true;
            syncRaw();
        }
    }

    private void commitPoints(List<TouchPoint> points, InkViewport drawnOn, boolean eraseChannel) {
        Tool mode = strokeTool != null ? strokeTool : firmwareTool;
        if (eraseChannel || mode == Tool.ERASER) {
            eraseStrokes(points, drawnOn);
        } else if (mode == Tool.LASSO) {
            finishLasso(points, drawnOn);
        } else {
            addStroke(points, drawnOn);
        }
        pointsCommitted = true;
        if (penLifted) {
            closeStroke();
        }
    }

    /** The pen is up. Turn fountain render back on after the side button. */
    private void closeStroke() {
        boolean renderOff = eraseRenderOff;
        boolean toolLag = firmwareTool != tool;
        strokeOpen = false;
        strokeTool = null;
        penLifted = false;
        pointsCommitted = false;
        eraseRenderOff = false;
        if (renderOff || toolLag) {
            pushToolStyle();
        }
    }

    /** {@code points} are in surface coordinates, drawn on the viewport shown now. */
    void addStroke(List<TouchPoint> points) {
        addStroke(points, shown);
    }

    /**
     * {@code points} are in surface coordinates, as TouchHelper reports them,
     * drawn while {@code drawnOn} was on screen. The stroke is bound to the
     * page under its first point and converted to that page's coordinates
     * here, once; that page keeps it even if it is no longer shown.
     */
    void addStroke(List<TouchPoint> points, InkViewport drawnOn) {
        if (points == null || points.isEmpty()) {
            return;
        }
        if (onPaper(drawnOn)) {
            addPaperStroke(points, drawnOn);
            return;
        }
        int index = drawnOn.pageIndexAt(points.get(0).y);
        if (index < 0) {
            return;
        }
        Board page = drawnOn.page(index);
        boolean wasBlank = page.isBlank();
        InkRenderer.InkStroke stroke = InkRenderer.strokeFrom(drawnOn.toPage(points, index));
        page.strokes.add(stroke);
        history.record(InkHistory.Edit.of(InkHistory.Part.added(page, stroke)));
        historyChanged();
        Bitmap cached = bitmaps.get(page.id);
        if (cached != null) {
            InkRenderer.draw(new Canvas(cached), paint, stroke);
        }
        notifyChanged(page);
        if (wasBlank && listener != null) {
            listener.onPageBecameNonEmpty(page);
        }
    }

    /** {@code eraserPath} is in surface coordinates. */
    void eraseStrokes(List<TouchPoint> eraserPath) {
        eraseStrokes(eraserPath, shown);
    }

    void eraseStrokes(List<TouchPoint> eraserPath, InkViewport view) {
        if (eraserPath == null || eraserPath.isEmpty()) {
            return;
        }
        if (onPaper(view)) {
            erasePaper(eraserPath, view);
            return;
        }
        ArrayList<Board> changed = new ArrayList<>();
        InkHistory.Edit edit = new InkHistory.Edit();
        int last = view.lastVisible(surfaceView.getHeight());
        for (int index = view.firstVisible(); index <= last; index++) {
            Board page = view.page(index);
            if (page.strokes.isEmpty()) {
                continue;
            }
            List<TouchPoint> path = view.toPage(eraserPath, index);
            ArrayList<InkHistory.Placed> removed = new ArrayList<>();
            for (int i = 0; i < page.strokes.size(); i++) {
                if (InkRenderer.hits(page.strokes.get(i), path)) {
                    removed.add(new InkHistory.Placed(page.strokes.get(i), i));
                }
            }
            if (!removed.isEmpty()) {
                for (int i = removed.size() - 1; i >= 0; i--) {
                    page.strokes.remove(removed.get(i).index);
                }
                edit.add(InkHistory.Part.removed(page, removed));
                invalidatePage(page.id);
                changed.add(page);
            }
        }
        if (!changed.isEmpty()) {
            history.record(edit);
            historyChanged();
            redrawAll();
            for (Board page : changed) {
                notifyChanged(page);
            }
        }
    }

    private void notifyChanged(Board page) {
        if (listener != null) {
            listener.onPageChanged(page);
        }
    }

    private Bitmap bitmapFor(Board page, int height) {
        Bitmap bitmap = bitmaps.get(page.id);
        if (bitmap != null && bitmap.getHeight() == height) {
            return bitmap;
        }
        int width = surfaceView.getWidth();
        if (width <= 0) {
            return null;
        }
        if (page.paper != null) {
            bitmap = renderSlice(page, width, height, null);
        } else {
            bitmap = renderPage(page.strokes, width, height);
        }
        putBitmap(page.id, bitmap);
        return bitmap;
    }

    /** Evicted bitmaps are not recycled: a queued frame may still draw them; the GC frees them. */
    private void putBitmap(String pageId, Bitmap bitmap) {
        bitmaps.remove(pageId);
        while (bitmaps.size() >= BITMAP_CACHE_SIZE) {
            Iterator<Map.Entry<String, Bitmap>> it = bitmaps.entrySet().iterator();
            it.next();
            it.remove();
        }
        bitmaps.put(pageId, bitmap);
    }

    /** Any thread: {@code strokes} must not change while this runs (pass a snapshot off the main thread). */
    static Bitmap renderPage(List<InkRenderer.InkStroke> strokes, int width, int height) {
        Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
        ink.setStyle(Paint.Style.STROKE);
        ink.setColor(Color.BLACK);
        ink.setStrokeCap(Paint.Cap.ROUND);
        ink.setStrokeJoin(Paint.Join.ROUND);
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.WHITE);
        Canvas canvas = new Canvas(bitmap);
        InkRenderer.drawAll(canvas, ink, strokes);
        return bitmap;
    }

    // ---- Lasso ----

    /**
     * {@code outline} is in surface coordinates, as TouchHelper reports it. It
     * is bound to its page now; selecting runs a moment later, outside the pen
     * callback, and only if that page is still shown.
     */
    void finishLasso(List<TouchPoint> outline) {
        finishLasso(outline, shown);
    }

    void finishLasso(List<TouchPoint> outline, InkViewport drawnOn) {
        Tool mode = strokeTool != null ? strokeTool : tool;
        if (mode != Tool.LASSO || selection != null || outline.size() < 3) {
            reportLasso(0);
            return;
        }
        if (onPaper(drawnOn)) {
            finishPaperLasso(outline, drawnOn);
            return;
        }
        int index = drawnOn.pageIndexAt(outline.get(0).y);
        if (index < 0) {
            reportLasso(0);
            return;
        }
        Board page = drawnOn.page(index);
        List<TouchPoint> local = drawnOn.toPage(outline, index);
        main.post(() -> selectOnPage(page, local));
    }

    private void selectOnPage(Board page, List<TouchPoint> outline) {
        int index = shown.layout.indexOf(page.id);
        // The outline was already accepted as a lasso stroke. A tool tap since then
        // must not drop it; the stroke finishes as the tool it started with.
        if (selection != null || index < 0) {
            reportLasso(0);
            return;
        }
        List<InkRenderer.InkStroke> picked = Lasso.select(page.strokes, outline);
        if (picked.isEmpty()) {
            reportLasso(0);
            return;
        }
        reportLasso(beginSelection(page, index, picked) ? picked.size() : 0);
    }

    private void reportLasso(int selected) {
        if (listener != null) {
            listener.onLassoSelected(selected);
        }
    }

    private boolean beginSelection(Board page, int index, List<InkRenderer.InkStroke> picked) {
        int width = surfaceView.getWidth();
        if (width <= 0) {
            return false;
        }
        cancelResume();
        Set<String> ids = Lasso.idsOf(picked);
        RectF bounds = Lasso.boundsOf(picked);
        Rect spriteRect = new Rect();
        bounds.roundOut(spriteRect);

        Bitmap base = Bitmap.createBitmap(width, shown.layout.heights[index], Bitmap.Config.ARGB_8888);
        base.eraseColor(Color.WHITE);
        Canvas baseCanvas = new Canvas(base);
        ArrayList<InkRenderer.InkStroke> rest = new ArrayList<>();
        for (InkRenderer.InkStroke stroke : page.strokes) {
            if (!ids.contains(stroke.id)) {
                rest.add(stroke);
            }
        }
        InkRenderer.drawAll(baseCanvas, paint, rest);
        Bitmap sprite = Bitmap.createBitmap(Math.max(1, spriteRect.width()), Math.max(1, spriteRect.height()),
                Bitmap.Config.ARGB_8888);
        Canvas spriteCanvas = new Canvas(sprite);
        spriteCanvas.translate(-spriteRect.left, -spriteRect.top);
        InkRenderer.drawAll(spriteCanvas, paint, picked);
        selection = new Selection(page, index, ids, new RectF(spriteRect), base, sprite);
        setPenState(EpdPenManager.PEN_PAUSE);
        requestFrame(true, null, UpdateMode.GC);
        return true;
    }

    /** Dashed box around the (possibly dragged) selection, in surface coordinates. */
    private RectF boxOnSurface(Selection s) {
        return boxOnSurface(s, s.dx, s.dy);
    }

    private RectF boxOnSurface(Selection s, float dx, float dy) {
        RectF box = new RectF(s.bounds);
        if (s.acrossPaper) {
            box.offset(dx, dy - shown.scrollY);
            float pad = 4f * getResources().getDisplayMetrics().density;
            box.inset(-pad, -pad);
            return box;
        }
        box.offset(dx, shown.pageTop(s.index) + dy);
        float pad = 4f * getResources().getDisplayMetrics().density;
        box.inset(-pad, -pad);
        return box;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (selection == null) {
            return super.dispatchTouchEvent(event);
        }
        onSelectionTouch(event);
        return true;
    }

    private void onSelectionTouch(MotionEvent event) {
        Selection s = selection;
        float x = event.getX();
        float y = event.getY();
        int action = event.getActionMasked();
        if (action != MotionEvent.ACTION_DOWN && !s.sawDown) {
            // Tail of the lasso gesture itself, delivered after raw drawing let go.
            return;
        }
        switch (action) {
            case MotionEvent.ACTION_DOWN: {
                s.sawDown = true;
                RectF grab = boxOnSurface(s);
                float slop = 20f * getResources().getDisplayMetrics().density;
                grab.inset(-slop, -slop);
                s.dragging = grab.contains(x, y);
                s.downX = x;
                s.downY = y;
                s.drawnBox = boxOnSurface(s);
                break;
            }
            case MotionEvent.ACTION_MOVE:
                if (s.dragging) {
                    float[] offset = s.acrossPaper
                            ? Lasso.clampInto(s.bounds, x - s.downX, y - s.downY,
                                    0f, shown.scrollY, surfaceView.getWidth(),
                                    shown.scrollY + surfaceView.getHeight())
                            : Lasso.clampOffset(s.bounds, x - s.downX, y - s.downY,
                                    surfaceView.getWidth(), shown.layout.heights[s.index]);
                    s.dx = offset[0];
                    s.dy = offset[1];
                    scheduleDragFrame();
                }
                break;
            case MotionEvent.ACTION_UP:
                if (s.dragging) {
                    commitSelection();
                } else {
                    cancelSelection();
                }
                break;
            case MotionEvent.ACTION_CANCEL:
                cancelSelection();
                break;
            default:
                break;
        }
    }

    private void scheduleDragFrame() {
        removeCallbacks(dragFrame);
        long wait = DRAG_FRAME_MS - (SystemClock.uptimeMillis() - lastFrameAt);
        if (wait <= 0) {
            drawDragFrame();
        } else {
            postDelayed(dragFrame, wait);
        }
    }

    private void drawDragFrame() {
        Selection s = selection;
        if (s == null) {
            return;
        }
        lastFrameAt = SystemClock.uptimeMillis();
        RectF box = boxOnSurface(s);
        RectF dirty = new RectF(box);
        if (s.drawnBox != null) {
            dirty.union(s.drawnBox);
        }
        s.drawnBox = box;
        requestFrame(false, dirty, UpdateMode.ANIMATION_MONO);
    }

    private void commitSelection() {
        Selection s = selection;
        if (s.acrossPaper) {
            commitPaperSelection(s);
            return;
        }
        removeCallbacks(dragFrame);
        RectF dirty = touchedArea(s);
        Lasso.Move move = Lasso.move(s.page, s.ids, s.dx, s.dy);
        // Hold the pen off before dropping the selection, so this cannot turn
        // raw drawing back on in the lasso style while the tool is about to be pen.
        scheduleResume();
        endSelection();
        invalidatePage(s.page.id);
        requestFrame(false, dirty, UpdateMode.GC);
        if (move == null) {
            if (listener != null) {
                listener.onLassoCancelled();
            }
            return;
        }
        history.record(InkHistory.Edit.of(move.historyPart()));
        historyChanged();
        notifyChanged(s.page);
        if (listener != null) {
            listener.onLassoMoved(move);
        }
    }

    /** Everything the selection has painted: where it started, where it was last drawn, where it is now. */
    private RectF touchedArea(Selection s) {
        RectF area = boxOnSurface(s, 0f, 0f);
        area.union(boxOnSurface(s));
        if (s.drawnBox != null) {
            area.union(s.drawnBox);
        }
        return area;
    }

    private void cancelSelection() {
        Selection s = selection;
        if (s == null) {
            return;
        }
        removeCallbacks(dragFrame);
        RectF dirty = touchedArea(s);
        scheduleResume();
        endSelection();
        requestFrame(false, dirty, UpdateMode.GC);
        if (listener != null) {
            listener.onLassoCancelled();
        }
    }

    private void endSelection() {
        if (selection == null) {
            return;
        }
        // Not recycled: a queued frame may still draw them.
        selection = null;
        syncRaw();
    }

    private void scheduleResume() {
        main.removeCallbacks(resumeAfterLasso);
        resumePending = true;
        main.postDelayed(resumeAfterLasso, LASSO_RESUME_MS);
    }

    private void cancelResume() {
        main.removeCallbacks(resumeAfterLasso);
        resumePending = false;
    }

    private void onResumeAfterLasso() {
        resumePending = false;
        if (selection != null) {
            return;
        }
        setPenState(EpdPenManager.PEN_DRAWING);
        resumeScribble();
    }

    private void setPenState(int state) {
        handwritingPenState = state;
        syncRaw();
    }

    private static boolean onPaper(InkViewport view) {
        return view != null && !view.isEmpty() && view.page(0).paper != null;
    }

    /** Surface points onto the notebook. With no gap, content y is paper y. */
    private static ArrayList<TouchPoint> toPaper(List<TouchPoint> surface, int scrollY) {
        ArrayList<TouchPoint> copy = InkRenderer.copyPoints(surface);
        for (TouchPoint point : copy) {
            point.y += scrollY;
        }
        return copy;
    }

    private void addPaperStroke(List<TouchPoint> points, InkViewport drawnOn) {
        int index = drawnOn.pageIndexAt(points.get(0).y);
        if (index < 0) {
            return;
        }
        Board page = drawnOn.page(index);
        NotebookPaper paper = page.paper;
        int slicesBefore = paper.sliceCount();
        boolean wasBlank = page.isBlank();
        ArrayList<TouchPoint> paperPoints = toPaper(points, drawnOn.scrollY);
        String adopt = adoptBlank(paper, paperPoints, drawnOn);
        InkRenderer.InkStroke stroke = InkRenderer.strokeFrom(paperPoints);
        paper.appendStroke(stroke, adopt);
        history.record(InkHistory.Edit.of(InkHistory.Part.paperAdded(page, stroke)));
        historyChanged();
        invalidatePaper(drawnOn, paper);
        notifyChanged(page);
        if ((wasBlank || paper.sliceCount() > slicesBefore) && listener != null) {
            listener.onPageBecameNonEmpty(page);
        }
    }

    private void erasePaper(List<TouchPoint> eraserPath, InkViewport view) {
        NotebookPaper paper = view.page(0).paper;
        int index = view.pageIndexAt(eraserPath.get(0).y);
        Board page = view.page(index < 0 ? 0 : index);
        List<TouchPoint> path = toPaper(eraserPath, view.scrollY);
        ArrayList<InkRenderer.InkStroke> removed = new ArrayList<>();
        for (InkRenderer.InkStroke stroke : new ArrayList<>(paper.strokes())) {
            if (InkRenderer.hits(stroke, path)) {
                removed.add(stroke);
            }
        }
        if (removed.isEmpty()) {
            return;
        }
        ArrayList<String> ids = new ArrayList<>();
        for (InkRenderer.InkStroke stroke : removed) {
            ids.add(stroke.id);
        }
        paper.deleteIds(ids);
        history.record(InkHistory.Edit.of(InkHistory.Part.paperRemoved(page, removed)));
        historyChanged();
        invalidatePaper(view, paper);
        redrawAll();
        notifyChanged(page);
    }

    private void finishPaperLasso(List<TouchPoint> outline, InkViewport drawnOn) {
        List<TouchPoint> paperOutline = toPaper(outline, drawnOn.scrollY);
        NotebookPaper paper = drawnOn.page(0).paper;
        main.post(() -> selectOnPaper(paper, paperOutline));
    }

    private void selectOnPaper(NotebookPaper paper, List<TouchPoint> outline) {
        if (selection != null || shown.isEmpty() || shown.page(0).paper != paper) {
            reportLasso(0);
            return;
        }
        List<InkRenderer.InkStroke> picked = Lasso.select(paper.strokes(), outline);
        if (picked.isEmpty()) {
            reportLasso(0);
            return;
        }
        reportLasso(beginPaperSelection(shown.page(0), picked) ? picked.size() : 0);
    }

    private boolean beginPaperSelection(Board page, List<InkRenderer.InkStroke> picked) {
        int width = surfaceView.getWidth();
        if (width <= 0 || page.paper == null) {
            return false;
        }
        cancelResume();
        Set<String> ids = Lasso.idsOf(picked);
        RectF bounds = Lasso.boundsOf(picked);
        Rect spriteRect = new Rect();
        bounds.roundOut(spriteRect);
        Bitmap sprite = Bitmap.createBitmap(Math.max(1, spriteRect.width()), Math.max(1, spriteRect.height()),
                Bitmap.Config.ARGB_8888);
        Canvas spriteCanvas = new Canvas(sprite);
        spriteCanvas.translate(-spriteRect.left, -spriteRect.top);
        InkRenderer.drawAll(spriteCanvas, paint, picked);
        HashMap<String, Bitmap> bases = new HashMap<>();
        for (int i = 0; i < shown.layout.size(); i++) {
            Board slice = shown.page(i);
            bases.put(slice.id, renderSlice(slice, width, shown.layout.heights[i], ids));
        }
        selection = new Selection(page, 0, ids, new RectF(spriteRect),
                bases.get(page.id), sprite);
        selection.acrossPaper = true;
        selection.sliceBases = bases;
        setPenState(EpdPenManager.PEN_PAUSE);
        requestFrame(true, null, UpdateMode.GC);
        return true;
    }

    private void commitPaperSelection(Selection s) {
        removeCallbacks(dragFrame);
        RectF dirty = touchedArea(s);
        float dx = s.dx;
        float dy = s.dy;
        NotebookPaper paper = s.page.paper;
        Set<String> ids = s.ids;
        scheduleResume();
        endSelection();
        requestFrame(false, dirty, UpdateMode.GC);
        if (paper == null || (dx == 0f && dy == 0f)) {
            if (listener != null) {
                listener.onLassoCancelled();
            }
            return;
        }
        float bottom = 0f;
        for (InkRenderer.InkStroke stroke : paper.strokes()) {
            if (ids.contains(stroke.id)) {
                bottom = Math.max(bottom, stroke.bounds.bottom + dy);
            }
        }
        String adopt = null;
        if (bottom >= paper.origin(paper.sliceCount()) && !shown.isEmpty()) {
            Board tail = shown.page(shown.layout.size() - 1);
            if (tail.sliceIndex >= paper.sliceCount()) {
                adopt = tail.id;
            }
        }
        paper.translate(ids, dx, dy, adopt);
        invalidatePaper(shown, paper);
        history.record(InkHistory.Edit.of(InkHistory.Part.paperShifted(s.page, ids, dx, dy)));
        historyChanged();
        notifyChanged(s.page);
        if (listener != null) {
            listener.onLassoMoved(new Lasso.Move(s.page, Map.of(), Map.of(), dx, dy));
        }
    }

    /** The unsaved blank at the end of the stack, when this stroke grows into it. */
    private static String adoptBlank(NotebookPaper paper, List<TouchPoint> paperPoints, InkViewport drawnOn) {
        float bottom = 0f;
        for (TouchPoint point : paperPoints) {
            bottom = Math.max(bottom, point.y);
        }
        if (bottom < paper.origin(paper.sliceCount()) || drawnOn.layout.size() == 0) {
            return null;
        }
        Board tail = drawnOn.page(drawnOn.layout.size() - 1);
        return tail.sliceIndex >= paper.sliceCount() ? tail.id : null;
    }

    private void invalidatePaper(InkViewport view, NotebookPaper paper) {
        for (int i = 0; i < view.layout.size(); i++) {
            Board each = view.page(i);
            if (each.paper == paper) {
                invalidatePage(each.id);
            }
        }
    }

    /** One slice of the paper, drawn in the slice's own bitmap. */
    private Bitmap renderSlice(Board page, int width, int height, Set<String> exclude) {
        paintedPageIds.add(page.id);
        Bitmap bitmap = Bitmap.createBitmap(Math.max(1, width), Math.max(1, height), Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.WHITE);
        if (page.paper == null) {
            return bitmap;
        }
        Canvas canvas = new Canvas(bitmap);
        canvas.save();
        canvas.clipRect(0, 0, width, height);
        canvas.translate(0f, -page.paperOrigin);
        ArrayList<InkRenderer.InkStroke> rest = new ArrayList<>();
        for (InkRenderer.InkStroke stroke : page.paper.touching(page.sliceIndex)) {
            if (exclude == null || !exclude.contains(stroke.id)) {
                rest.add(stroke);
            }
        }
        InkRenderer.drawAll(canvas, paint, rest);
        canvas.restore();
        return bitmap;
    }

    private static final class Selection {
        final Board page;
        final int index;
        final Set<String> ids;
        /** Sprite position on the page before the drag. */
        final RectF bounds;
        final Bitmap base;
        final Bitmap sprite;
        float dx;
        float dy;
        /** Selection lives on the notebook paper, so the drag is one shift across slices. */
        boolean acrossPaper;
        Map<String, Bitmap> sliceBases;
        boolean sawDown;
        boolean dragging;
        float downX;
        float downY;
        RectF drawnBox;

        Selection(Board page, int index, Set<String> ids, RectF bounds, Bitmap base, Bitmap sprite) {
            this.page = page;
            this.index = index;
            this.ids = ids;
            this.bounds = bounds;
            this.base = base;
            this.sprite = sprite;
        }
    }

    private void resumeScribble() {
        syncRaw();
    }

    private void updateExcludeRects() {
        excludeRects.clear();
        excludeRects.addAll(extraExcludeRects);
        syncRaw();
    }

    private Rect canvasLimit() {
        Rect limit = new Rect();
        surfaceView.getLocalVisibleRect(limit);
        if (limit.isEmpty()) {
            limit.set(0, 0, Math.max(surfaceView.getWidth(), 1), Math.max(surfaceView.getHeight(), 1));
        }
        return limit;
    }
}
