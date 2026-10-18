package com.zetteldraw.penpoc.data;

import com.onyx.android.sdk.data.note.TouchPoint;
import com.zetteldraw.penpoc.Board;
import com.zetteldraw.penpoc.InkRenderer;

import java.util.ArrayList;
import java.util.List;

public final class TestInk {
    private TestInk() {
    }

    public static InkRenderer.InkStroke stroke(float x0, float y0, int points) {
        ArrayList<TouchPoint> list = new ArrayList<>();
        for (int i = 0; i < points; i++) {
            list.add(new TouchPoint(x0 + i * 3f, y0 + i * 2f, 0.4f + (i % 5) * 0.1f, 1f, i % 3, -(i % 2), 1_000L + i * 8L));
        }
        return InkRenderer.strokeFrom(list);
    }

    /**
     * Ink on a Ready slice. If {@code board.id} is not on paper yet, appends that
     * blank slice first (replaces the old soft trailing-blank adopt).
     */
    public static void draw(Board board, float x0) {
        float y0 = board.paper != null ? board.paperOrigin + 50f : 50f;
        InkRenderer.InkStroke s = stroke(x0, y0, 12);
        if (board.paper != null) {
            ensureSlice(board);
            board.paper.appendStroke(s);
        } else {
            board.strokes.add(s);
        }
    }

    /** Ensure {@code board.id} is a real slice; reseat board fields. */
    public static void ensureSlice(Board board) {
        if (board == null || board.paper == null) {
            return;
        }
        if (board.paper.indexOfSliceId(board.id) < 0) {
            board.paper.appendBlankPage(board.id);
        }
        int index = board.paper.indexOfSliceId(board.id);
        board.sliceIndex = index;
        board.paperOrigin = board.paper.origin(index);
        board.slicePx = board.paper.heightAt(index);
    }

    /**
     * Append stroke after ensuring slices cover {@code bottom}. First new slice
     * may use {@code adoptId} only when that id is not already on the paper
     * (test substitute for deleted soft-tail growTo — never duplicate a Ready id).
     */
    public static void appendCovering(NotebookPaper paper, InkRenderer.InkStroke stroke, String adoptId) {
        if (paper == null || stroke == null) {
            return;
        }
        float bottom = stroke.bounds.bottom;
        while (bottom >= paper.origin(paper.sliceCount())) {
            String id = (adoptId != null && paper.indexOfSliceId(adoptId) < 0)
                    ? adoptId
                    : java.util.UUID.randomUUID().toString();
            adoptId = null;
            paper.appendBlankPage(id);
        }
        paper.appendStroke(stroke);
    }

    public static void appendCovering(NotebookPaper paper, InkRenderer.InkStroke stroke) {
        appendCovering(paper, stroke, null);
    }

    /** Strokes for this page: Ready slices use paper.touching; legacy uses {@code strokes}. */
    public static List<InkRenderer.InkStroke> strokesOf(Board board) {
        if (board.paper != null) {
            int index = board.paper.indexOfSliceId(board.id);
            if (index < 0) {
                index = board.sliceIndex;
            }
            return board.paper.touching(index);
        }
        return board.strokes;
    }

    public static void assertSameInk(List<InkRenderer.InkStroke> expected, List<InkRenderer.InkStroke> actual) {
        org.junit.Assert.assertEquals(expected.size(), actual.size());
        for (int s = 0; s < expected.size(); s++) {
            org.junit.Assert.assertEquals(expected.get(s).color, actual.get(s).color);
            List<TouchPoint> e = expected.get(s).points;
            List<TouchPoint> a = actual.get(s).points;
            org.junit.Assert.assertEquals(e.size(), a.size());
            for (int i = 0; i < e.size(); i++) {
                org.junit.Assert.assertEquals(e.get(i).x, a.get(i).x, 0f);
                org.junit.Assert.assertEquals(e.get(i).y, a.get(i).y, 0f);
                org.junit.Assert.assertEquals(e.get(i).pressure, a.get(i).pressure, 0f);
                org.junit.Assert.assertEquals(e.get(i).size, a.get(i).size, 0f);
                org.junit.Assert.assertEquals(e.get(i).tiltX, a.get(i).tiltX);
                org.junit.Assert.assertEquals(e.get(i).tiltY, a.get(i).tiltY);
                org.junit.Assert.assertEquals(e.get(i).timestamp, a.get(i).timestamp);
            }
        }
    }
}
