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

    /** Dense stylus circle: many samples, same closed shape as {@link #square()}. */
    private static List<TouchPoint> denseCircle(float cx, float cy, float r, int samples) {
        ArrayList<TouchPoint> polygon = new ArrayList<>(samples);
        for (int i = 0; i < samples; i++) {
            double a = i * 2.0 * Math.PI / samples;
            polygon.add(point(cx + (float) (r * Math.cos(a)), cy + (float) (r * Math.sin(a))));
        }
        return polygon;
    }

    @Test
    public void simplifyCapsVerticesAndKeepsContainment() {
        List<TouchPoint> dense = denseCircle(200f, 200f, 120f, 1800);
        List<TouchPoint> thin = Lasso.simplify(dense);
        assertTrue(thin.size() <= Lasso.MAX_HIT_VERTICES);
        assertTrue(thin.size() >= 3);
        assertTrue(Lasso.contains(thin, 200f, 200f));
        assertFalse(Lasso.contains(thin, 200f, 40f));
    }

    @Test
    public void densePageSelectStaysFastAndCorrect() {
        // One dense page of short strokes under a raw-rate lasso outline.
        ArrayList<InkRenderer.InkStroke> page = new ArrayList<>(900);
        InkRenderer.InkStroke centerDot = line(200f, 200f, 1);
        page.add(centerDot);
        for (int row = 0; row < 30; row++) {
            for (int col = 0; col < 30; col++) {
                page.add(line(40f + col * 36f, 40f + row * 42f, 8));
            }
        }
        // Far pages in the same notebook log must not change the answer or the cost class.
        for (int i = 0; i < 400; i++) {
            page.add(line(50f + (i % 20) * 10f, 5000f + i * 3f, 12));
        }
        List<TouchPoint> outline = denseCircle(200f, 200f, 110f, 1600);

        long t0 = System.nanoTime();
        List<InkRenderer.InkStroke> picked = Lasso.select(page, outline);
        long ms = (System.nanoTime() - t0) / 1_000_000L;

        assertTrue("dense lasso select took " + ms + "ms", ms < 80);
        assertTrue("center of the circle must be selected", picked.contains(centerDot));
        assertTrue("should catch a cluster of nearby strokes", picked.size() >= 20);
        for (InkRenderer.InkStroke stroke : picked) {
            assertTrue("far-page stroke must not be selected: y=" + stroke.points.get(0).y,
                    stroke.bounds.bottom < 400f);
        }
    }

    @Test
    public void clampIntoFreezesWhenSelectionTallerThanViewport() {
        // Cross-page box taller than the visible band: old clamp froze dy at 0.
        RectF tall = new RectF(40f, 100f, 200f, 2000f);
        float minY = 500f;
        float maxY = 1900f;
        float[] frozen = Lasso.clampInto(tall, 0f, 80f, 0f, minY, 1000f, maxY);
        assertEquals(0f, frozen[1], 0f);

        float[] moved = Lasso.clampIntoAllowingOverflow(tall, 0f, 80f, 0f, minY, 1000f, maxY);
        assertEquals("overflowing selection can still move toward fitting", 80f, moved[1], 0f);

        float[] up = Lasso.clampIntoAllowingOverflow(tall, 0f, -400f, 0f, minY, 1000f, maxY);
        assertTrue("can drag up to reduce bottom overflow", up[1] < 0f);
    }

    @Test
    public void clampIntoAllowingOverflowKeepsInBoundsSelectionInside() {
        RectF box = new RectF(100f, 800f, 300f, 1000f);
        float[] ok = Lasso.clampIntoAllowingOverflow(box, 40f, -30f, 0f, 500f, 1000f, 1900f);
        assertEquals(40f, ok[0], 0f);
        assertEquals(-30f, ok[1], 0f);
        float[] hitBottom = Lasso.clampIntoAllowingOverflow(box, 0f, 5000f, 0f, 500f, 1000f, 1900f);
        assertEquals(900f, hitBottom[1], 0f);
    }
}
