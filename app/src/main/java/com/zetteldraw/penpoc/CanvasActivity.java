package com.zetteldraw.penpoc;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Bundle;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;

import com.onyx.android.sdk.api.device.epd.EpdController;
import com.onyx.android.sdk.api.device.epd.UpdateMode;
import com.onyx.android.sdk.data.note.TouchPoint;
import com.onyx.android.sdk.pen.RawInputCallback;
import com.onyx.android.sdk.pen.TouchHelper;
import com.onyx.android.sdk.pen.data.TouchPointList;

import java.util.ArrayList;
import java.util.List;

/**
 * Full-screen stylus canvas using Onyx {@link TouchHelper} raw/scribble drawing.
 * Live ink is rendered by the e-ink controller, not {@code View.onDraw}.
 */
public final class CanvasActivity extends Activity {
    private static final float STROKE_WIDTH = 3.0f;

    private SurfaceView surfaceView;
    private TouchHelper touchHelper;
    private Bitmap bitmap;
    private Canvas bitmapCanvas;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(Color.BLACK);
        paint.setStrokeWidth(STROKE_WIDTH);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);

        surfaceView = new SurfaceView(this);
        surfaceView.setOnTouchListener((View v, android.view.MotionEvent event) -> true);
        setContentView(surfaceView);
        surfaceView.getHolder().addCallback(surfaceCallback);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (touchHelper != null) {
            touchHelper.setRawDrawingEnabled(true);
        }
    }

    @Override
    protected void onPause() {
        if (touchHelper != null) {
            touchHelper.setRawDrawingEnabled(false);
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (touchHelper != null) {
            touchHelper.closeRawDrawing();
            touchHelper = null;
        }
        recycleBitmap();
        super.onDestroy();
    }

    private final SurfaceHolder.Callback surfaceCallback = new SurfaceHolder.Callback() {
        @Override
        public void surfaceCreated(SurfaceHolder holder) {
            fillWhite();
            restoreBitmap();
            openRawDrawing();
        }

        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            Rect limit = new Rect();
            surfaceView.getLocalVisibleRect(limit);
            if (touchHelper != null && !limit.isEmpty()) {
                touchHelper.setLimitRect(limit, new ArrayList<>());
            }
        }

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
            if (touchHelper != null) {
                touchHelper.closeRawDrawing();
                touchHelper = null;
            }
        }
    };

    private void openRawDrawing() {
        if (touchHelper != null) {
            touchHelper.closeRawDrawing();
        }
        Rect limit = new Rect();
        surfaceView.getLocalVisibleRect(limit);
        if (limit.isEmpty()) {
            limit.set(0, 0, surfaceView.getWidth(), surfaceView.getHeight());
        }
        touchHelper = TouchHelper.create(surfaceView, rawInputCallback);
        touchHelper.setStrokeWidth(STROKE_WIDTH)
                .setLimitRect(limit, new ArrayList<>())
                .openRawDrawing();
        touchHelper.setStrokeStyle(TouchHelper.STROKE_STYLE_PENCIL);
        touchHelper.setStrokeColor(Color.BLACK);
        touchHelper.setRawDrawingRenderEnabled(true);
        touchHelper.setPenUpRefreshEnabled(true);
        touchHelper.enableFingerTouch(false);
        touchHelper.setRawDrawingEnabled(true);
    }

    private final RawInputCallback rawInputCallback = new RawInputCallback() {
        @Override
        public void onBeginRawDrawing(boolean shortcutErase, TouchPoint point) {
        }

        @Override
        public void onEndRawDrawing(boolean shortcutErase, TouchPoint point) {
        }

        @Override
        public void onRawDrawingTouchPointMoveReceived(TouchPoint point) {
        }

        @Override
        public void onRawDrawingTouchPointListReceived(TouchPointList touchPointList) {
            if (touchPointList == null) {
                return;
            }
            drawStrokeToBitmap(touchPointList.getPoints());
        }

        @Override
        public void onBeginRawErasing(boolean shortcutErase, TouchPoint point) {
        }

        @Override
        public void onEndRawErasing(boolean shortcutErase, TouchPoint point) {
        }

        @Override
        public void onRawErasingTouchPointMoveReceived(TouchPoint point) {
        }

        @Override
        public void onRawErasingTouchPointListReceived(TouchPointList touchPointList) {
        }

        @Override
        public void onPenUpRefresh(RectF refreshRect) {
            surfaceView.post(() -> blitCompletedStroke(refreshRect));
        }
    };

    private void drawStrokeToBitmap(List<TouchPoint> points) {
        if (points == null || points.isEmpty()) {
            return;
        }
        ensureBitmap();
        Path path = new Path();
        PointF previous = new PointF(points.get(0).x, points.get(0).y);
        path.moveTo(previous.x, previous.y);
        for (TouchPoint point : points) {
            path.quadTo(previous.x, previous.y, point.x, point.y);
            previous.set(point.x, point.y);
        }
        bitmapCanvas.drawPath(path, paint);
    }

    private void blitCompletedStroke(RectF refreshRect) {
        if (bitmap == null || surfaceView.getHolder() == null) {
            return;
        }
        Rect renderRect = new Rect();
        if (refreshRect != null && !refreshRect.isEmpty()) {
            refreshRect.roundOut(renderRect);
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
        if (bitmap == null) {
            return;
        }
        Canvas canvas = surfaceView.getHolder().lockCanvas();
        if (canvas == null) {
            return;
        }
        canvas.drawColor(Color.WHITE);
        canvas.drawBitmap(bitmap, 0f, 0f, null);
        surfaceView.getHolder().unlockCanvasAndPost(canvas);
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
    }

    private void recycleBitmap() {
        if (bitmap != null) {
            bitmap.recycle();
            bitmap = null;
            bitmapCanvas = null;
        }
    }
}
