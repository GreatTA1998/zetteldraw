package com.zetteldraw.penpoc;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.DisplayMetrics;
import android.util.TypedValue;

import com.onyx.android.sdk.api.device.epd.EpdController;
import com.onyx.android.sdk.data.note.TouchPoint;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

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
        return strokeFrom(UUID.randomUUID().toString(), points);
    }

    public static InkStroke strokeFrom(String id, List<TouchPoint> points) {
        return strokeOwning(id, copyPoints(points));
    }

    /** {@code points} become the stroke's own: no other code may hold or change them. */
    public static InkStroke strokeOwning(String id, ArrayList<TouchPoint> points) {
        return new InkStroke(id, points, widthsFor(points));
    }

    static void draw(Canvas canvas, Paint paint, InkStroke stroke) {
        if (stroke != null) {
            drawAll(canvas, paint, Collections.singletonList(stroke));
        }
    }

    /** Segment widths are drawn in steps of 1/{@value #WIDTH_STEPS} px, far below what e-ink shows. */
    private static final int WIDTH_STEPS = 4;

    /**
     * Each segment is a round-capped line of its own width, as before; segments
     * of equal (stepped) width go out in one {@code drawLines} call instead of
     * one native call per segment, which made a full page take ~0.5 s.
     */
    static void drawAll(Canvas canvas, Paint paint, List<InkStroke> strokes) {
        float[][] lines = new float[MAX_WIDTH_STEP + 1][];
        int[] counts = new int[MAX_WIDTH_STEP + 1];
        for (InkStroke stroke : strokes) {
            List<TouchPoint> points = stroke.points;
            float[] widths = stroke.widths;
            if (points.isEmpty()) {
                continue;
            }
            if (points.size() == 1) {
                TouchPoint p = points.get(0);
                paint.setStrokeWidth(widths[0]);
                canvas.drawPoint(p.x, p.y, paint);
                continue;
            }
            TouchPoint previous = points.get(0);
            float previousWidth = widths[0];
            for (int i = 1; i < points.size(); i++) {
                TouchPoint point = points.get(i);
                int step = Math.min(MAX_WIDTH_STEP,
                        Math.max(1, Math.round((previousWidth + widths[i]) * 0.5f * WIDTH_STEPS)));
                float[] buf = lines[step];
                if (buf == null) {
                    buf = lines[step] = new float[256];
                } else if (counts[step] + 4 > buf.length) {
                    buf = lines[step] = Arrays.copyOf(buf, buf.length * 2);
                }
                int n = counts[step];
                buf[n] = previous.x;
                buf[n + 1] = previous.y;
                buf[n + 2] = point.x;
                buf[n + 3] = point.y;
                counts[step] = n + 4;
                previous = point;
                previousWidth = widths[i];
            }
        }
        for (int step = 1; step <= MAX_WIDTH_STEP; step++) {
            if (counts[step] > 0) {
                paint.setStrokeWidth(step / (float) WIDTH_STEPS);
                canvas.drawLines(lines[step], 0, counts[step], paint);
            }
        }
    }

    private static final int MAX_WIDTH_STEP = 64 * WIDTH_STEPS;

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
        float left = first.x;
        float top = first.y;
        float right = first.x;
        float bottom = first.y;
        for (int i = 1; i < points.size(); i++) {
            TouchPoint p = points.get(i);
            left = Math.min(left, p.x);
            top = Math.min(top, p.y);
            right = Math.max(right, p.x);
            bottom = Math.max(bottom, p.y);
        }
        bounds.set(left - pad, top - pad, right + pad, bottom + pad);
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
        float cap = usePressure ? pressureCap(points) : 1f;
        float[] taper = taperEnvelope(points);
        for (int i = 0; i < n; i++) {
            float pressureScale = 1f;
            if (usePressure) {
                float p01 = pressure01(points.get(i).pressure, cap);
                pressureScale = PRESSURE_MIN_SCALE + (PRESSURE_MAX_SCALE - PRESSURE_MIN_SCALE) * p01;
            }
            widths[i] = BASE_WIDTH_PX * pressureScale * taper[i];
        }
        return widths;
    }

    /** Device maximum, read once: it is a system call on the Boox, and widths are computed for every decoded point. */
    private static volatile float deviceMaxPressure = Float.NaN;

    private static float deviceMaxPressure() {
        float max = deviceMaxPressure;
        if (Float.isNaN(max)) {
            try {
                max = EpdController.getMaxTouchPressure();
            } catch (RuntimeException | LinkageError e) {
                max = 0f;
            }
            deviceMaxPressure = max;
        }
        return max;
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

    private static float pressureCap(List<TouchPoint> points) {
        float observedMax = 0f;
        for (TouchPoint point : points) {
            if (point.pressure > observedMax) {
                observedMax = point.pressure;
            }
        }
        if (observedMax <= 1.05f) {
            return 1f;
        }
        float cap = deviceMaxPressure();
        return cap <= 1f ? Math.max(observedMax, 1f) : cap;
    }

    private static float pressure01(float pressure, float cap) {
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
            dist[i] = dist[i - 1] + (float) Math.sqrt(dx * dx + dy * dy);
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
        /** Stable across moves and sync; only a duplicate gets a new one. */
        public final String id;
        public final ArrayList<TouchPoint> points;
        final float[] widths;
        final RectF bounds;
        final float maxWidth;

        InkStroke(String id, ArrayList<TouchPoint> points, float[] widths) {
            this.id = id;
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

        /** Same id and widths, every point shifted. */
        public InkStroke translated(float dx, float dy) {
            ArrayList<TouchPoint> moved = copyPoints(points);
            for (TouchPoint point : moved) {
                point.x += dx;
                point.y += dy;
            }
            return new InkStroke(id, moved, widths);
        }
    }
}
