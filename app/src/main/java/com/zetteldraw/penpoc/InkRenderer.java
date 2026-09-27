package com.zetteldraw.penpoc;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.DisplayMetrics;
import android.util.TypedValue;

import com.onyx.android.sdk.api.device.epd.EpdController;
import com.onyx.android.sdk.data.note.TouchPoint;

import java.util.ArrayList;
import java.util.List;

/**
 * Variable-width freeze renderer: pressure when it varies, Excalidraw-like
 * thin–thick–thin end taper always. Live ink stays on TouchHelper; this only
 * paints the bitmap that replaces the scribble overlay on pen-up.
 */
public final class InkRenderer {
    /** Notes default pen line width: 0.50mm. */
    static final float BASE_WIDTH_MM = 0.50f;
    /**
     * Device pixels for {@link #BASE_WIDTH_MM}. Default is 0.50mm at 300 PPI
     * (Go 7 Color II). {@link #applyBaseWidthMm} overwrites this from DisplayMetrics.
     */
    static float BASE_WIDTH_PX = 5.91f;
    static final float ERASER_RADIUS_PX = 18f;

    private static final float TAPER_FRACTION = 0.14f;
    private static final float TAPER_MIN = 0.32f;
    private static final float PRESSURE_MIN_SCALE = 0.55f;
    private static final float PRESSURE_MAX_SCALE = 1.28f;
    private static final float PRESSURE_VARIATION_RATIO = 0.06f;

    private InkRenderer() {
    }

