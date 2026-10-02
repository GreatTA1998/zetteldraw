package com.zetteldraw.penpoc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;

import androidx.test.core.app.ApplicationProvider;

import com.onyx.android.sdk.data.note.TouchPoint;
import com.onyx.android.sdk.pen.data.TouchPointList;
import com.zetteldraw.penpoc.data.TestStrokes;
import com.zetteldraw.penpoc.data.ZettelData;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Tool changes used to be applied on the surface worker while raw drawing stayed
 * on, so the Boox kept painting the previous tool, and an older style could be
 * written after a newer one. These tests fail on that code.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class, qualifiers = "w600dp-h1000dp",
        shadows = IdleSurfaceViewShadow.class)
public class ToolSwitchTest {
    private final Context context = ApplicationProvider.getApplicationContext();
    private final List<Runnable> worker = new ArrayList<>();
    private final Pen pen = new Pen();

    @Before
    public void setUp() {
        UiExecutors.useSynchronousForTest();
        UiExecutors.surfaceForTest = worker::add;
        SurfaceWorker.rawPenForTest = pen;
    }

    @After
    public void tearDown() {
        UiExecutors.surfaceForTest = null;
        SurfaceWorker.rawPenForTest = null;
    }

    @Test
    public void selectingEraserOrPenTakesEffectBeforeTheNextStroke() {
        PageInkView ink = show(laidOut(new Board("a", 1L)));
        settle();
        assertTrue(ink.penOn());
        assertTrue("pen draws ink", pen.style != null && pen.style.render && pen.style.brush);
        pen.styledWhileRaw = false;

        ink.setTool(PageInkView.Tool.ERASER);
        ShadowLooper.idleMainLooper();
        assertEquals(PageInkView.Tool.ERASER, ink.tool());
        assertFalse("the Boox must not keep drawing fountain ink while the eraser style is still queued",
                ink.penOn());

        settle();
        assertTrue("eraser is on before the next stroke", ink.penOn());
        assertTrue(pen.raw);
        assertFalse(pen.style.brush);
        assertFalse(pen.style.render);
        assertFalse("eraser style was written while raw drawing was still on", pen.styledWhileRaw);

        pen.styledWhileRaw = false;
        ink.setTool(PageInkView.Tool.PEN);
        ShadowLooper.idleMainLooper();
        assertEquals(PageInkView.Tool.PEN, ink.tool());
        assertFalse(ink.penOn());

        settle();
        assertTrue("pen is drawable again without an undo repaint", ink.penOn());
        assertTrue(pen.raw);
        assertTrue(pen.style.brush);
        assertTrue(pen.style.render);
        assertFalse("pen style was written while raw drawing was still on", pen.styledWhileRaw);
    }

    @Test
    public void aPenStrokeAlreadyDownStaysAPenStrokeWhenEraserIsTapped() {
        Board page = new Board("a", 1L);
        InkRenderer.InkStroke existing = TestStrokes.stroke(200f, 200f);
        page.strokes.add(existing);
        PageInkView ink = laidOut(page);
        ShadowLooper.idleMainLooper();

        ArrayList<TouchPoint> path = points(200f, 200f);
        // The SDK has already queued pen-down. The eraser tap is processed first by Android,
        // then that queued pen-down. The stroke still belongs to the pen.
        new Handler(Looper.getMainLooper()).post(() -> ink.rawInput().onBeginRawDrawing(false, path.get(0)));
        ink.setTool(PageInkView.Tool.ERASER);
        ShadowLooper.idleMainLooper();
        ink.rawInput().onRawDrawingTouchPointListReceived(list(path));

        assertEquals(PageInkView.Tool.ERASER, ink.tool());
        assertEquals("the pen stroke is kept and the ink under it is not erased", 2, page.strokes.size());
        assertSame(existing, page.strokes.get(0));
    }

    @Test
    public void anEraserStrokeAlreadyDownStaysAnEraseWhenPenIsTapped() {
        Board page = new Board("a", 1L);
        page.strokes.add(TestStrokes.stroke(200f, 200f));
        PageInkView ink = laidOut(page);
        ShadowLooper.idleMainLooper();

        ink.setTool(PageInkView.Tool.ERASER);
        ShadowLooper.idleMainLooper();
        ArrayList<TouchPoint> path = points(200f, 200f);
        ink.rawInput().onBeginRawDrawing(false, path.get(0));
        ink.setTool(PageInkView.Tool.PEN);
        ShadowLooper.idleMainLooper();
        ink.rawInput().onRawDrawingTouchPointListReceived(list(path));

        assertEquals(PageInkView.Tool.PEN, ink.tool());
        assertEquals("the stroke that started as the eraser still erases", 0, page.strokes.size());
    }

    @Test
    public void sideButtonEraseIsNotSavedAsAPenStrokeWhenTheButtonIsAlreadyUp() {
        Board page = new Board("a", 1L);
        page.strokes.add(TestStrokes.stroke(200f, 200f));
        PageInkView ink = laidOut(page);
        ShadowLooper.idleMainLooper();

        ArrayList<TouchPoint> path = points(200f, 200f);
        // Begin reports the side button down. End can report it up before the point list arrives.
        ink.rawInput().onBeginRawDrawing(true, path.get(0));
        ink.rawInput().onEndRawDrawing(false, path.get(0));
        ink.rawInput().onRawDrawingTouchPointListReceived(list(path));

        assertEquals(PageInkView.Tool.PEN, ink.tool());
        assertEquals("a side-button erase is not stored as a fountain stroke", 0, page.strokes.size());
    }

