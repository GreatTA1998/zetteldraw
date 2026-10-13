package com.zetteldraw.penpoc.data;

import android.graphics.RectF;

import com.zetteldraw.penpoc.InkRenderer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * Y-bucket spatial index over paper strokes. Hit-tests (lasso, erase, slice
 * paint) query a paper Y range instead of walking every stroke on the sheet.
 *
 * <p>A stroke is listed in every bucket its bounds overlap. Bucket height is
 * fixed in paper pixels so cost tracks the query region, not notebook length.
 */
final class StrokeYIndex {
    /** Paper-px per bucket. Smaller → tighter erase/lasso candidates. */
    static final float BUCKET_HEIGHT = 256f;

    private final HashMap<Integer, ArrayList<InkRenderer.InkStroke>> buckets = new HashMap<>();

    void clear() {
        buckets.clear();
    }

    void rebuild(List<InkRenderer.InkStroke> strokes) {
        clear();
        if (strokes == null) {
            return;
        }
        for (InkRenderer.InkStroke stroke : strokes) {
            insert(stroke);
        }
    }

    void insert(InkRenderer.InkStroke stroke) {
        if (stroke == null || stroke.bounds == null) {
            return;
        }
        int lo = bucket(stroke.bounds.top);
        int hi = bucket(stroke.bounds.bottom);
        if (hi < lo) {
            int t = lo;
            lo = hi;
            hi = t;
        }
        for (int b = lo; b <= hi; b++) {
            ArrayList<InkRenderer.InkStroke> list = buckets.get(b);
            if (list == null) {
                list = new ArrayList<>(4);
                buckets.put(b, list);
            }
            list.add(stroke);
        }
    }

    /**
     * Unique strokes whose bounds meet {@code [top, bottom)}. Empty when the
     * range is empty. Still filter with AABB / geometry; this only culls.
     */
    List<InkRenderer.InkStroke> overlapping(float top, float bottom) {
        ArrayList<InkRenderer.InkStroke> out = new ArrayList<>();
        if (!(top < bottom)) {
            return out;
        }
        int lo = bucket(top);
        int hi = bucket(bottom);
        if (hi < lo) {
            int t = lo;
            lo = hi;
            hi = t;
        }
        HashSet<InkRenderer.InkStroke> seen = new HashSet<>();
        for (int b = lo; b <= hi; b++) {
            ArrayList<InkRenderer.InkStroke> list = buckets.get(b);
            if (list == null) {
                continue;
            }
            for (InkRenderer.InkStroke stroke : list) {
                if (stroke.bounds == null) {
                    continue;
                }
                if (stroke.bounds.bottom >= top && stroke.bounds.top < bottom && seen.add(stroke)) {
                    out.add(stroke);
                }
            }
        }
        return out;
    }

    List<InkRenderer.InkStroke> overlapping(RectF area) {
        if (area == null || area.isEmpty()) {
            return new ArrayList<>();
        }
        return overlapping(area.top, area.bottom);
    }

    /** Test helper: how many bucket entries exist (a tall stroke counts many times). */
    int entryCount() {
        int n = 0;
        for (Map.Entry<Integer, ArrayList<InkRenderer.InkStroke>> e : buckets.entrySet()) {
            n += e.getValue().size();
        }
        return n;
    }

    private static int bucket(float y) {
        if (y < 0f) {
            // Keep negative ink addressable without colliding with bucket 0.
            return (int) Math.floor(y / BUCKET_HEIGHT);
        }
        return (int) (y / BUCKET_HEIGHT);
    }
}
