package com.zetteldraw.penpoc.data;

import com.onyx.android.sdk.data.note.TouchPoint;
import com.zetteldraw.penpoc.Board;
import com.zetteldraw.penpoc.InkRenderer;

import java.util.ArrayList;
import java.util.List;

final class TestInk {
    private TestInk() {
    }

    static InkRenderer.InkStroke stroke(float x0, float y0, int points) {
        ArrayList<TouchPoint> list = new ArrayList<>();
        for (int i = 0; i < points; i++) {
            list.add(new TouchPoint(x0 + i * 3f, y0 + i * 2f, 0.4f + (i % 5) * 0.1f, 1f, i % 3, -(i % 2), 1_000L + i * 8L));
        }
        return InkRenderer.strokeFrom(list);
    }

    static void draw(Board board, float x0) {
        board.strokes.add(stroke(x0, 50f, 12));
    }

    static void assertSameInk(List<InkRenderer.InkStroke> expected, List<InkRenderer.InkStroke> actual) {
        org.junit.Assert.assertEquals(expected.size(), actual.size());
        for (int s = 0; s < expected.size(); s++) {
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
