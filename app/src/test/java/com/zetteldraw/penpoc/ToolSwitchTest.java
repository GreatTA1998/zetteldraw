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
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import com.onyx.android.sdk.data.note.TouchPoint;
import com.onyx.android.sdk.pen.TouchHelper;
import com.onyx.android.sdk.pen.data.TouchPointList;
import com.zetteldraw.penpoc.data.TestInk;
import com.zetteldraw.penpoc.data.BoardRepository;
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
import java.util.concurrent.TimeUnit;

/**
 * {@code setRawDrawingEnabled(true)} restores the default pen. These tests fail
 * if that enable lands after the eraser switches, or if a stroke already down
 * is reclassified when the tool changes.
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
    public void eraserSnapshotLeavesBrushAndRenderOffWithNoEnableAfterThem() {
        PageInkView ink = show(laidOut(new Board("a", 1L)));
        settle();
        assertTrue(ink.penOn());
        assertTrue(pen.raw);
        assertTrue(pen.brush);
        assertTrue(pen.render);

        ink.setTool(PageInkView.Tool.ERASER);
        ShadowLooper.idleMainLooper();
        assertEquals(PageInkView.Tool.ERASER, ink.tool());
        // A full bitmap frame holds the pen off until frozen ink is re-pushed
        // (scribble render just dropped). After that frame, drawing comes back on.

        settle();
        assertTrue("raw drawing stays on for an eraser drag once the frame is up", ink.penOn());
        assertTrue(pen.raw);
        assertFalse(pen.brush);
        assertFalse(pen.render);
        assertTrue("the eraser switches are written, and no enable follows them",
                pen.lastEnable < pen.lastOffSwitches);

        ink.setTool(PageInkView.Tool.PEN);
        ShadowLooper.idleMainLooper();
        settle();
        assertTrue(ink.penOn());
        assertTrue(pen.raw);
        assertTrue(pen.brush);
        assertTrue(pen.render);
        assertTrue(pen.lastStyle > pen.lastEnable);
    }

    @Test
    public void penToLassoAppliesEvenWhenThePenStyleIsCachedAndDrawingIsOff() {
        PageInkView ink = show(laidOut(new Board("a", 1L)));
        settle();
        assertEquals(TouchHelper.STROKE_STYLE_FOUNTAIN, pen.strokeStyle);
        assertTrue(pen.raw);

        ink.hold(PageInkView.Hold.SCROLL);
        settle();
        assertFalse(ink.penOn());
        assertEquals(TouchHelper.STROKE_STYLE_FOUNTAIN, pen.strokeStyle);

        ink.setTool(PageInkView.Tool.LASSO);
        ShadowLooper.idleMainLooper();
        settle();
        assertEquals(PageInkView.Tool.LASSO, ink.tool());
        assertEquals("a cached pen style must not skip the lasso dash",
                TouchHelper.STROKE_STYLE_DASH, pen.strokeStyle);
        assertFalse(pen.raw);
        assertTrue(pen.lastStyle > pen.lastEnable);

        ink.release(PageInkView.Hold.SCROLL);
        settle();
        assertTrue(ink.penOn());
        assertTrue(pen.raw);
        assertTrue(pen.render);
        assertEquals(TouchHelper.STROKE_STYLE_DASH, pen.strokeStyle);
        assertTrue(pen.lastStyle > pen.lastEnable);
    }

    @Test
    public void finishingALassoMoveKeepsLassoToolAndDash() {
        Board page = new Board("a", 1L);
        page.strokes.add(TestStrokes.stroke(200f, 200f));
        PageInkView ink = show(laidOut(page));
        ink.setListener(new PageInkView.Listener() {
            @Override
            public void onPageChanged(Board changed) {
            }

            @Override
            public void onPageBecameNonEmpty(Board changed) {
            }

            @Override
            public void onLassoSelected(int selected) {
            }

            @Override
            public void onLassoMoved(Lasso.Move move) {
                // Activity no longer forces Pen after a move (Boox Notes).
            }

            @Override
            public void onLassoCancelled() {
            }

            @Override
            public void onHistoryChanged() {
            }
        });
        settle();
        ink.setTool(PageInkView.Tool.LASSO);
        ShadowLooper.idleMainLooper();
        settle();
        assertEquals(TouchHelper.STROKE_STYLE_DASH, pen.strokeStyle);

        ink.finishLasso(outlineAround(200f, 200f));
        ShadowLooper.idleMainLooper();
        assertTrue("the outline selects the stroke", ink.hasSelection());
        settle();

        touch(ink, MotionEvent.ACTION_DOWN, 210f, 205f);
        touch(ink, MotionEvent.ACTION_MOVE, 260f, 235f);
        touch(ink, MotionEvent.ACTION_UP, 310f, 285f);
        settle();

        assertEquals("after a lasso move the tool stays LASSO", PageInkView.Tool.LASSO, ink.tool());
        assertTrue("selection stays active after move (Notes)", ink.hasSelection());
        assertEquals(TouchHelper.STROKE_STYLE_DASH, pen.strokeStyle);
        assertFalse("pen stays paused while the group is still selected", ink.penOn());

        // Tap outside to dismiss, then drawing resumes as Lasso.
        touch(ink, MotionEvent.ACTION_DOWN, 20f, 900f);
        touch(ink, MotionEvent.ACTION_UP, 20f, 900f);
        settle();
        assertFalse(ink.hasSelection());

        ShadowLooper.idleMainLooper(600, TimeUnit.MILLISECONDS);
        settle();
        assertTrue("drawing resumes as the lasso", ink.penOn());
        assertTrue(pen.raw);
        assertTrue(pen.brush);
        assertTrue(pen.render);
        assertEquals(TouchHelper.STROKE_STYLE_DASH, pen.strokeStyle);
    }

    @Test
    public void leavingLassoForPenWritesFountainLast() {
        Board page = new Board("a", 1L);
        page.strokes.add(TestStrokes.stroke(200f, 200f));
        PageInkView ink = show(laidOut(page));
        settle();
        ink.setTool(PageInkView.Tool.LASSO);
        ShadowLooper.idleMainLooper();
        settle();
        assertEquals(TouchHelper.STROKE_STYLE_DASH, pen.strokeStyle);

        ink.setTool(PageInkView.Tool.PEN);
        ShadowLooper.idleMainLooper();
        settle();
        assertEquals(PageInkView.Tool.PEN, ink.tool());
        assertEquals("leaving the lasso replaces the dash before the next stroke",
                TouchHelper.STROKE_STYLE_FOUNTAIN, pen.strokeStyle);
        assertTrue("raw drawing coming back on must not restore the lasso dash",
                pen.lastStyle > pen.lastEnable);
    }

    @Test
    public void sideButtonIsASnapshotChangeAndDoesNotEnableRawDrawing() {
        PageInkView ink = show(laidOut(new Board("a", 1L)));
        settle();
        int enable = pen.lastEnable;
        TouchPoint point = points(40f, 40f).get(0);
        ink.rawInput().onBeginRawDrawing(true, point);
        settle();
        assertEquals(enable, pen.lastEnable);
        assertTrue(pen.raw);
        assertTrue(pen.brush);
        assertFalse(pen.render);

        ink.rawInput().onRawDrawingTouchPointListReceived(list(points(40f, 40f)));
        ink.rawInput().onPenUpRefresh(null);
        settle();
        assertEquals(enable, pen.lastEnable);
        assertTrue(pen.brush);
        assertTrue(pen.render);
    }

    @Test
    public void selectingEraserKeepsStrokesAndRepaintsFrozenInk() {
        Board page = new Board("a", 1L);
        page.strokes.add(TestStrokes.stroke(80f, 120f));
        page.strokes.add(TestStrokes.stroke(220f, 340f));
        page.strokes.add(TestStrokes.stroke(400f, 500f));
        PageInkView ink = show(laidOut(page));
        settle();
        int framesBefore = ink.fullFramesPainted();
        assertEquals(3, page.strokes.size());
        assertTrue(pen.render);

        ink.setTool(PageInkView.Tool.ERASER);
        ShadowLooper.idleMainLooper();
        settle();

        assertEquals(PageInkView.Tool.ERASER, ink.tool());
        assertEquals("selecting the eraser must not delete strokes", 3, page.strokes.size());
        assertFalse(pen.brush);
        assertFalse(pen.render);
        assertTrue("turning scribble render off must re-push frozen bitmaps",
                ink.fullFramesPainted() > framesBefore);
        assertTrue("raw drawing stays on so the next erase drag works", ink.penOn());
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

    /**
     * Regression (Elton 2026-10-11): after Pen, tapping Lasso must move the
     * underline and the ink tool enum together, and TouchHelper must get DASH.
     * ToolController is the single setTool path for chrome + PageInkView.
     */
    @Test
    public void penToLassoUpdatesChromeAndToolEnumTogether() {
        ZettelData.resetForTest();
        ActivityController<CanvasActivity> controller = Robolectric.buildActivity(CanvasActivity.class).setup();
        View root = controller.get().getWindow().getDecorView();
        settle();

        View penButton = byDescription(root, "Pen").get(0);
        View lassoButton = byDescription(root, "Lasso").get(0);
        PageInkView ink = findInk(root);
        assertNotNull(ink);
        // Activity setup alone may not open the surface; arm it so style writes reach the mock pen.
        ink.onSurface(true);
        ink.dispatchWindowVisibilityChanged(View.VISIBLE);
        settle();

        assertTrue(penButton.isSelected());
        assertFalse(lassoButton.isSelected());
        assertEquals(PageInkView.Tool.PEN, ink.tool());

        assertTrue(lassoButton.performClick());
        ShadowLooper.idleMainLooper();
        settle();

        assertTrue("Lasso underline/selected affordance moves with the tool",
                lassoButton.isSelected());
        assertFalse("Pen underline clears when Lasso is selected", penButton.isSelected());
        assertEquals("ink tool enum matches chrome", PageInkView.Tool.LASSO, ink.tool());
        assertEquals("TouchHelper brush follows the same tool",
                TouchHelper.STROKE_STYLE_DASH, pen.strokeStyle);

        // Back to Pen, then re-tap Pen (color-menu path reasserts fountain).
        assertTrue(penButton.performClick());
        ShadowLooper.idleMainLooper();
        settle();
        assertTrue(penButton.isSelected());
        assertEquals(PageInkView.Tool.PEN, ink.tool());
        assertEquals(TouchHelper.STROKE_STYLE_FOUNTAIN, pen.strokeStyle);

        assertTrue("re-tap Pen while already selected", penButton.performClick());
        ShadowLooper.idleMainLooper();
        settle();
        assertTrue(penButton.isSelected());
        assertFalse(lassoButton.isSelected());
        assertEquals(PageInkView.Tool.PEN, ink.tool());
        assertEquals(TouchHelper.STROKE_STYLE_FOUNTAIN, pen.strokeStyle);

        // Lasso must still move chrome + enum after the Pen color-menu path.
        assertTrue(lassoButton.performClick());
        ShadowLooper.idleMainLooper();
        settle();
        assertTrue(lassoButton.isSelected());
        assertFalse(penButton.isSelected());
        assertEquals(PageInkView.Tool.LASSO, ink.tool());
        assertEquals(TouchHelper.STROKE_STYLE_DASH, pen.strokeStyle);

        controller.pause().stop().destroy();
    }

    @Test
    public void toolControllerKeepsChromeSyncedWithInkTool() {
        PageInkView ink = show(laidOut(new Board("a", 1L)));
        settle();
        final ImageButton[] buttons = new ImageButton[3];
        buttons[0] = new ImageButton(context);
        buttons[1] = new ImageButton(context);
        buttons[2] = new ImageButton(context);
        final int[] afterCalls = {0};
        ToolController tools = new ToolController(ink, new ToolController.Host() {
            @Override
            public void styleToolButton(ImageButton button, boolean selected) {
                button.setSelected(selected);
            }

            @Override
            public void onAfterToolChanged(PageInkView.Tool tool) {
                afterCalls[0]++;
                // Host must not be able to leave chrome pointing at a different tool.
                if (tool == PageInkView.Tool.LASSO) {
                    buttons[0].setSelected(true);
                    buttons[2].setSelected(false);
                }
            }
        });
        tools.bind(buttons[0], buttons[1], buttons[2], null);

        tools.setTool(PageInkView.Tool.LASSO);
        ShadowLooper.idleMainLooper();
        settle();
        assertEquals(1, afterCalls[0]);
        assertEquals(PageInkView.Tool.LASSO, tools.tool());
        assertEquals(PageInkView.Tool.LASSO, ink.tool());
        assertFalse("setTool re-syncs chrome after host side effects", buttons[0].isSelected());
        assertFalse(buttons[1].isSelected());
        assertTrue(buttons[2].isSelected());
        assertEquals(TouchHelper.STROKE_STYLE_DASH, pen.strokeStyle);

        tools.setTool(PageInkView.Tool.PEN);
        ShadowLooper.idleMainLooper();
        settle();
        assertEquals(PageInkView.Tool.PEN, tools.tool());
        assertEquals(PageInkView.Tool.PEN, ink.tool());
        assertTrue(buttons[0].isSelected());
        assertFalse(buttons[2].isSelected());
        assertEquals(TouchHelper.STROKE_STYLE_FOUNTAIN, pen.strokeStyle);
    }

    /**
     * Regression (Elton 2026-10-12): after a lasso move the tool stays LASSO
     * (chrome + enum + DASH), matching Boox Notes — not an auto-switch to Pen.
     */
    @Test
    public void afterLassoMoveToolRemainsLassoInActivity() {
        ZettelData.resetForTest();
        Application app = ApplicationProvider.getApplicationContext();
        BoardRepository repo = ZettelData.repository(app);
        assertTrue(repo.ensureSheet(null, 800, 800, 0L));
        if (repo.scratchpadPages().isEmpty()) {
            repo.createPage(null);
        }
        Board page = repo.scratchpadPages().get(0);
        TestInk.appendCovering(page.paper, TestStrokes.stroke(200f, page.paperOrigin + 200f), page.id);
        repo.saveInk(page);
        BoardRepository.NotebookInfo notebook = repo.createNotebook("stay-lasso");
        assertTrue(repo.ensureSheet(notebook.id(), 800, 800, 0L));
        repo.movePageToNotebook(page.id, notebook.id());

        ActivityController<CanvasActivity> controller = Robolectric.buildActivity(CanvasActivity.class).setup();
        View root = controller.get().getWindow().getDecorView();
        settle();
        TextView tab = findText(root, "stay-lasso");
        assertNotNull(tab);
        assertTrue(tab.performClick());
        settle();

        View penButton = byDescription(root, "Pen").get(0);
        View lassoButton = byDescription(root, "Lasso").get(0);
        PageInkView ink = findInk(root);
        assertNotNull(ink);
        ink.onSurface(true);
        ink.dispatchWindowVisibilityChanged(View.VISIBLE);
        settle();

        assertTrue(lassoButton.performClick());
        ShadowLooper.idleMainLooper();
        settle();
        assertTrue(lassoButton.isSelected());
        assertFalse(penButton.isSelected());
        assertEquals(PageInkView.Tool.LASSO, ink.tool());

        ink.finishLasso(outlineAround(200f, 200f));
        ShadowLooper.idleMainLooper();
        settle();
        assertTrue(ink.hasSelection());

        touch(ink, MotionEvent.ACTION_DOWN, 210f, 205f);
        touch(ink, MotionEvent.ACTION_MOVE, 260f, 235f);
        touch(ink, MotionEvent.ACTION_UP, 310f, 285f);
        settle();

        assertTrue("selection stays after move", ink.hasSelection());
        assertEquals(PageInkView.Tool.LASSO, ink.tool());
        assertTrue("underline stays on Lasso after move", lassoButton.isSelected());
        assertFalse(penButton.isSelected());
        assertEquals(TouchHelper.STROKE_STYLE_DASH, pen.strokeStyle);

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

    private static ArrayList<TouchPoint> outlineAround(float x, float y) {
        ArrayList<TouchPoint> outline = new ArrayList<>();
        outline.add(new TouchPoint(x - 40f, y - 40f, 0.5f, 1f, 0, 0, 1L));
        outline.add(new TouchPoint(x + 40f, y - 40f, 0.5f, 1f, 0, 0, 2L));
        outline.add(new TouchPoint(x + 40f, y + 40f, 0.5f, 1f, 0, 0, 3L));
        outline.add(new TouchPoint(x - 40f, y + 40f, 0.5f, 1f, 0, 0, 4L));
        return outline;
    }

    private static void touch(View target, int action, float x, float y) {
        long now = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(now, now, action, x, y, 0);
        target.dispatchTouchEvent(event);
        event.recycle();
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

    private static TextView findText(View view, String text) {
        if (view.getVisibility() != View.VISIBLE) {
            return null;
        }
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) {
            return (TextView) view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = findText(group.getChildAt(i), text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
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

    /**
     * Models the Boox: {@code setRawDrawingEnabled(true)} turns brush and render
     * back on, the way {@code resetPenDefaultRawDrawing} does.
     */
    private static final class Pen implements SurfaceWorker.RawPen {
        boolean raw;
        boolean brush;
        boolean render;
        int strokeStyle = -1;
        int strokeColor = 0;
        private int seq;
        /** Sequence number of the last {@code setRawDrawingEnabled(true)}, or -1. */
        int lastEnable = -1;
        /** Sequence number of the last style write, or -1. */
        int lastStyle = -1;
        /** Sequence number of the last style write with brush and render off, or -1. */
        int lastOffSwitches = -1;

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
            strokeStyle = next.strokeStyle;
            strokeColor = next.color;
            brush = next.brush;
            render = next.render;
            seq++;
            lastStyle = seq;
            if (!next.brush && !next.render) {
                lastOffSwitches = seq;
            }
        }

        @Override
        public void setRawDrawingEnabled(boolean enabled) {
            raw = enabled;
            seq++;
            if (enabled) {
                brush = true;
                render = true;
                lastEnable = seq;
            }
        }

        @Override
        public void setRawDrawingRenderEnabled(boolean enabled) {
            render = enabled;
        }

        @Override
        public void enableSideBtnErase(boolean enabled) {
        }
    }
}
