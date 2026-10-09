package com.zetteldraw.penpoc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.graphics.RectF;

import com.onyx.android.sdk.data.note.TouchPoint;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class)
public class LassoTest {
    /** Closed square lasso from (100,100) to (300,300), drawn as a pen would: many points. */
    private static List<TouchPoint> square() {
        ArrayList<TouchPoint> polygon = new ArrayList<>();
        for (int i = 0; i <= 20; i++) {
            polygon.add(point(100f + i * 10f, 100f));
        }
        for (int i = 1; i <= 20; i++) {
            polygon.add(point(300f, 100f + i * 10f));
        }
        for (int i = 1; i <= 20; i++) {
            polygon.add(point(300f - i * 10f, 300f));
        }
        for (int i = 1; i < 20; i++) {
            polygon.add(point(100f, 300f - i * 10f));
        }
        return polygon;
    }

    private static TouchPoint point(float x, float y) {
        return new TouchPoint(x, y, 0.5f, 1f, 0, 0, 1_000L);
    }

    /** Horizontal line of {@code n} points starting at x0, 10px apart. */
    private static InkRenderer.InkStroke line(float x0, float y, int n) {
        ArrayList<TouchPoint> points = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            points.add(new TouchPoint(x0 + i * 10f, y, 0.5f, 1f, 0, 0, 1_000L + i));
        }
        return InkRenderer.strokeFrom(points);
    }

    @Test
    public void polygonContainment() {
        List<TouchPoint> square = square();
        assertTrue(Lasso.contains(square, 200f, 200f));
        assertTrue(Lasso.contains(square, 101f, 299f));
        assertFalse(Lasso.contains(square, 99f, 200f));
        assertFalse(Lasso.contains(square, 200f, 301f));

        ArrayList<TouchPoint> lShape = new ArrayList<>();
        lShape.add(point(0f, 0f));
        lShape.add(point(100f, 0f));
        lShape.add(point(100f, 40f));
        lShape.add(point(40f, 40f));
        lShape.add(point(40f, 100f));
        lShape.add(point(0f, 100f));
        assertTrue(Lasso.contains(lShape, 20f, 80f));
        assertFalse("concave notch is outside", Lasso.contains(lShape, 80f, 80f));
    }

    @Test
    public void selectsStrokesWithMostPointsInside() {
        InkRenderer.InkStroke inside = line(150f, 150f, 10);        // 150..240, all in
        InkRenderer.InkStroke half = line(205f, 200f, 20);          // 205..395: 10 of 20 in
        InkRenderer.InkStroke mostlyOut = line(250f, 250f, 20);     // 250..440: 5 of 20 in
        InkRenderer.InkStroke far = line(600f, 600f, 5);
        InkRenderer.InkStroke dotIn = line(120f, 280f, 1);
        InkRenderer.InkStroke dotOut = line(90f, 280f, 1);
        List<InkRenderer.InkStroke> page = List.of(inside, half, mostlyOut, far, dotIn, dotOut);

        List<InkRenderer.InkStroke> picked = Lasso.select(page, square());

        assertEquals(List.of(inside, half, dotIn), picked);
    }

    @Test
    public void degenerateLassoSelectsNothing() {
        List<InkRenderer.InkStroke> page = List.of(line(150f, 150f, 3));
        ArrayList<TouchPoint> twoPoints = new ArrayList<>();
        twoPoints.add(point(100f, 100f));
        twoPoints.add(point(300f, 300f));
        assertTrue(Lasso.select(page, twoPoints).isEmpty());
        assertTrue(Lasso.select(Collections.emptyList(), square()).isEmpty());
    }

    @Test
    public void clampKeepsSelectionOnThePage() {
        RectF bounds = new RectF(100f, 200f, 300f, 400f);
        float[] ok = Lasso.clampOffset(bounds, 50f, -50f, 1000f, 1400f);
        assertEquals(50f, ok[0], 0f);
        assertEquals(-50f, ok[1], 0f);

        float[] clamped = Lasso.clampOffset(bounds, -500f, 5000f, 1000f, 1400f);
        assertEquals(-100f, clamped[0], 0f);
        assertEquals(1000f, clamped[1], 0f);

        RectF overEdge = new RectF(-10f, 0f, 50f, 50f);
        float[] stuck = Lasso.clampOffset(overEdge, -30f, 0f, 1000f, 1400f);
        assertEquals("already past the edge: not pushed further", 0f, stuck[0], 0f);
        assertEquals(20f, Lasso.clampOffset(overEdge, 20f, 0f, 1000f, 1400f)[0], 0f);
    }

    @Test
    public void moveRewritesPointsAndKeepsIdsAndOrder() {
        Board page = Board.blank();
        InkRenderer.InkStroke a = line(150f, 150f, 5);
        InkRenderer.InkStroke b = line(500f, 500f, 5);
        InkRenderer.InkStroke c = line(160f, 250f, 5);
        page.strokes.addAll(List.of(a, b, c));
        Set<String> ids = Lasso.idsOf(List.of(a, c));

        Lasso.Move move = Lasso.move(page, ids, 40f, -30f);

        assertNotNull(move);
        assertEquals(2, move.size());
        assertEquals(3, page.strokes.size());
        assertEquals(a.id, page.strokes.get(0).id);
        assertSame("unselected stroke untouched", b, page.strokes.get(1));
        assertEquals(c.id, page.strokes.get(2).id);
        for (int i = 0; i < a.points.size(); i++) {
            assertEquals(a.points.get(i).x + 40f, page.strokes.get(0).points.get(i).x, 0f);
            assertEquals(a.points.get(i).y - 30f, page.strokes.get(0).points.get(i).y, 0f);
            assertEquals(a.points.get(i).pressure, page.strokes.get(0).points.get(i).pressure, 0f);
            assertEquals(a.points.get(i).timestamp, page.strokes.get(0).points.get(i).timestamp);
        }
        assertEquals("original points not mutated", 150f, a.points.get(0).x, 0f);
        assertEquals(a.bounds.left + 40f, page.strokes.get(0).bounds.left, 0.001f);
        assertEquals(c.bounds.top - 30f, page.strokes.get(2).bounds.top, 0.001f);

        List<InkRenderer.InkStroke> reselect = Lasso.select(page.strokes, square());
        assertEquals("hit-testing uses the moved points", 2, reselect.size());
    }

    @Test
    public void zeroOffsetOrNoMatchIsNotAMove() {
        Board page = Board.blank();
        InkRenderer.InkStroke a = line(150f, 150f, 5);
        page.strokes.add(a);
        assertNull(Lasso.move(page, Lasso.idsOf(List.of(a)), 0f, 0f));
        assertNull(Lasso.move(page, Set.of("missing"), 10f, 10f));
        assertSame(a, page.strokes.get(0));
    }

    @Test
    public void undoRestoresExactPoints() {
        Board page = Board.blank();
        InkRenderer.InkStroke a = line(150.3f, 150.7f, 5);
        InkRenderer.InkStroke b = line(500f, 500f, 5);
        page.strokes.addAll(List.of(a, b));
        Lasso.Move move = Lasso.move(page, Lasso.idsOf(List.of(a)), 33.33f, 17.77f);
        assertNotSame(a, page.strokes.get(0));

        assertTrue(move.historyPart().revert());

        assertSame(a, page.strokes.get(0));
        assertSame(b, page.strokes.get(1));
        assertFalse("second undo is a no-op", move.historyPart().revert());
    }

    @Test
    public void undoKeepsLaterEditsAndSkipsErasedStrokes() {
        Board page = Board.blank();
        InkRenderer.InkStroke a = line(150f, 150f, 5);
        InkRenderer.InkStroke c = line(150f, 250f, 5);
        page.strokes.addAll(List.of(a, c));
        Lasso.Move move = Lasso.move(page, Lasso.idsOf(List.of(a, c)), 10f, 10f);
        page.strokes.remove(1);
        InkRenderer.InkStroke drawnAfter = line(700f, 700f, 3);
        page.strokes.add(drawnAfter);

        assertTrue(move.historyPart().revert());

        assertEquals(2, page.strokes.size());
        assertSame(a, page.strokes.get(0));
        assertSame(drawnAfter, page.strokes.get(1));
    }

    @Test
    public void undoAfterWipeDoesNothing() {
        Board page = Board.blank();
        InkRenderer.InkStroke a = line(150f, 150f, 5);
        page.strokes.add(a);
        Lasso.Move move = Lasso.move(page, Lasso.idsOf(List.of(a)), 10f, 10f);
        page.strokes.clear();
        assertFalse(move.historyPart().revert());
        assertTrue(page.isBlank());
    }
}