    static void applyBaseWidthMm(DisplayMetrics metrics) {
        BASE_WIDTH_PX = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_MM, BASE_WIDTH_MM, metrics);
    }

    public static InkStroke strokeFrom(List<TouchPoint> points) {
        ArrayList<TouchPoint> copy = copyPoints(points);
        float[] widths = widthsFor(copy);
        return new InkStroke(copy, widths);
    }

    static void draw(Canvas canvas, Paint paint, InkStroke stroke) {
        if (stroke == null || stroke.points.isEmpty()) {
            return;
        }
        List<TouchPoint> points = stroke.points;
        float[] widths = stroke.widths;
        if (points.size() == 1) {
            TouchPoint p = points.get(0);
            paint.setStrokeWidth(widths[0]);
            canvas.drawPoint(p.x, p.y, paint);
            return;
        }
        TouchPoint previous = points.get(0);
        float previousWidth = widths[0];
        for (int i = 1; i < points.size(); i++) {
            TouchPoint point = points.get(i);
            paint.setStrokeWidth((previousWidth + widths[i]) * 0.5f);
            canvas.drawLine(previous.x, previous.y, point.x, point.y, paint);
            previous = point;
            previousWidth = widths[i];
        }
    }

    static boolean hits(InkStroke stroke, List<TouchPoint> eraserPath) {
        if (stroke == null || eraserPath == null || eraserPath.isEmpty()) {
            return false;
        }
        float radius = Math.max(ERASER_RADIUS_PX, stroke.maxWidth * 0.5f + 8f);
        float radiusSq = radius * radius;
        RectF probe = new RectF(stroke.bounds);
        probe.inset(-radius, -radius);
        for (TouchPoint eraser : eraserPath) {
            if (!probe.contains(eraser.x, eraser.y)) {
                continue;
            }
            if (distanceToPolylineSq(stroke.points, eraser.x, eraser.y) <= radiusSq) {
                return true;
            }
        }
        return false;
    }

    static RectF boundsOf(List<TouchPoint> points, float pad) {
        RectF bounds = new RectF();
        if (points == null || points.isEmpty()) {
            return bounds;
        }
        TouchPoint first = points.get(0);
        bounds.set(first.x, first.y, first.x, first.y);
        for (int i = 1; i < points.size(); i++) {
            TouchPoint p = points.get(i);
            bounds.union(p.x, p.y);
        }
        bounds.inset(-pad, -pad);
        return bounds;
    }

    static ArrayList<TouchPoint> copyPoints(List<TouchPoint> points) {
        ArrayList<TouchPoint> copy = new ArrayList<>(points == null ? 0 : points.size());
        if (points == null) {
            return copy;
        }
        for (TouchPoint point : points) {
            if (point != null) {
                copy.add(new TouchPoint(point));
            }
        }
        return copy;
    }

    static float[] widthsFor(List<TouchPoint> points) {
        int n = points.size();
        float[] widths = new float[n];
        if (n == 0) {
            return widths;
        }
        boolean usePressure = pressureVaries(points);
        float[] taper = taperEnvelope(points);
        for (int i = 0; i < n; i++) {
            float pressureScale = 1f;
            if (usePressure) {
                float p01 = pressure01(points.get(i).pressure, points);
                pressureScale = PRESSURE_MIN_SCALE + (PRESSURE_MAX_SCALE - PRESSURE_MIN_SCALE) * p01;
            }
            widths[i] = BASE_WIDTH_PX * pressureScale * taper[i];
        }
        return widths;
    }

    private static boolean pressureVaries(List<TouchPoint> points) {
        float min = Float.MAX_VALUE;
        float max = 0f;
        for (TouchPoint point : points) {
            float p = point.pressure;
            if (p < min) {
                min = p;
            }
            if (p > max) {
                max = p;
            }
        }
        if (max <= 0f) {
            return false;
        }
        return (max - min) > Math.max(0.02f, max * PRESSURE_VARIATION_RATIO);
    }

    private static float pressure01(float pressure, List<TouchPoint> points) {
        float observedMax = 0f;
        for (TouchPoint point : points) {
            if (point.pressure > observedMax) {
                observedMax = point.pressure;
            }
        }
        float cap;
        if (observedMax <= 1.05f) {
            cap = 1f;
        } else {
            cap = EpdController.getMaxTouchPressure();
            if (cap <= 1f) {
                cap = Math.max(observedMax, 1f);
            }
        }
        float n = pressure / cap;
        if (n < 0f) {
            return 0f;
        }
        if (n > 1f) {
            return 1f;
        }
        return n;
    }

    private static float[] taperEnvelope(List<TouchPoint> points) {
        int n = points.size();
        float[] taper = new float[n];
        if (n == 1) {
            taper[0] = TAPER_MIN;
            return taper;
        }
        float[] dist = new float[n];
        dist[0] = 0f;
        for (int i = 1; i < n; i++) {
            TouchPoint a = points.get(i - 1);
            TouchPoint b = points.get(i);
            float dx = b.x - a.x;
            float dy = b.y - a.y;
            dist[i] = dist[i - 1] + (float) Math.hypot(dx, dy);
        }
        float length = dist[n - 1];
        if (length < 1f) {
            for (int i = 0; i < n; i++) {
                taper[i] = TAPER_MIN + (1f - TAPER_MIN) * 0.5f;
            }
            return taper;
        }
        float tip = Math.max(8f, length * TAPER_FRACTION);
        for (int i = 0; i < n; i++) {
            float fromStart = dist[i];
            float fromEnd = length - dist[i];
            float edge = Math.min(fromStart, fromEnd);
            float t;
            if (edge >= tip) {
                t = 1f;
            } else {
                t = edge / tip;
                t = t * t * (3f - 2f * t);
            }
            taper[i] = TAPER_MIN + (1f - TAPER_MIN) * t;
        }
        return taper;
    }

    private static float distanceToPolylineSq(List<TouchPoint> points, float x, float y) {
        if (points.isEmpty()) {
            return Float.MAX_VALUE;
        }
        if (points.size() == 1) {
            TouchPoint p = points.get(0);
            float dx = x - p.x;
            float dy = y - p.y;
            return dx * dx + dy * dy;
        }
        float best = Float.MAX_VALUE;
        TouchPoint prev = points.get(0);
        for (int i = 1; i < points.size(); i++) {
            TouchPoint next = points.get(i);
            float d = distanceToSegmentSq(x, y, prev.x, prev.y, next.x, next.y);
            if (d < best) {
                best = d;
            }
            prev = next;
        }
        return best;
    }

    private static float distanceToSegmentSq(float px, float py, float x1, float y1, float x2, float y2) {
        float dx = x2 - x1;
        float dy = y2 - y1;
        float len2 = dx * dx + dy * dy;
        float t = 0f;
        if (len2 > 0f) {
            t = ((px - x1) * dx + (py - y1) * dy) / len2;
            if (t < 0f) {
                t = 0f;
            } else if (t > 1f) {
                t = 1f;
            }
        }
        float x = x1 + t * dx;
        float y = y1 + t * dy;
        float ex = px - x;
        float ey = py - y;
        return ex * ex + ey * ey;
    }

    public static final class InkStroke {
        public final ArrayList<TouchPoint> points;
        final float[] widths;
        final RectF bounds;
        final float maxWidth;

        InkStroke(ArrayList<TouchPoint> points, float[] widths) {
            this.points = points;
            this.widths = widths;
            float max = InkRenderer.BASE_WIDTH_PX;
            for (float w : widths) {
                if (w > max) {
                    max = w;
                }
            }
            this.maxWidth = max;
            this.bounds = boundsOf(points, max * 0.5f + 2f);
        }
    }
}
