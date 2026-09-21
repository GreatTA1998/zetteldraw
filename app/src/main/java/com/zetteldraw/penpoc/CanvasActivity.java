package com.zetteldraw.penpoc;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

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
 * Full-screen stylus canvas using Onyx {@link TouchHelper} raw/scribble drawing.
 * Live ink is rendered by the e-ink controller, not {@code View.onDraw}.
 */
public final class CanvasActivity extends Activity {
    private SurfaceView surfaceView;
    private LinearLayout toolbar;
    private Button eraserButton;
    private Button wipeButton;
    private TouchHelper touchHelper;
    private Bitmap bitmap;
    private Canvas bitmapCanvas;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ArrayList<Rect> excludeRects = new ArrayList<>();
    private final ArrayList<InkRenderer.InkStroke> strokes = new ArrayList<>();
    private boolean eraserMode;
    private boolean erasingStroke;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(Color.BLACK);
        paint.setStrokeWidth(InkRenderer.BASE_WIDTH_PX);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);

        FrameLayout root = new FrameLayout(this);
        surfaceView = new SurfaceView(this);
        surfaceView.setOnTouchListener((View v, android.view.MotionEvent event) -> true);
        root.addView(surfaceView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        int pad = dp(6);
        toolbar.setPadding(pad, pad, pad, pad);
        eraserButton = tinyButton(getString(R.string.eraser));
        wipeButton = tinyButton(getString(R.string.wipe));
        eraserButton.setOnClickListener(v -> setEraserMode(!eraserMode));
        wipeButton.setOnClickListener(v -> wipePage());
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        toolbar.addView(eraserButton, btnLp);
        btnLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        btnLp.leftMargin = dp(6);
        toolbar.addView(wipeButton, btnLp);

        FrameLayout.LayoutParams barLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        barLp.gravity = Gravity.TOP | Gravity.END;
        barLp.topMargin = dp(8);
        barLp.rightMargin = dp(8);
        root.addView(toolbar, barLp);
        toolbar.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> updateExcludeRects());

        setContentView(root);
        setEraserMode(false);
        surfaceView.getHolder().addCallback(surfaceCallback);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (touchHelper != null) {
            applyLiveInk();
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
            ensureBitmap();
            rebuildBitmap();
            fillWhite();
            restoreBitmap();
            openRawDrawing();
        }

        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            ensureBitmap();
            updateExcludeRects();
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

    private void applyLiveInk() {
        if (touchHelper == null) {
            return;
        }
        // FOUNTAIN == EpdController.STROKE_STYLE_BRUSH: live width follows pressure.
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
        InkRenderer.InkStroke stroke = InkRenderer.strokeFrom(points);
        strokes.add(stroke);
        ensureBitmap();
        InkRenderer.draw(bitmapCanvas, paint, stroke);
    }

    private void eraseStrokes(List<TouchPoint> eraserPath) {
        if (eraserPath == null || eraserPath.isEmpty() || strokes.isEmpty()) {
            return;
        }
        ArrayList<TouchPoint> path = InkRenderer.copyPoints(eraserPath);
        boolean removed = false;
        Iterator<InkRenderer.InkStroke> iterator = strokes.iterator();
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
        }
    }

    private void wipePage() {
        strokes.clear();
        ensureBitmap();
        if (bitmap != null) {
            bitmap.eraseColor(Color.WHITE);
        }
        pauseScribble();
        fillWhite();
        restoreBitmap();
        resumeScribble();
    }

    private void setEraserMode(boolean on) {
        eraserMode = on;
        styleButton(eraserButton, on);
        if (touchHelper != null) {
            applyLiveInk();
        }
    }

    private void rebuildBitmap() {
        ensureBitmap();
        if (bitmap == null) {
            return;
        }
        bitmap.eraseColor(Color.WHITE);
        for (InkRenderer.InkStroke stroke : strokes) {
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
        if (touchHelper == null) {
            return;
        }
        applyLiveInk();
        touchHelper.setRawDrawingEnabled(true);
    }

    private void updateExcludeRects() {
        excludeRects.clear();
        Rect toolbarRect = viewRectOnSurface(toolbar);
        if (toolbarRect != null && !toolbarRect.isEmpty()) {
            toolbarRect.inset(-dp(4), -dp(4));
            excludeRects.add(toolbarRect);
        }
        if (touchHelper != null) {
            touchHelper.setLimitRect(canvasLimit(), excludeRects);
        }
    }

    private Rect viewRectOnSurface(View view) {
        if (view == null || view.getWidth() <= 0 || view.getHeight() <= 0) {
            return null;
        }
        int[] surfaceLoc = new int[2];
        int[] viewLoc = new int[2];
        surfaceView.getLocationOnScreen(surfaceLoc);
        view.getLocationOnScreen(viewLoc);
        int left = viewLoc[0] - surfaceLoc[0];
        int top = viewLoc[1] - surfaceLoc[1];
        return new Rect(left, top, left + view.getWidth(), top + view.getHeight());
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
        for (InkRenderer.InkStroke stroke : strokes) {
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

    private Button tinyButton(String label) {
        Button button = new Button(this, null, android.R.attr.borderlessButtonStyle);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        button.setMinHeight(0);
        button.setMinWidth(0);
        button.setMinimumHeight(dp(28));
        button.setMinimumWidth(0);
        button.setPadding(dp(10), dp(4), dp(10), dp(4));
        styleButton(button, false);
        return button;
    }

    private void styleButton(Button button, boolean selected) {
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(4));
        if (selected) {
            background.setColor(Color.BLACK);
            background.setStroke(dp(1), Color.BLACK);
            button.setTextColor(Color.WHITE);
        } else {
            background.setColor(Color.WHITE);
            background.setStroke(dp(1), Color.BLACK);
            button.setTextColor(Color.BLACK);
        }
        button.setBackground(background);
        button.setSelected(selected);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
