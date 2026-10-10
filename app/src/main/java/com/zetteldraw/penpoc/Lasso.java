package com.zetteldraw.penpoc;

import android.graphics.RectF;

import com.onyx.android.sdk.data.note.TouchPoint;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Lasso selection on one page, in page-local coordinates. A move rewrites
 * the selected strokes' points and keeps their ids.
 */
final class Lasso {
    /** Share of a stroke's points that must fall inside the lasso. */
    static final float MAJORITY = 0.5f;
    /**
     * Cap on polygon vertices used for hit-testing. A raw stylus circle is
     * often 500–2000 samples; ray-casting every stroke point against that is
     * quadratic and freezes the UI on a dense page. The shape is unchanged.
     */
    static final int MAX_HIT_VERTICES = 64;
    /** Drop successive outline samples closer than this (device px). */
    private static final float MIN_VERTEX_SPACING_SQ = 4f * 4f;

    private Lasso() {
    }

    /** Strokes with at least half their points inside the closed polygon; a dot needs its one point inside. */
    static List<InkRenderer.InkStroke> select(List<InkRenderer.InkStroke> strokes, List<TouchPoint> polygon) {
        ArrayList<InkRenderer.InkStroke> selected = new ArrayList<>();
        if (strokes == null || polygon == null || polygon.size() < 3) {
            return selected;
        }
        float[] poly = flatten(simplify(polygon));
        if (poly.length < 6) {
            return selected;
        }
        RectF area = boundsOf(poly);
        for (InkRenderer.InkStroke stroke : strokes) {
            if (!RectF.intersects(area, stroke.bounds)) {
                continue;
            }
            if (majorityInside(stroke, area, poly)) {
                selected.add(stroke);
            }
        }
        return selected;
    }

    /**
     * True when at least {@link #MAJORITY} of the stroke's points lie inside
     * {@code poly}. Stops early once the answer cannot change.
     */
    private static boolean majorityInside(InkRenderer.InkStroke stroke, RectF area, float[] poly) {
        int n = stroke.points.size();
        if (n == 0) {
            return false;
        }
        float need = n * MAJORITY;
        int inside = 0;
        int remaining = n;
        for (TouchPoint point : stroke.points) {
            remaining--;
            if (area.contains(point.x, point.y) && contains(poly, point.x, point.y)) {
                inside++;
                if (inside >= need) {
                    return true;
                }
            } else if (inside + remaining < need) {
                return false;
            }
        }
        return inside > 0 && inside >= need;
    }

    /**
     * Thins a hand-drawn outline for hit-testing. Keeps the closed shape;
     * drops near-duplicate samples, then strides down to {@link #MAX_HIT_VERTICES}.
     */
    static List<TouchPoint> simplify(List<TouchPoint> polygon) {
        if (polygon == null || polygon.size() < 3) {
            return polygon == null ? List.of() : new ArrayList<>(polygon);
        }
        ArrayList<TouchPoint> spaced = new ArrayList<>(Math.min(polygon.size(), MAX_HIT_VERTICES + 1));
        TouchPoint first = polygon.get(0);
        spaced.add(first);
        TouchPoint lastKept = first;
        for (int i = 1; i < polygon.size(); i++) {
            TouchPoint point = polygon.get(i);
            float dx = point.x - lastKept.x;
            float dy = point.y - lastKept.y;
            if (dx * dx + dy * dy >= MIN_VERTEX_SPACING_SQ) {
                spaced.add(point);
                lastKept = point;
            }
        }
        TouchPoint end = polygon.get(polygon.size() - 1);
        if (spaced.get(spaced.size() - 1) != end) {
            float dx = end.x - lastKept.x;
            float dy = end.y - lastKept.y;
            if (dx * dx + dy * dy >= 1f || spaced.size() < 3) {
                spaced.add(end);
            }
        }
        if (spaced.size() < 3) {
            return new ArrayList<>(polygon.subList(0, Math.min(3, polygon.size())));
        }
        if (spaced.size() <= MAX_HIT_VERTICES) {
            return spaced;
        }
        ArrayList<TouchPoint> thinned = new ArrayList<>(MAX_HIT_VERTICES);
        int last = spaced.size() - 1;
        thinned.add(spaced.get(0));
        for (int i = 1; i < MAX_HIT_VERTICES - 1; i++) {
            int index = i * last / (MAX_HIT_VERTICES - 1);
            thinned.add(spaced.get(index));
        }
        thinned.add(spaced.get(last));
        return thinned;
    }

    /** Flat [x0,y0,x1,y1,…] for the ray cast. */
    private static float[] flatten(List<TouchPoint> polygon) {
        float[] xy = new float[polygon.size() * 2];
        for (int i = 0; i < polygon.size(); i++) {
            TouchPoint point = polygon.get(i);
            xy[i * 2] = point.x;
            xy[i * 2 + 1] = point.y;
        }
        return xy;
    }