    @Test
    public void aLassoStrokeAlreadyDownIsNotStoredAsAPenStroke() {
        Board page = new Board("a", 1L);
        page.strokes.add(TestStrokes.stroke(200f, 200f));
        PageInkView ink = laidOut(page);
        ShadowLooper.idleMainLooper();
        ink.setTool(PageInkView.Tool.LASSO);
        ShadowLooper.idleMainLooper();

        ArrayList<TouchPoint> outline = new ArrayList<>();
        outline.add(new TouchPoint(150f, 150f, 0.5f, 1f, 0, 0, 1L));
        outline.add(new TouchPoint(300f, 150f, 0.5f, 1f, 0, 0, 2L));
        outline.add(new TouchPoint(300f, 300f, 0.5f, 1f, 0, 0, 3L));
        outline.add(new TouchPoint(150f, 300f, 0.5f, 1f, 0, 0, 4L));
        ink.rawInput().onBeginRawDrawing(false, outline.get(0));
        ink.setTool(PageInkView.Tool.PEN);
        ShadowLooper.idleMainLooper();
        ink.rawInput().onRawDrawingTouchPointListReceived(list(outline));

        assertEquals(PageInkView.Tool.PEN, ink.tool());
        assertEquals("the lasso outline is not inked as a pen stroke", 1, page.strokes.size());
    }

    @Test
    public void toolButtonsStayPressableAcrossEraserAndPen() {
        ZettelData.resetForTest();
        ActivityController<CanvasActivity> controller = Robolectric.buildActivity(CanvasActivity.class).setup();
        View root = controller.get().getWindow().getDecorView();
        ShadowLooper.idleMainLooper();

        View penButton = byDescription(root, "Pen").get(0);
        View eraserButton = byDescription(root, "Eraser").get(0);
        View lassoButton = byDescription(root, "Lasso").get(0);
        assertTrue(penButton.isEnabled());
        assertTrue(eraserButton.isEnabled());
        assertTrue(lassoButton.isEnabled());

        assertTrue(eraserButton.performClick());
        assertTrue(eraserButton.isSelected());
        assertTrue(penButton.isEnabled());
        assertTrue(eraserButton.isEnabled());
        assertTrue(lassoButton.isEnabled());

        PageInkView ink = findInk(root);
        ink.rawInput().onBeginRawDrawing(false, points(40f, 40f).get(0));
        assertTrue(penButton.performClick());
        assertTrue("pen can be selected again while a stroke is down", penButton.isSelected());
        assertFalse(eraserButton.isSelected());
        assertTrue(penButton.isEnabled());
        assertTrue(eraserButton.isEnabled());
        assertTrue(lassoButton.isEnabled());
        assertEquals(PageInkView.Tool.PEN, ink.tool());

        controller.pause().stop().destroy();
    }

    private PageInkView laidOut(Board... pages) {
        PageInkView ink = new PageInkView(context);
        ink.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY));
        ink.layout(0, 0, 600, 1000);
        int[] heights = new int[pages.length];
        Arrays.fill(heights, 1000);
        ink.setPages(Arrays.asList(pages), heights, 24);
        return ink;
    }

    private PageInkView show(PageInkView ink) {
        ink.setLive(true);
        ink.onSurface(true);
        ink.dispatchWindowVisibilityChanged(View.VISIBLE);
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

    private static ArrayList<TouchPoint> points(float x, float y) {
        ArrayList<TouchPoint> list = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            list.add(new TouchPoint(x + i * 6f, y + i * 4f, 0.5f, 1f, 0, 0, 1L + i));
        }
        return list;
    }

    private static TouchPointList list(List<TouchPoint> points) {
        TouchPointList list = new TouchPointList();
        for (TouchPoint point : points) {
            list.add(point);
        }
        return list;
    }

    private static List<View> byDescription(View view, String description) {
        List<View> out = new ArrayList<>();
        collect(view, description, out);
        return out;
    }

    private static void collect(View view, String description, List<View> out) {
        if (view.getVisibility() != View.VISIBLE) {
            return;
        }
        if (description.contentEquals(String.valueOf(view.getContentDescription()))) {
            out.add(view);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                collect(group.getChildAt(i), description, out);
            }
        }
    }

    private static PageInkView findInk(View view) {
        if (view instanceof PageInkView) {
            return (PageInkView) view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                PageInkView found = findInk(group.getChildAt(i));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** Records the order the firmware is told to draw. */
    private static final class Pen implements SurfaceWorker.RawPen {
        boolean raw;
        boolean styledWhileRaw;
        SurfaceWorker.Style style;

        @Override
        public void open(Rect limit, List<Rect> excludes) {
            raw = false;
        }

        @Override
        public void close() {
            raw = false;
        }

        @Override
        public void setLimit(Rect limit, List<Rect> excludes) {
        }

        @Override
        public void setHandwritingPenState(int state) {
        }

        @Override
        public void setStyle(SurfaceWorker.Style next) {
            if (raw) {
                styledWhileRaw = true;
            }
            style = next;
        }

        @Override
        public void setRawDrawingEnabled(boolean enabled) {
            raw = enabled;
        }

        @Override
        public void setRawDrawingRenderEnabled(boolean enabled) {
        }

        @Override
        public void enableSideBtnErase(boolean enabled) {
        }
    }
}
