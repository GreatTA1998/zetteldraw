package com.zetteldraw.penpoc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

import androidx.test.core.app.ApplicationProvider;

import com.onyx.android.sdk.data.note.TouchPoint;
import com.zetteldraw.penpoc.data.BoardRepository;
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
 * First ink on the trailing blank must grow a new blank without a full-frame
 * pen-off flash (the old reloadPages path).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class, qualifiers = "w600dp-h1000dp",
        shadows = IdleSurfaceViewShadow.class)
public class TrailingBlankAppendTest {
    private final Context context = ApplicationProvider.getApplicationContext();
    private final List<Runnable> worker = new ArrayList<>();

    @Before
    public void setUp() {
        ZettelData.resetForTest();
        UiExecutors.useSynchronousForTest();
        UiExecutors.surfaceForTest = worker::add;
    }

    @After
    public void tearDown() {
        UiExecutors.surfaceForTest = null;
    }

    @Test
    public void extendPagesKeepsThePenLiveAndSkipsAFullFrame() {
        Board page = new Board("a", 1L);
        Board blank = new Board("blank", 2L);
        PageInkView ink = laidOut(page);
        ink.setLive(true);
        ink.onSurface(true);
        ink.dispatchWindowVisibilityChanged(View.VISIBLE);
        settle();
        assertTrue(ink.penOn());
        int frames = ink.fullFramesPainted();

        ink.extendPages(Arrays.asList(page, blank), new int[]{1000, 1000}, 24);
        ShadowLooper.idleMainLooper();
        settle();

        assertEquals("appending a blank must not full-repaint", frames, ink.fullFramesPainted());
        assertTrue("the pen stays on through the append", ink.penOn());
    }

    @Test
    public void setPagesThatRemapStillFullRepaints() {
        Board a = new Board("a", 1L);
        Board b = new Board("b", 2L);
        PageInkView ink = laidOut(a);
        ink.setLive(true);
        ink.onSurface(true);
        ink.dispatchWindowVisibilityChanged(View.VISIBLE);
        settle();
        int frames = ink.fullFramesPainted();

        ink.setPages(Arrays.asList(b), new int[]{1000}, 24);
        ShadowLooper.idleMainLooper();
        settle();

        assertTrue("a real remap still paints a new picture", ink.fullFramesPainted() > frames);
    }

    @Test
    public void firstScratchpadStrokeGrowsABlankWithoutAFullFrameFlash() {
        ActivityController<CanvasActivity> controller = Robolectric.buildActivity(CanvasActivity.class).setup();
        View root = controller.get().getWindow().getDecorView();
        idle();
        PageInkView ink = findInk(root);
        ink.onSurface(true);
        ink.dispatchWindowVisibilityChanged(View.VISIBLE);
        settle();
        assertTrue(ink.penOn());
        int frames = ink.fullFramesPainted();
        BoardRepository repo = ZettelData.repository(controller.get());
        assertEquals(1, repo.scratchpadPages().size());

        ArrayList<TouchPoint> stroke = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            stroke.add(new TouchPoint(80f + i * 6f, 120f + i * 4f, 0.5f, 1f, 0, 0, 1L + i));
        }
        ink.addStroke(stroke);
        idle();
        settle();

        assertEquals(2, repo.scratchpadPages().size());
        assertEquals(Arrays.asList("1/2", "2/2"), pageLabels(root));
        assertEquals("first ink must not flash a full frame", frames, ink.fullFramesPainted());
        assertTrue("drawing stays live after the blank appears", ink.penOn());
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

    private static void idle() {
        ShadowLooper.idleMainLooper();
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

    private static List<String> pageLabels(View root) {
        ArrayList<String> labels = new ArrayList<>();
        collectPageLabels(root, labels);
        return labels;
    }

    private static void collectPageLabels(View view, List<String> out) {
        CharSequence desc = view.getContentDescription();
        if (desc != null && desc.toString().startsWith("Page ") && desc.toString().contains(" of ")) {
            String text = desc.toString().replace("Page ", "").replace(" of ", "/");
            out.add(text);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                collectPageLabels(group.getChildAt(i), out);
            }
        }
    }
}