    private static RectF boundsOf(float[] poly) {
        float left = poly[0];
        float top = poly[1];
        float right = poly[0];
        float bottom = poly[1];
        for (int i = 2; i < poly.length; i += 2) {
            float x = poly[i];
            float y = poly[i + 1];
            left = Math.min(left, x);
            top = Math.min(top, y);
            right = Math.max(right, x);
            bottom = Math.max(bottom, y);
        }
        return new RectF(left, top, right, bottom);
    }

    /** Even-odd ray cast; the polygon is closed implicitly. */
    static boolean contains(List<TouchPoint> polygon, float x, float y) {
        if (polygon == null || polygon.size() < 3) {
            return false;
        }
        return contains(flatten(polygon), x, y);
    }

    static boolean contains(float[] poly, float x, float y) {
        boolean inside = false;
        int n = poly.length / 2;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            float ax = poly[i * 2];
            float ay = poly[i * 2 + 1];
            float bx = poly[j * 2];
            float by = poly[j * 2 + 1];
            if ((ay > y) != (by > y)
                    && x < (bx - ax) * (y - ay) / (by - ay) + ax) {
                inside = !inside;
            }
        }
        return inside;
    }

    static RectF boundsOf(Collection<InkRenderer.InkStroke> strokes) {
        RectF bounds = null;
        for (InkRenderer.InkStroke stroke : strokes) {
            if (bounds == null) {
                bounds = new RectF(stroke.bounds);
            } else {
                bounds.union(stroke.bounds);
            }
        }
        return bounds == null ? new RectF() : bounds;
    }

    /**
     * Limits an offset so {@code bounds} stays on a page of the given size.
     * Ink already past an edge may stay there but is not pushed further out.
     */
    static float[] clampOffset(RectF bounds, float dx, float dy, float pageWidth, float pageHeight) {
        return new float[]{
                clamp(dx, Math.min(0f, -bounds.left), Math.max(0f, pageWidth - bounds.right)),
                clamp(dy, Math.min(0f, -bounds.top), Math.max(0f, pageHeight - bounds.bottom)),
        };
    }

    /**
     * Keeps {@code bounds} inside the paper rectangle that is already on screen.
     * Ink already past an edge may stay there but is not pushed further out.
     */
    static float[] clampInto(RectF bounds, float dx, float dy,
                              float minX, float minY, float maxX, float maxY) {
        return new float[]{
                clamp(dx, Math.min(0f, minX - bounds.left), Math.max(0f, maxX - bounds.right)),
                clamp(dy, Math.min(0f, minY - bounds.top), Math.max(0f, maxY - bounds.bottom)),
        };
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    static Set<String> idsOf(Collection<InkRenderer.InkStroke> strokes) {
        HashSet<String> ids = new HashSet<>();
        for (InkRenderer.InkStroke stroke : strokes) {
            ids.add(stroke.id);
        }
        return ids;
    }

    /**
     * Shifts the strokes with these ids in place (order and ids kept).
     * Returns null when nothing moved.
     */
    static Move move(Board page, Set<String> ids, float dx, float dy) {
        if (page == null || ids == null || ids.isEmpty() || (dx == 0f && dy == 0f)) {
            return null;
        }
        HashMap<String, InkRenderer.InkStroke> before = new HashMap<>();
        HashMap<String, InkRenderer.InkStroke> after = new HashMap<>();
        for (int i = 0; i < page.strokes.size(); i++) {
            InkRenderer.InkStroke stroke = page.strokes.get(i);
            if (ids.contains(stroke.id)) {
                InkRenderer.InkStroke moved = stroke.translated(dx, dy);
                before.put(stroke.id, stroke);
                after.put(stroke.id, moved);
                page.strokes.set(i, moved);
            }
        }
        return before.isEmpty() ? null : new Move(page, before, after, dx, dy);
    }

    /** One committed lasso move: the strokes before and after, by id. */
    static final class Move {
        final Board page;
        final float dx;
        final float dy;
        private final Map<String, InkRenderer.InkStroke> before;
        private final Map<String, InkRenderer.InkStroke> after;

        Move(Board page, Map<String, InkRenderer.InkStroke> before,
             Map<String, InkRenderer.InkStroke> after, float dx, float dy) {
            this.page = page;
            this.before = before;
            this.after = after;
            this.dx = dx;
            this.dy = dy;
        }

        int size() {
            return before.size();
        }

        InkHistory.Part historyPart() {
            return InkHistory.Part.moved(page, before, after);
        }
    }
}
