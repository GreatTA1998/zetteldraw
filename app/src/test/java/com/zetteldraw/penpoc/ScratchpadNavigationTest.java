package com.zetteldraw.penpoc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import com.zetteldraw.penpoc.data.BoardRepository;
import com.zetteldraw.penpoc.data.RoomBoardRepository;
import com.zetteldraw.penpoc.data.TestStrokes;
import com.zetteldraw.penpoc.data.ZettelData;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.util.List;

/**
 * Scratchpad landing page: first open → trailing blank; return restores left-behind
 * scroll (v73 navigation intent / effectiveScrollY).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class, qualifiers = "w600dp-h1000dp",
        shadows = IdleSurfaceViewShadow.class)
public class ScratchpadNavigationTest {
    @Before
    public void freshRepository() {
        ZettelData.resetForTest();
        UiExecutors.useSynchronousForTest();
        ApplicationProvider.getApplicationContext()
                .getSharedPreferences("canvas", Context.MODE_PRIVATE)
                .edit()
                .clear()
                .apply();
    }

    @Test
    public void firstScratchpadOpenLandsOnTrailingBlank() {
        Application app = ApplicationProvider.getApplicationContext();
        BoardRepository repo = ZettelData.repository(app);
        int height = seedMultiPageScratchpad(app, repo);
        app.getSharedPreferences("canvas", Context.MODE_PRIVATE)
                .edit()
                .remove("scroll_scratchpad")
                .apply();

        ActivityController<CanvasActivity> controller = Robolectric.buildActivity(CanvasActivity.class).setup();
        View root = controller.get().getWindow().getDecorView();
        idle();
        idleForPaint();
        relayout(root);

        List<Board> pages = repo.scratchpadPages();
        assertTrue("Scratchpad must be multi-page, was " + pages.size(), pages.size() > 2);
        PageScroller scroller = (PageScroller) pageScroller(root);
        View column = pageColumn(root);
        assertTrue(column instanceof ViewGroup);
        ViewGroup slots = (ViewGroup) column;
        assertEquals(pages.size(), slots.getChildCount());
        View trailing = slots.getChildAt(slots.getChildCount() - 1);
        int lastPageTop = offsetIn(trailing, scroller);
        // ScrollView clamps to max = content - viewport; that may sit a few px
        // above the last slot top when the trailing blank is shorter than the viewport.
        int maxY = Math.max(0, column.getHeight() - scroller.getHeight());
        int expected = Math.min(lastPageTop, maxY);
        assertTrue("first open must not stay on page 1 (Y=0); was " + scroller.getScrollY(),
                scroller.getScrollY() > height);
        assertEquals("first open → trailing blank (clamped last page)",
                expected, scroller.getScrollY());

        controller.pause().stop().destroy();
    }

    @Test
    public void returningToScratchpadRestoresLeftBehindScroll() {
        Application app = ApplicationProvider.getApplicationContext();
        BoardRepository repo = ZettelData.repository(app);
        int height = seedMultiPageScratchpad(app, repo);
        app.getSharedPreferences("canvas", Context.MODE_PRIVATE)
                .edit()
                .remove("scroll_scratchpad")
                .apply();

        ActivityController<CanvasActivity> controller = Robolectric.buildActivity(CanvasActivity.class).setup();
        View root = controller.get().getWindow().getDecorView();
        idle();
        idleForPaint();
        relayout(root);

        View scroller = pageScroller(root);
        int leftBehind = height; // page 2 of a multi-page Scratchpad
        ((PageScroller) scroller).scrollTo(0, leftBehind);
        idle();
        assertEquals(leftBehind, scroller.getScrollY());

        assertTrue(find(root, "comedy").performClick());
        idle();
        idleForPaint();
        relayout(root);

        assertTrue(byDescription(root, "Scratchpad").get(0).performClick());
        idle();
        idleForPaint();
        relayout(root);

        scroller = pageScroller(root);
        assertEquals("return restores Scratchpad scroll, not page 0 or last page",
                leftBehind, scroller.getScrollY());
        assertNotNull(find(root, "2/" + repo.scratchpadPages().size()));

        controller.pause().stop().destroy();
    }

    @Test
    public void leaveWhilePendingScrollStillRemembersIntendedY() {
        Application app = ApplicationProvider.getApplicationContext();
        BoardRepository repo = ZettelData.repository(app);
        seedMultiPageScratchpad(app, repo);
        app.getSharedPreferences("canvas", Context.MODE_PRIVATE)
                .edit()
                .remove("scroll_scratchpad")
                .apply();

        ActivityController<CanvasActivity> controller = Robolectric.buildActivity(CanvasActivity.class).setup();
        View root = controller.get().getWindow().getDecorView();
        idle();
        // Do not relayout: pendingScrollY holds first-open last-page target while
        // ScrollView may still read 0. Switching away must not persist Y=0.
        assertTrue(find(root, "comedy").performClick());
        idle();

        int saved = app.getSharedPreferences("canvas", Context.MODE_PRIVATE)
                .getInt("scroll_scratchpad", -1);
        assertTrue("leave while pending must remember last-page Y, not 0; was " + saved,
                saved > 0);

        controller.pause().stop().destroy();
    }

    /** Multi-page Scratchpad with ink on pages 1–3 and a tear so slice count stays. */
    private static int seedMultiPageScratchpad(Application app, BoardRepository repo) {
        ActivityController<CanvasActivity> probe = Robolectric.buildActivity(CanvasActivity.class).setup();
        View root = probe.get().getWindow().getDecorView();
        idle();
        Board blank = repo.scratchpadPages().get(0);
        int height = blank.slicePx;
        assertTrue(height > 40);
        blank.paper.appendStroke(TestStrokes.stroke(12f, 20f), blank.id);
        blank.paper.appendStroke(TestStrokes.stroke(12f, height + 20f));
        blank.paper.appendStroke(TestStrokes.stroke(12f, 2f * height + 20f));
        blank.paper.tearDelete(1);
        repo.saveInk(blank);
        idle();
        ((RoomBoardRepository) repo).forgetSheetForTest(null);
        probe.pause().stop().destroy();
        return height;
    }

    private static View pageColumn(View view) {
        if (view instanceof PageScroller) {
            PageScroller scroller = (PageScroller) view;
            if (scroller.getChildCount() > 0) {
                return scroller.getChildAt(0);
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = pageColumn(group.getChildAt(i));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static int offsetIn(View view, View ancestor) {
        int top = 0;
        View current = view;
        while (current != null && current != ancestor) {
            top += current.getTop();
            current = current.getParent() instanceof View ? (View) current.getParent() : null;
        }
        return top;
    }

    private static View pageScroller(View view) {
        if (view instanceof PageScroller) {
            return view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = pageScroller(group.getChildAt(i));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static void relayout(View root) {
        root.measure(
                View.MeasureSpec.makeMeasureSpec(root.getWidth(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(root.getHeight(), View.MeasureSpec.EXACTLY));
        root.layout(0, 0, root.getWidth(), root.getHeight());
        idle();
    }

    private static void idle() {
        ShadowLooper.idleMainLooper();
    }

    private static void idleForPaint() {
        ShadowLooper.shadowMainLooper().idleFor(java.time.Duration.ofSeconds(2));
    }

    private static TextView find(View root, String text) {
        return textIn(root, text);
    }

    private static TextView textIn(View view, String text) {
        if (view.getVisibility() != View.VISIBLE) {
            return null;
        }
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) {
            return (TextView) view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = textIn(group.getChildAt(i), text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static java.util.List<View> byDescription(View view, String description) {
        java.util.ArrayList<View> out = new java.util.ArrayList<>();
        collectByDescription(view, description, out);
        return out;
    }

    private static void collectByDescription(View view, String description, java.util.List<View> out) {
        if (view.getVisibility() != View.VISIBLE) {
            return;
        }
        if (description.contentEquals(String.valueOf(view.getContentDescription()))) {
            out.add(view);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                collectByDescription(group.getChildAt(i), description, out);
            }
        }
    }
}
