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
import java.util.List;

/**
 * One board's live ink. TouchHelper raw/scribble stays on this surface
 * while the board is the one being drawn.
 */
final class BoardView extends FrameLayout {
    interface Listener {
        void onBoardChanged(Board board);

        void onBecameNonEmpty(Board board);
    }

    private final SurfaceView surfaceView;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ArrayList<Rect> excludeRects = new ArrayList<>();
    private TouchHelper touchHelper;
    private Bitmap bitmap;
    private Canvas bitmapCanvas;
    private Board board = Board.blank();
    private Listener listener;
    private View fingerPassthrough;
    private boolean live;
    private boolean eraserMode;
    private boolean erasingStroke;
    private List<Rect> extraExcludeRects = new ArrayList<>();

    BoardView(Context context) {
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

    void setFingerPassthrough(View target) {
        fingerPassthrough = target;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (fingerPassthrough != null && !isStylus(ev)) {
            return fingerPassthrough.dispatchTouchEvent(ev);
        }
        return super.dispatchTouchEvent(ev);
    }

    private static boolean isStylus(MotionEvent event) {
        int tool = event.getToolType(0);
        return tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER;
    }

    Board getBoard() {
        return board;
    }

    void bind(Board board) {
        this.board = board == null ? Board.blank() : board;
        paint.setStrokeWidth(InkRenderer.BASE_WIDTH_PX);
        if (live) {
            pauseScribble();
        }
        rebuildBitmap();
        if (live && surfaceView.getHolder().getSurface().isValid()) {
            restoreBitmap();
            resumeScribble();
        }
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

    void wipe() {
        board.strokes.clear();
        ensureBitmap();
        if (bitmap != null) {
            bitmap.eraseColor(Color.WHITE);
        }
        pauseScribble();
        fillWhite();
        restoreBitmap();
        resumeScribble();
        notifyChanged();
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
                ensureBitmap();
                rebuildBitmap();
                restoreBitmap();
                openRawDrawing();
            }
        } else {
            closeRawDrawing();
        }
    }

    void pauseLive() {
        if (touchHelper != null) {
            touchHelper.setRawDrawingEnabled(false);
        }
    }

    void resumeLive() {
        if (live && touchHelper != null) {
            applyLiveInk();
            touchHelper.setRawDrawingEnabled(true);
        }
    }

    void close() {
        closeRawDrawing();
        recycleBitmap();
    }

    private final SurfaceHolder.Callback surfaceCallback = new SurfaceHolder.Callback() {
        @Override
        public void surfaceCreated(SurfaceHolder holder) {
            ensureBitmap();
            rebuildBitmap();
            fillWhite();
            restoreBitmap();
            if (live) {
                openRawDrawing();
            }
        }

        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            ensureBitmap();
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
        touchHelper.setRawDrawingEnabled(true);
    }

    private void closeRawDrawing() {
        if (touchHelper != null) {
            touchHelper.closeRawDrawing();
            touchHelper = null;
        }
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
                blit(refreshRect);
                applyLiveInk();
                erasingStroke = eraserMode;
            });
        }
    };

    private void addStroke(List<TouchPoint> points) {
        if (points == null || points.isEmpty()) {
            return;
        }
        boolean wasBlank = board.isBlank();
        InkRenderer.InkStroke stroke = InkRenderer.strokeFrom(points);
        board.strokes.add(stroke);
        ensureBitmap();
        InkRenderer.draw(bitmapCanvas, paint, stroke);
        notifyChanged();
        if (wasBlank && listener != null) {
            listener.onBecameNonEmpty(board);
        }
    }

    private void eraseStrokes(List<TouchPoint> eraserPath) {
        if (eraserPath == null || eraserPath.isEmpty() || board.strokes.isEmpty()) {
            return;
        }
        ArrayList<TouchPoint> path = InkRenderer.copyPoints(eraserPath);
        boolean removed = false;
        Iterator<InkRenderer.InkStroke> iterator = board.strokes.iterator();
        while (iterator.hasNext()) {
            if (InkRenderer.hits(iterator.next(), path)) {
                iterator.remove();
                removed = true;
            }
        }
        if (removed) {
            rebuildBitmap();
            pauseScribble();
            blit(null);
            resumeScribble();
            notifyChanged();
        }
    }

    private void notifyChanged() {
        if (listener != null) {
            listener.onBoardChanged(board);
        }
    }

    private void rebuildBitmap() {
        ensureBitmap();
        if (bitmap == null) {
            return;
        }
        bitmap.eraseColor(Color.WHITE);
        for (InkRenderer.InkStroke stroke : board.strokes) {
            InkRenderer.draw(bitmapCanvas, paint, stroke);
        }
    }

    private void blit(RectF refreshRect) {
        if (bitmap == null || surfaceView.getHolder() == null) {
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
        EpdController.setViewDefaultUpdateMode(surfaceView, UpdateMode.HAND_WRITING_REPAINT_MODE);
        Canvas canvas = surfaceView.getHolder().lockCanvas(renderRect);
        if (canvas == null) {
            EpdController.resetViewUpdateMode(surfaceView);
            return;
        }
        try {
            canvas.drawBitmap(bitmap, 0f, 0f, null);
        } finally {
            surfaceView.getHolder().unlockCanvasAndPost(canvas);
            EpdController.resetViewUpdateMode(surfaceView);
        }
    }

    private void fillWhite() {
        Canvas canvas = surfaceView.getHolder().lockCanvas();
        if (canvas == null) {
            return;
        }
        canvas.drawColor(Color.WHITE);
        surfaceView.getHolder().unlockCanvasAndPost(canvas);
    }

    private void restoreBitmap() {
        Canvas canvas = surfaceView.getHolder().lockCanvas();
        if (canvas == null) {
            return;
        }
        canvas.drawColor(Color.WHITE);
        if (bitmap != null) {
            canvas.drawBitmap(bitmap, 0f, 0f, null);
        }
        surfaceView.getHolder().unlockCanvasAndPost(canvas);
    }

    private void pauseScribble() {
        if (touchHelper == null) {
            return;
        }
        touchHelper.setRawDrawingEnabled(false);
        touchHelper.setRawDrawingRenderEnabled(false);
    }

    private void resumeScribble() {
        if (!live || touchHelper == null) {
            return;
        }
        applyLiveInk();
        touchHelper.setRawDrawingEnabled(true);
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

    private void ensureBitmap() {
        int width = surfaceView.getWidth();
        int height = surfaceView.getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }
        if (bitmap != null && bitmap.getWidth() == width && bitmap.getHeight() == height) {
            return;
        }
        recycleBitmap();
        bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.WHITE);
        bitmapCanvas = new Canvas(bitmap);
        for (InkRenderer.InkStroke stroke : board.strokes) {
            InkRenderer.draw(bitmapCanvas, paint, stroke);
        }
    }

    private void recycleBitmap() {
        if (bitmap != null) {
            bitmap.recycle();
            bitmap = null;
            bitmapCanvas = null;
        }
    }
}
