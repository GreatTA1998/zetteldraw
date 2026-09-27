package com.zetteldraw.penpoc;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;

import com.onyx.android.sdk.api.device.epd.EpdController;
import com.onyx.android.sdk.api.device.epd.UpdateMode;
import com.onyx.android.sdk.data.note.TouchPoint;
import com.onyx.android.sdk.pen.EpdPenManager;
import com.onyx.android.sdk.pen.RawInputCallback;
import com.onyx.android.sdk.pen.TouchHelper;
import com.onyx.android.sdk.pen.data.TouchPointList;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Live ink for a vertical stack of pages. One SurfaceView + TouchHelper
 * covers the drawing area; it paints whichever pages are scrolled into view.
 * Pages stack top to bottom, each with its own height and a fixed gap
 * below it. Strokes are stored in page-local coordinates.
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
    }

    enum Tool { PEN, ERASER, LASSO }

    private static final int BITMAP_CACHE_SIZE = 3;
    /** Stock-app resume delay after a lasso on colour devices; shorter ones drop the first stroke. */
    private static final long LASSO_RESUME_MS = 500;
    private static final long DRAG_FRAME_MS = 40;

    private final SurfaceView surfaceView;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ArrayList<Rect> excludeRects = new ArrayList<>();
    private final ArrayList<Board> pages = new ArrayList<>();
    private final LinkedHashMap<String, Bitmap> bitmaps = new LinkedHashMap<>(8, 0.75f, true);
    private TouchHelper touchHelper;
    private Listener listener;
    /** Content y of each page's top edge, and each page's height. */
    private int[] tops = new int[0];
    private int[] heights = new int[0];
    private int pageGap;
    private int scrollY;
    private boolean live;
    private boolean held;
    private boolean paused;
    private Tool tool = Tool.PEN;
    private boolean erasingStroke;
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
        if (selection != null) {
            endSelection();
            scheduleResume();
            if (listener != null) {
                listener.onLassoCancelled();
            }
        }
        pages.clear();
        pages.addAll(next);
        pageGap = Math.max(0, gap);
        tops = new int[pages.size()];
        heights = new int[pages.size()];
        int y = 0;
        for (int i = 0; i < pages.size(); i++) {
            tops[i] = y;
            heights[i] = Math.max(1, pageHeights[i]);
            y += heights[i] + pageGap;
        }
        Iterator<Map.Entry<String, Bitmap>> it = bitmaps.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Bitmap> entry = it.next();
            int index = indexOfPage(entry.getKey());
            if (index < 0 || entry.getValue().getHeight() != heights[index]) {
                entry.getValue().recycle();
                it.remove();
            }
        }
        redrawAll();
    }

    void setContentScrollY(int y) {
        scrollY = Math.max(0, y);
        redrawAll();
    }

    void invalidatePage(String pageId) {
        Bitmap bitmap = bitmaps.remove(pageId);
        if (bitmap != null) {
            bitmap.recycle();
        }
    }

    void redrawAll() {
        pauseScribble();
        blit(null, null);
        resumeScribble();
    }

    void setExtraExcludeRects(List<Rect> rects) {
        extraExcludeRects = rects == null ? new ArrayList<>() : rects;
        updateExcludeRects();
    }

    void setTool(Tool next) {
        if (selection != null && next != Tool.LASSO) {
            cancelSelection();
        }
        tool = next;
        if (touchHelper != null) {
            applyLiveInk();
        }
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

    /** Puts a lasso move back. Returns false when none of its strokes are left. */
    boolean undo(Lasso.Move move) {
        if (move == null) {
            return false;
        }
        if (selection != null) {
            cancelSelection();
        }
        if (!move.undo()) {
            return false;
        }
        invalidatePage(move.page.id);
        redrawAll();
        notifyChanged(move.page);
        return true;
    }

    void setLive(boolean on) {
        if (live == on) {
            if (on) {
                resumeScribble();
            }
            return;
        }
        live = on;
        if (on) {
            if (surfaceView.getHolder().getSurface().isValid()) {
                blit(null, null);
                openRawDrawing();
            }
        } else {
            closeRawDrawing();
        }
    }

    /** Scrolling or a popup is up: stop raw ink until {@link #release()}. */
    void hold() {
        held = true;
        if (touchHelper != null) {
            touchHelper.setRawDrawingEnabled(false);
        }
    }

    void release() {
        held = false;
        resumeScribble();
    }

    void pauseLive() {
        paused = true;
        if (touchHelper != null) {
            touchHelper.setRawDrawingEnabled(false);
        }
    }

    void resumeLive() {
        paused = false;
        resumeScribble();
    }

    void close() {
        removeCallbacks(resumeAfterLasso);
        removeCallbacks(dragFrame);
        endSelection();
        closeRawDrawing();
        for (Bitmap bitmap : bitmaps.values()) {
            bitmap.recycle();
        }
        bitmaps.clear();
    }

    private final SurfaceHolder.Callback surfaceCallback = new SurfaceHolder.Callback() {
        @Override
        public void surfaceCreated(SurfaceHolder holder) {
            blit(null, null);
            if (live) {
                openRawDrawing();
            }
        }

        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            updateExcludeRects();
        }

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
            closeRawDrawing();
        }
    };

    private void openRawDrawing() {
        if (!live) {
            return;
        }
        if (touchHelper != null) {
            touchHelper.closeRawDrawing();
        }
        Rect limit = canvasLimit();
        touchHelper = TouchHelper.create(surfaceView, rawInputCallback);
        touchHelper.setStrokeWidth(InkRenderer.BASE_WIDTH_PX)
                .setStrokeColor(Color.BLACK)
                .setLimitRect(limit, excludeRects)
                .openRawDrawing();
        applyLiveInk();
        touchHelper.setPenUpRefreshEnabled(true);
        touchHelper.enableFingerTouch(false);
        touchHelper.enableSideBtnErase(true);
        touchHelper.setRawDrawingEnabled(canInk());
    }

    private void closeRawDrawing() {
        if (touchHelper != null) {
            touchHelper.closeRawDrawing();
            touchHelper = null;
        }
    }

    /** The single gate for raw drawing: every resume path asks this. */
    private boolean canInk() {
        return live && !held && !paused && !resumePending && selection == null && !pages.isEmpty();
    }

    private void applyLiveInk() {
        if (touchHelper == null) {
            return;
        }
        boolean lasso = tool == Tool.LASSO;
        boolean eraser = tool == Tool.ERASER;
        touchHelper.setStrokeStyle(lasso ? TouchHelper.STROKE_STYLE_DASH : TouchHelper.STROKE_STYLE_FOUNTAIN);
        touchHelper.setStrokeWidth(lasso ? Math.max(2f, InkRenderer.BASE_WIDTH_PX * 0.5f) : InkRenderer.BASE_WIDTH_PX);
        touchHelper.setStrokeColor(Color.BLACK);
        touchHelper.setBrushRawDrawingEnabled(!eraser);
        touchHelper.setRawDrawingRenderEnabled(!eraser);
    }

    private final RawInputCallback rawInputCallback = new RawInputCallback() {
        @Override
        public void onBeginRawDrawing(boolean shortcutErase, TouchPoint point) {
            erasingStroke = tool == Tool.ERASER || shortcutErase;
            if (erasingStroke && touchHelper != null) {
                touchHelper.setRawDrawingRenderEnabled(false);
            }
        }

        @Override
        public void onEndRawDrawing(boolean shortcutErase, TouchPoint point) {
            erasingStroke = tool == Tool.ERASER || shortcutErase;
        }

        @Override
        public void onRawDrawingTouchPointMoveReceived(TouchPoint point) {
        }

        @Override
        public void onRawDrawingTouchPointListReceived(TouchPointList touchPointList) {
            if (touchPointList == null) {
                return;
            }
            List<TouchPoint> points = touchPointList.getPoints();
            if (erasingStroke || tool == Tool.ERASER) {
                eraseStrokes(points);
            } else if (tool == Tool.LASSO) {
                ArrayList<TouchPoint> outline = InkRenderer.copyPoints(points);
                surfaceView.post(() -> finishLasso(outline));
            } else {
                addStroke(points);
            }
        }

        @Override
        public void onBeginRawErasing(boolean shortcutErase, TouchPoint point) {
            erasingStroke = true;
            if (touchHelper != null) {
                touchHelper.setRawDrawingRenderEnabled(false);
            }
        }

        @Override
        public void onEndRawErasing(boolean shortcutErase, TouchPoint point) {
            erasingStroke = true;
        }

        @Override
        public void onRawErasingTouchPointMoveReceived(TouchPoint point) {
        }

        @Override
        public void onRawErasingTouchPointListReceived(TouchPointList touchPointList) {
            if (touchPointList == null) {
                return;
            }
            eraseStrokes(touchPointList.getPoints());
        }

        @Override
        public void onPenUpRefresh(RectF refreshRect) {
            surfaceView.post(() -> {
                blit(refreshRect, UpdateMode.HAND_WRITING_REPAINT_MODE);
                applyLiveInk();
                erasingStroke = tool == Tool.ERASER;
            });
        }
    };

    /** {@code points} are in surface coordinates, as TouchHelper reports them. */
    void addStroke(List<TouchPoint> points) {
        if (points == null || points.isEmpty()) {
            return;
        }
        int index = pageIndexAt(points.get(0).y);
        if (index < 0) {
            return;
        }
        Board page = pages.get(index);
        boolean wasBlank = page.isBlank();
        InkRenderer.InkStroke stroke = InkRenderer.strokeFrom(toPage(points, index));
        page.strokes.add(stroke);
        Bitmap cached = bitmaps.get(page.id);
        if (cached != null) {
            InkRenderer.draw(new Canvas(cached), paint, stroke);
        }
        notifyChanged(page);
        if (wasBlank && listener != null) {
            listener.onPageBecameNonEmpty(page);
        }
    }

    private void eraseStrokes(List<TouchPoint> eraserPath) {
        if (eraserPath == null || eraserPath.isEmpty()) {
            return;
        }
        ArrayList<Board> changed = new ArrayList<>();
        for (int index = firstVisible(); index <= lastVisible(); index++) {
            Board page = pages.get(index);
            if (page.strokes.isEmpty()) {
                continue;
            }
            List<TouchPoint> path = toPage(eraserPath, index);
            boolean removed = false;
            Iterator<InkRenderer.InkStroke> iterator = page.strokes.iterator();
            while (iterator.hasNext()) {
                if (InkRenderer.hits(iterator.next(), path)) {
                    iterator.remove();
                    removed = true;
                }
            }
            if (removed) {
                invalidatePage(page.id);
                changed.add(page);
            }
        }
        if (!changed.isEmpty()) {
            redrawAll();
            for (Board page : changed) {
                notifyChanged(page);
            }
        }
    }

    private List<TouchPoint> toPage(List<TouchPoint> surfacePoints, int index) {
        ArrayList<TouchPoint> copy = InkRenderer.copyPoints(surfacePoints);
        float dy = scrollY - (float) tops[index];
        for (TouchPoint point : copy) {
            point.y += dy;
        }
        return copy;
    }

    private int pageIndexAt(float surfaceY) {
        if (pages.isEmpty()) {
            return -1;
        }
        float y = surfaceY + scrollY;
        if (y < 0) {
            return -1;
        }
        int index = 0;
        while (index + 1 < pages.size() && tops[index + 1] <= y) {
            index++;
        }
        return y < tops[index] + heights[index] + pageGap ? index : -1;
    }

    private int firstVisible() {
        int index = 0;
        while (index < pages.size() && tops[index] + heights[index] + pageGap <= scrollY) {
            index++;
        }
        return index;
    }

    private int lastVisible() {
        int bottom = scrollY + Math.max(1, surfaceView.getHeight());
        int index = pages.size() - 1;
        while (index > 0 && tops[index] > bottom) {
            index--;
        }
        return index;
    }

    private int indexOfPage(String id) {
        for (int i = 0; i < pages.size(); i++) {
            if (pages.get(i).id.equals(id)) {
                return i;
            }
        }
        return -1;
    }

    private void notifyChanged(Board page) {
        if (listener != null) {
            listener.onPageChanged(page);
        }
    }

    private Bitmap bitmapFor(Board page, int height) {
        Bitmap bitmap = bitmaps.get(page.id);
        if (bitmap != null) {
            return bitmap;
        }
        int width = surfaceView.getWidth();
        if (width <= 0) {
            return null;
        }
        while (bitmaps.size() >= BITMAP_CACHE_SIZE) {
            Iterator<Map.Entry<String, Bitmap>> it = bitmaps.entrySet().iterator();
            it.next().getValue().recycle();
            it.remove();
        }
        bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.WHITE);
        Canvas canvas = new Canvas(bitmap);
        for (InkRenderer.InkStroke stroke : page.strokes) {
            InkRenderer.draw(canvas, paint, stroke);
        }
        bitmaps.put(page.id, bitmap);
        return bitmap;
    }

    /** Paints visible pages into {@code refreshRect} (whole surface if null), optionally in an EPD mode. */
    private void blit(RectF refreshRect, UpdateMode mode) {
        SurfaceHolder holder = surfaceView.getHolder();
        if (holder == null || !holder.getSurface().isValid()) {
            return;
        }
        Rect renderRect = new Rect();
        if (refreshRect != null && !refreshRect.isEmpty()) {
            refreshRect.roundOut(renderRect);
            int pad = (int) Math.ceil(InkRenderer.BASE_WIDTH_PX * 2f);
            renderRect.inset(-pad, -pad);
        } else {
            renderRect.set(0, 0, surfaceView.getWidth(), surfaceView.getHeight());
        }
        if (mode != null) {
            EpdController.setViewDefaultUpdateMode(surfaceView, mode);
        }
        Canvas canvas = holder.lockCanvas(renderRect);
        if (canvas == null) {
            if (mode != null) {
                EpdController.resetViewUpdateMode(surfaceView);
            }
            return;
        }
        try {
            canvas.drawColor(Color.WHITE);
            int last = lastVisible();
            for (int index = firstVisible(); index <= last; index++) {
                float top = (float) tops[index] - scrollY;
                if (selection != null && selection.index == index) {
                    drawSelection(canvas, top);
                    continue;
                }
                Bitmap bitmap = bitmapFor(pages.get(index), heights[index]);
                if (bitmap != null) {
                    canvas.drawBitmap(bitmap, 0f, top, null);
                }
            }
        } finally {
            holder.unlockCanvasAndPost(canvas);
            if (mode != null) {
                EpdController.resetViewUpdateMode(surfaceView);
            }
        }
    }

    // ---- Lasso ----

    /** {@code outline} is in surface coordinates, as TouchHelper reports it. */
    void finishLasso(List<TouchPoint> outline) {
        if (tool != Tool.LASSO || selection != null || outline.size() < 3) {
            reportLasso(0);
            return;
        }
        int index = pageIndexAt(outline.get(0).y);
        if (index < 0) {
            reportLasso(0);
            return;
        }
        Board page = pages.get(index);
        List<InkRenderer.InkStroke> picked = Lasso.select(page.strokes, toPage(outline, index));
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
        pauseScribble();
        setPenState(EpdPenManager.PEN_PAUSE);
        Set<String> ids = Lasso.idsOf(picked);
        RectF bounds = Lasso.boundsOf(picked);
        Rect spriteRect = new Rect();
        bounds.roundOut(spriteRect);

        Bitmap base = Bitmap.createBitmap(width, heights[index], Bitmap.Config.ARGB_8888);
        base.eraseColor(Color.WHITE);
        Canvas baseCanvas = new Canvas(base);
        for (InkRenderer.InkStroke stroke : page.strokes) {
            if (!ids.contains(stroke.id)) {
                InkRenderer.draw(baseCanvas, paint, stroke);
            }
        }
        Bitmap sprite = Bitmap.createBitmap(Math.max(1, spriteRect.width()), Math.max(1, spriteRect.height()),
                Bitmap.Config.ARGB_8888);
        Canvas spriteCanvas = new Canvas(sprite);
        spriteCanvas.translate(-spriteRect.left, -spriteRect.top);
        for (InkRenderer.InkStroke stroke : picked) {
            InkRenderer.draw(spriteCanvas, paint, stroke);
        }
        selection = new Selection(page, index, ids, new RectF(spriteRect), base, sprite);
        blit(null, UpdateMode.GC);
        return true;
    }

    private void drawSelection(Canvas canvas, float pageTop) {
        Selection s = selection;
        canvas.drawBitmap(s.base, 0f, pageTop, null);
        canvas.drawBitmap(s.sprite, s.bounds.left + s.dx, pageTop + s.bounds.top + s.dy, null);
        RectF box = boxOnSurface(s);
        canvas.drawRect(box, boxPaint);
    }

    /** Dashed box around the (possibly dragged) selection, in surface coordinates. */
    private RectF boxOnSurface(Selection s) {
        return boxOnSurface(s, s.dx, s.dy);
    }

    private RectF boxOnSurface(Selection s, float dx, float dy) {
        RectF box = new RectF(s.bounds);
        box.offset(dx, (float) tops[s.index] - scrollY + dy);
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
                if (s.dragging) {
                    EpdController.setViewDefaultUpdateMode(surfaceView, UpdateMode.ANIMATION_MONO);
                }
                break;
            }
            case MotionEvent.ACTION_MOVE:
                if (s.dragging) {
                    float[] offset = Lasso.clampOffset(s.bounds, x - s.downX, y - s.downY,
                            surfaceView.getWidth(), heights[s.index]);
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
        blit(dirty, null);
    }

    private void commitSelection() {
        Selection s = selection;
        removeCallbacks(dragFrame);
        EpdController.resetViewUpdateMode(surfaceView);
        RectF dirty = touchedArea(s);
        Lasso.Move move = Lasso.move(s.page, s.ids, s.dx, s.dy);
        endSelection();
        invalidatePage(s.page.id);
        blit(dirty, UpdateMode.GC);
        scheduleResume();
        if (move == null) {
            if (listener != null) {
                listener.onLassoCancelled();
            }
            return;
        }
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
        EpdController.resetViewUpdateMode(surfaceView);
        RectF dirty = touchedArea(s);
        endSelection();
        blit(dirty, UpdateMode.GC);
        scheduleResume();
        if (listener != null) {
            listener.onLassoCancelled();
        }
    }

    private void endSelection() {
        if (selection == null) {
            return;
        }
        selection.base.recycle();
        selection.sprite.recycle();
        selection = null;
    }

    private void scheduleResume() {
        removeCallbacks(resumeAfterLasso);
        resumePending = true;
        postDelayed(resumeAfterLasso, LASSO_RESUME_MS);
    }

    private void cancelResume() {
        removeCallbacks(resumeAfterLasso);
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
        try {
            EpdController.setScreenHandWritingPenState(surfaceView, state);
        } catch (RuntimeException | LinkageError ignored) {
            // Not an Onyx device.
        }
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

    private void pauseScribble() {
        if (touchHelper == null) {
            return;
        }
        touchHelper.setRawDrawingEnabled(false);
        touchHelper.setRawDrawingRenderEnabled(false);
    }

    private void resumeScribble() {
        if (touchHelper == null) {
            return;
        }
        applyLiveInk();
        boolean on = canInk();
        touchHelper.setRawDrawingEnabled(on);
        if (on) {
            // Enabling raw drawing resets the side-button eraser channel.
            touchHelper.enableSideBtnErase(true);
        }
    }

    private void updateExcludeRects() {
        excludeRects.clear();
        excludeRects.addAll(extraExcludeRects);
        if (touchHelper != null) {
            touchHelper.setLimitRect(canvasLimit(), excludeRects);
        }
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
