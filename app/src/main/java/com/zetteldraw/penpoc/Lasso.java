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

    private Lasso() {
    }

    /** Strokes with at least half their points inside the closed polygon; a dot needs its one point inside. */
    static List<InkRenderer.InkStroke> select(List<InkRenderer.InkStroke> strokes, List<TouchPoint> polygon) {
        ArrayList<InkRenderer.InkStroke> selected = new ArrayList<>();
        if (strokes == null || polygon == null || polygon.size() < 3) {
            return selected;
        }
        RectF area = InkRenderer.boundsOf(polygon, 0f);
        for (InkRenderer.InkStroke stroke : strokes) {
            if (!RectF.intersects(area, stroke.bounds)) {
                continue;
            }
            int inside = 0;
            for (TouchPoint point : stroke.points) {
                if (area.contains(point.x, point.y) && contains(polygon, point.x, point.y)) {
                    inside++;
                }
            }
            if (inside > 0 && inside >= stroke.points.size() * MAJORITY) {
                selected.add(stroke);
            }
        }
        return selected;
    }

    /** Even-odd ray cast; the polygon is closed implicitly. */
    static boolean contains(List<TouchPoint> polygon, float x, float y) {
        boolean inside = false;
        int n = polygon.size();
        for (int i = 0, j = n - 1; i < n; j = i++) {
            TouchPoint a = polygon.get(i);
            TouchPoint b = polygon.get(j);
            if ((a.y > y) != (b.y > y)
                    && x < (b.x - a.x) * (y - a.y) / (b.y - a.y) + a.x) {
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
        for (int i = 0; i < page.strokes.size(); i++) {
            InkRenderer.InkStroke stroke = page.strokes.get(i);
            if (ids.contains(stroke.id)) {
                before.put(stroke.id, stroke);
                page.strokes.set(i, stroke.translated(dx, dy));
            }
        }
        return before.isEmpty() ? null : new Move(page, before, dx, dy);
    }

    /** One committed lasso move; enough to put the strokes back exactly. */
    static final class Move {
        final Board page;
        final float dx;
        final float dy;
        private final Map<String, InkRenderer.InkStroke> before;

        Move(Board page, Map<String, InkRenderer.InkStroke> before, float dx, float dy) {
            this.page = page;
            this.before = before;
            this.dx = dx;
            this.dy = dy;
        }

        int size() {
            return before.size();
        }

        /**
         * Restores the original points of moved strokes that still exist.
         * Strokes erased since the move stay erased; strokes drawn since stay put.
         */
        boolean undo() {
            boolean changed = false;
            for (int i = 0; i < page.strokes.size(); i++) {
                InkRenderer.InkStroke original = before.get(page.strokes.get(i).id);
                if (original != null && original != page.strokes.get(i)) {
                    page.strokes.set(i, original);
                    changed = true;
                }
            }
            return changed;
        }
    }
}
