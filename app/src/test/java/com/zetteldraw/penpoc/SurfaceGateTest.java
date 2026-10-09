package com.zetteldraw.penpoc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.view.View;

import androidx.test.core.app.ApplicationProvider;

import com.onyx.android.sdk.data.note.TouchPoint;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The e-ink surface and the Onyx pen are only touched from the surface
 * worker, and only once the surface is really visible. A worker that is stuck
 * in the system must never stop the main thread.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class, shadows = IdleSurfaceViewShadow.class)
public class SurfaceGateTest {
    private final Context context = ApplicationProvider.getApplicationContext();
    /** The worker thread, run by hand. */
    private final List<Runnable> worker = new ArrayList<>();

    @Before
    public void setUp() {
        UiExecutors.useSynchronousForTest();
        UiExecutors.surfaceForTest = worker::add;
    }

    @After
    public void tearDown() {
        UiExecutors.surfaceForTest = null;
    }

    private PageInkView laidOut(Board... pages) {
        PageInkView ink = new PageInkView(context);
        ink.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY));
        ink.layout(0, 0, 600, 1000);
        int[] heights = new int[pages.length];
        Arrays.fill(heights, 1000);
        ink.setPages(Arrays.asList(pages), heights, 24);
        ink.setLive(true);
        return ink;
    }

    private void settle() {
        for (int i = 0; i < 10; i++) {
            ArrayList<Runnable> batch = new ArrayList<>(worker);
            worker.clear();
            for (Runnable r : batch) {
                r.run();
            }
            ShadowLooper.idleMainLooper();
        }
    }

    @Test
    public void nothingIsPaintedOrInkedUntilTheSurfaceIsVisible() {
        PageInkView ink = laidOut(new Board("a", 1L));
        settle();
        assertTrue("allowed in principle", ink.inkEnabled());
        assertFalse("but no surface yet", ink.penOn());
        assertEquals(0, ink.fullFramesPainted());

        ink.onSurface(true);
        settle();
        assertFalse("surface exists, window not on screen: still nothing", ink.penOn());
        assertEquals(0, ink.fullFramesPainted());

        ink.dispatchWindowVisibilityChanged(View.VISIBLE);
        assertFalse("the pen waits for the first full frame", ink.penOn());
        settle();
        assertEquals(1, ink.fullFramesPainted());
        assertTrue("pen on once the picture is up", ink.penOn());

        ink.dispatchWindowVisibilityChanged(View.GONE);
        assertFalse(ink.penOn());
        ink.dispatchWindowVisibilityChanged(View.VISIBLE);
        assertFalse("coming back repaints before inking", ink.penOn());
        settle();
        assertTrue(ink.penOn());
        assertEquals(2, ink.fullFramesPainted());
    }

    @Test
    public void aStuckSurfaceWorkerNeverBlocksTheMainThread() {
        Board a = new Board("a", 1L);
        Board b = new Board("b", 2L);
        PageInkView ink = laidOut(a, b);
        settle();
        ink.onSurface(true);
        ink.dispatchWindowVisibilityChanged(View.VISIBLE);
        settle();
        assertTrue(ink.penOn());

        UiExecutors.surfaceForTest = null;
        worker.clear();
        // From here the worker never runs again: every Onyx call and paint is stuck.
        long started = System.nanoTime();
        ink.setContentScrollY(800);
        ink.addStroke(points(100f, 900f));
        for (int i = 0; i < 50; i++) {
            ink.setContentScrollY(800 + i);
        }
        ShadowLooper.idleMainLooper(1_100, TimeUnit.MILLISECONDS);
        long ms = (System.nanoTime() - started) / 1_000_000;
        assertTrue("main thread kept going (" + ms + " ms)", ms < 1_000);
        assertEquals("the stroke drawn before the scroll landed where it was drawn", 900f,
                a.strokes.get(0).points.get(0).y, 0f);
        assertFalse("pen stays off: the worker never confirmed a repaint", ink.penOn());

        ink.addStroke(points(100f, 900f));
        assertEquals("the scroll still applied after the deadline", 1, b.strokes.size());
    }

    private static ArrayList<TouchPoint> points(float x, float y) {
        ArrayList<TouchPoint> list = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            list.add(new TouchPoint(x + i * 6f, y + i * 4f, 0.5f, 1f, 0, 0, 1L + i));
        }
        return list;
    }
}
