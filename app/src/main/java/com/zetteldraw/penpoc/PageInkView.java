package com.zetteldraw.penpoc;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;

import com.onyx.android.sdk.api.device.epd.EpdController;
import com.onyx.android.sdk.api.device.epd.UpdateMode;
import com.onyx.android.sdk.data.note.TouchPoint;
import com.onyx.android.sdk.pen.RawInputCallback;
import com.onyx.android.sdk.pen.TouchHelper;
import com.onyx.android.sdk.pen.data.TouchPointList;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Live ink for a vertical stack of pages. One SurfaceView + TouchHelper
 * covers the drawing area; it paints whichever pages are scrolled into view.
 * Page i starts at content y = i * pageStride. Strokes are stored in
 * page-local coordinates.
 */
final class PageInkView extends FrameLayout {
    interface Listener {
        void onPageChanged(Board page);

        void onPageBecameNonEmpty(Board page);
    }

    private static final int BITMAP_CACHE_SIZE = 3;

    private final SurfaceView surfaceView;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ArrayList<Rect> excludeRects = new ArrayList<>();
    private final ArrayList<Board> pages = new ArrayList<>();
    private final LinkedHashMap<String, Bitmap> bitmaps = new LinkedHashMap<>(8, 0.75f, true);
    private TouchHelper touchHelper;
    private Listener listener;
    private int pageHeight = 1;
    private int pageStride = 1;
    private int scrollY;
    private boolean live;
    private boolean held;
    private boolean eraserMode;
    private boolean erasingStroke;
    private List<Rect> extraExcludeRects = new ArrayList<>();

    PageInkView(Context context) {
        super(context);
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(Color.BLACK);
        paint.setStrokeWidth(InkRenderer.BASE_WIDTH_PX);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
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

    void setPages(List<Board> next, int pageHeight, int pageStride) {
        pages.clear();
        pages.addAll(next);
        this.pageHeight = Math.max(1, pageHeight);
        this.pageStride = Math.max(this.pageHeight, pageStride);
        Iterator<Map.Entry<String, Bitmap>> it = bitmaps.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Bitmap> entry = it.next();
            if (indexOfPage(entry.getKey()) < 0 || entry.getValue().getHeight() != this.pageHeight) {
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
        blit(null, false);
        resumeScribble();
    }

    void setExtraExcludeRects(List<Rect> rects) {
        extraExcludeRects = rects == null ? new ArrayList<>() : rects;
        updateExcludeRects();
    }

    void setEraserMode(boolean on) {
        eraserMode = on;
        if (touchHelper != null) {
            applyLiveInk();
        }
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
                blit(null, false);
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
        if (touchHelper != null) {
            touchHelper.setRawDrawingEnabled(false);
        }
    }

    void resumeLive() {
        resumeScribble();
    }

    void close() {
        closeRawDrawing();
        for (Bitmap bitmap : bitmaps.values()) {
            bitmap.recycle();
        }
        bitmaps.clear();
    }

    private final SurfaceHolder.Callback surfaceCallback = new SurfaceHolder.Callback() {
        @Override
        public void surfaceCreated(SurfaceHolder holder) {
            blit(null, false);
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

    private boolean canInk() {
        return live && !held && !pages.isEmpty();
    }

    private void applyLiveInk() {
        if (touchHelper == null) {
            return;
        }
        touchHelper.setStrokeStyle(TouchHelper.STROKE_STYLE_FOUNTAIN);
        touchHelper.setStrokeWidth(InkRenderer.BASE_WIDTH_PX);
        touchHelper.setStrokeColor(Color.BLACK);
        touchHelper.setBrushRawDrawingEnabled(!eraserMode);
        touchHelper.setRawDrawingRenderEnabled(!eraserMode);
    }

    private final RawInputCallback rawInputCallback = new RawInputCallback() {
        @Override
        public void onBeginRawDrawing(boolean shortcutErase, TouchPoint point) {
            erasingStroke = eraserMode || shortcutErase;
            if (erasingStroke && touchHelper != null) {
                touchHelper.setRawDrawingRenderEnabled(false);
            }
        }

        @Override
        public void onEndRawDrawing(boolean shortcutErase, TouchPoint point) {
            erasingStroke = eraserMode || shortcutErase;
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
            if (erasingStroke || eraserMode) {
                eraseStrokes(points);
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
                blit(refreshRect, true);
                applyLiveInk();
                erasingStroke = eraserMode;
            });
        }
    };

    private void addStroke(List<TouchPoint> points) {
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
        float dy = scrollY - (float) index * pageStride;
        for (TouchPoint point : copy) {
            point.y += dy;
        }
        return copy;
    }

    private int pageIndexAt(float surfaceY) {
        if (pages.isEmpty()) {
            return -1;
        }
        int index = (int) Math.floor((surfaceY + scrollY) / pageStride);
        return index < 0 || index >= pages.size() ? -1 : index;
    }

    private int firstVisible() {
        return Math.min(pages.size(), Math.max(0, scrollY / pageStride));
    }

    private int lastVisible() {
        int bottom = scrollY + Math.max(1, surfaceView.getHeight());
        return Math.min(pages.size() - 1, bottom / pageStride);
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

    private Bitmap bitmapFor(Board page) {
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
        bitmap = Bitmap.createBitmap(width, pageHeight, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.WHITE);
        Canvas canvas = new Canvas(bitmap);
        for (InkRenderer.InkStroke stroke : page.strokes) {
            InkRenderer.draw(canvas, paint, stroke);
        }
        bitmaps.put(page.id, bitmap);
        return bitmap;
    }

    private void blit(RectF refreshRect, boolean handwritingMode) {
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
        if (handwritingMode) {
            EpdController.setViewDefaultUpdateMode(surfaceView, UpdateMode.HAND_WRITING_REPAINT_MODE);
        }
        Canvas canvas = holder.lockCanvas(renderRect);
        if (canvas == null) {
            if (handwritingMode) {
                EpdController.resetViewUpdateMode(surfaceView);
            }
            return;
        }
        try {
            canvas.drawColor(Color.WHITE);
            int last = lastVisible();
            for (int index = firstVisible(); index <= last; index++) {
                Bitmap bitmap = bitmapFor(pages.get(index));
                if (bitmap != null) {
                    canvas.drawBitmap(bitmap, 0f, (float) index * pageStride - scrollY, null);
                }
            }
        } finally {
            holder.unlockCanvasAndPost(canvas);
            if (handwritingMode) {
                EpdController.resetViewUpdateMode(surfaceView);
            }
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
        touchHelper.setRawDrawingEnabled(canInk());
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
