package com.zetteldraw.penpoc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.graphics.Rect;
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

import java.time.Duration;
import java.util.List;

/** Link stores the two page ids. The line under the bars and the arrow lines follow the page in front. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class, qualifiers = "w600dp-h1000dp",
        shadows = IdleSurfaceViewShadow.class)
public class PageLinksTest {
    @Before
    public void freshRepository() {
        ZettelData.resetForTest();
        UiExecutors.useSynchronousForTest();
    }

    @Test
    public void startingALinkOnALargeNotebookDoesNotReadPages() {
        Application app = ApplicationProvider.getApplicationContext();
        BoardRepository repo = ZettelData.repository(app);
        BoardRepository.NotebookInfo dense = repo.createNotebook("dense");
        BoardRepository.NotebookInfo otherA = repo.createNotebook("other-a");
        BoardRepository.NotebookInfo otherB = repo.createNotebook("other-b");

        ActivityController<CanvasActivity> controller = Robolectric.buildActivity(CanvasActivity.class).setup();
        View root = controller.get().getWindow().getDecorView();
        openNotebook(root, "dense");
        int height = repo.pages(dense.id).get(0).slicePx;
        assertTrue(height > 40);
        List<Board> pages = repo.pages(dense.id);
        pages.get(0).paper.appendStroke(TestStrokes.stroke(3f, 39f * height + 10f), pages.get(0).id);
        repo.saveInk(pages.get(0));
        openNotebook(root, "dense");
        assertEquals(41, repo.pages(dense.id).size());

        // Logs on disk, sheets dropped. The old press replayed each of them once and cached the
        // sheet, so the first press froze and the tenth did not.
        RoomBoardRepository room = (RoomBoardRepository) repo;
        room.ensureSheet(otherA.id, height, height, 1L);
        room.ensureSheet(otherB.id, height, height, 1L);
        ink(repo, otherA.id, 10f);
        ink(repo, otherB.id, 30f * height + 10f);
        room.forgetSheetForTest(otherA.id);
        room.forgetSheetForTest(otherB.id);

        TextView control = textIn((View) find(root, "40/41").getParent(), "Link");
        assertNotNull(control);
        View slot = (View) control.getParent();
        View area = (View) pageScroller(root).getParent();
        int areaBefore = area.getHeight();
        int reads = RoomBoardRepository.notebookReads.get();
        int replays = RoomBoardRepository.logReplays.get();
        int reflows = CanvasActivity.pageReflows.get();
        long worst = 0;
        for (int press = 1; press <= 10; press++) {
            long start = System.nanoTime();
            assertTrue(control.performClick());
            idle();
            relayout(root);
            long ms = (System.nanoTime() - start) / 1_000_000L;
            if (ms > worst) {
                worst = ms;
            }
            assertEquals("press " + press + " walked a notebook", reads, RoomBoardRepository.notebookReads.get());
            assertEquals("press " + press + " replayed an ink log", replays, RoomBoardRepository.logReplays.get());
            assertEquals("press " + press + " reflowed the notebook", reflows, CanvasActivity.pageReflows.get());
            assertTrue("press " + press + " did not open the linking line", area.getHeight() < areaBefore);
            assertSame("press " + press + " rebuilt the page list", slot, control.getParent());
            assertNotNull(find(root, "Linking from dense 40/41"));
            assertNull(find(root, "Confirm"));
            click(root, "Cancel");
            idle();
            relayout(root);
            assertEquals("cancel " + press + " walked a notebook", reads, RoomBoardRepository.notebookReads.get());
            assertEquals("cancel " + press + " replayed an ink log", replays, RoomBoardRepository.logReplays.get());
            assertEquals("cancel " + press + " reflowed the notebook", reflows, CanvasActivity.pageReflows.get());
            assertEquals("cancel " + press + " left the linking line's space", areaBefore, area.getHeight());
            assertSame("cancel " + press + " rebuilt the page list", slot, control.getParent());
        }
        assertTrue("the slowest Link press took " + worst + " ms", worst < 200);

        controller.pause().stop().destroy();
    }

    @Test
    public void confirmCreatesOneLinkAndBothDirectionsRender() {
        Application app = ApplicationProvider.getApplicationContext();
        BoardRepository repo = ZettelData.repository(app);
        BoardRepository.NotebookInfo from = repo.createNotebook("frombook");
        BoardRepository.NotebookInfo to = repo.createNotebook("tobook");

        ActivityController<CanvasActivity> controller = Robolectric.buildActivity(CanvasActivity.class).setup();
        View root = controller.get().getWindow().getDecorView();
        openNotebook(root, "frombook");
        openNotebook(root, "tobook");
        ink(repo, from.id, 10f);
        ink(repo, to.id, 10f);
        openNotebook(root, "frombook");

        clickOnPage(root, "1/2", "Link");
        assertNotNull(find(root, "Link"));
        assertNotNull(find(root, "Linking from frombook 1/2"));
        assertNull("confirm stays hidden on the source page", find(root, "Confirm"));

        PageInkView ink = findInk(root);
        relayout(root);
        assertNotNull("the linking line is a hole in the pen reader", cover(ink, linkingBar(root), ink.penExcludes()));

        openNotebook(root, "tobook");
        assertNotNull(find(root, "Link from frombook 1/2 to tobook 1/2"));
        assertNotNull(find(root, "Confirm"));
        click(root, "Confirm");

        String sourceId = repo.pages(from.id).get(0).id;
        String targetId = repo.pages(to.id).get(0).id;
        assertEquals(1, repo.linksTouching(sourceId).size());
        assertEquals(targetId, repo.linksTouching(sourceId).get(0).targetId);
        assertNotNull(find(root, "← frombook 1/2"));
        relayout(root);
        assertNotNull("the arrow line is a hole in the pen reader",
                cover(ink, find(root, "← frombook 1/2"), ink.penExcludes()));

        openNotebook(root, "frombook");
        assertNotNull(find(root, "→ tobook 1/2"));
        assertNull(find(root, "← tobook 1/2"));

        controller.pause().stop().destroy();
    }

    @Test
    public void confirmStaysHiddenWhenThePageInFrontIsTheSource() {
        Application app = ApplicationProvider.getApplicationContext();
        BoardRepository repo = ZettelData.repository(app);
        BoardRepository.NotebookInfo from = repo.createNotebook("frombook");
        BoardRepository.NotebookInfo to = repo.createNotebook("tobook");

        ActivityController<CanvasActivity> controller = Robolectric.buildActivity(CanvasActivity.class).setup();
        View root = controller.get().getWindow().getDecorView();
        openNotebook(root, "frombook");
        openNotebook(root, "tobook");
        ink(repo, from.id, 10f);
        ink(repo, to.id, 10f);
        openNotebook(root, "frombook");

        clickOnPage(root, "1/2", "Link");
        assertNull(find(root, "Confirm"));
        openNotebook(root, "tobook");
        assertNotNull(find(root, "Confirm"));
        openNotebook(root, "frombook");
        assertNotNull(find(root, "Linking from frombook 1/2"));
        assertNull("back on the source, confirm is gone", find(root, "Confirm"));

        controller.pause().stop().destroy();
    }

    @Test
    public void cancelStoresNothing() {
        Application app = ApplicationProvider.getApplicationContext();
        BoardRepository repo = ZettelData.repository(app);
        BoardRepository.NotebookInfo from = repo.createNotebook("frombook");
        BoardRepository.NotebookInfo to = repo.createNotebook("tobook");

        ActivityController<CanvasActivity> controller = Robolectric.buildActivity(CanvasActivity.class).setup();
        View root = controller.get().getWindow().getDecorView();
        openNotebook(root, "frombook");
        openNotebook(root, "tobook");
        ink(repo, from.id, 10f);
        ink(repo, to.id, 10f);
        openNotebook(root, "frombook");
        clickOnPage(root, "1/2", "Link");
        openNotebook(root, "tobook");
        assertNotNull(find(root, "Confirm"));
        click(root, "Cancel");

        assertNull(find(root, "Linking from frombook 1/2"));
        assertNull(find(root, "Confirm"));
        assertTrue(repo.linksTouching(repo.pages(from.id).get(0).id).isEmpty());
        assertTrue(repo.linksTouching(repo.pages(to.id).get(0).id).isEmpty());

        controller.pause().stop().destroy();
    }

    @Test
    public void displayedNumberChangesWhenAPageAboveTheTargetIsDeleted() {
        Application app = ApplicationProvider.getApplicationContext();
        BoardRepository repo = ZettelData.repository(app);
        BoardRepository.NotebookInfo from = repo.createNotebook("frombook");
        BoardRepository.NotebookInfo to = repo.createNotebook("tobook");

        ActivityController<CanvasActivity> controller = Robolectric.buildActivity(CanvasActivity.class).setup();
        View root = controller.get().getWindow().getDecorView();
        openNotebook(root, "frombook");
        openNotebook(root, "tobook");
        int height = repo.pages(to.id).get(0).slicePx;
        ink(repo, from.id, 10f);
        ink(repo, to.id, height + 10f);
        assertEquals(3, repo.pages(to.id).size());
        openNotebook(root, "frombook");
        clickOnPage(root, "1/2", "Link");
        openNotebook(root, "tobook");
        assertNotNull(find(root, "Link from frombook 1/2 to tobook 2/3"));
        click(root, "Confirm");

        String above = repo.pages(to.id).get(0).id;
        repo.deletePage(above);
        openNotebook(root, "frombook");
        assertNotNull(find(root, "→ tobook 1/2"));
        assertNull(find(root, "→ tobook 2/3"));

        controller.pause().stop().destroy();
    }

    @Test
    public void deletingEitherPageRemovesTheLink() {
        Application app = ApplicationProvider.getApplicationContext();
        BoardRepository repo = ZettelData.repository(app);
        BoardRepository.NotebookInfo from = repo.createNotebook("frombook");
        BoardRepository.NotebookInfo to = repo.createNotebook("tobook");

        ActivityController<CanvasActivity> controller = Robolectric.buildActivity(CanvasActivity.class).setup();
        View root = controller.get().getWindow().getDecorView();
        openNotebook(root, "frombook");
        openNotebook(root, "tobook");
        ink(repo, from.id, 10f);
        ink(repo, to.id, 10f);
        String sourceId = repo.pages(from.id).get(0).id;
        String targetId = repo.pages(to.id).get(0).id;
        repo.createLink(sourceId, targetId);
        openNotebook(root, "frombook");
        assertNotNull(find(root, "→ tobook 1/2"));

        repo.deletePage(sourceId);
        assertTrue(repo.linksTouching(targetId).isEmpty());
        openNotebook(root, "frombook");
        assertNull(find(root, "→ tobook 1/2"));
        openNotebook(root, "tobook");
        assertNull(find(root, "← frombook 1/2"));

        ink(repo, from.id, 10f);
        sourceId = repo.pages(from.id).get(0).id;
        repo.createLink(sourceId, targetId);
        openNotebook(root, "tobook");
        assertNotNull(find(root, "← frombook 1/2"));
        repo.deletePage(targetId);
        assertTrue(repo.linksTouching(sourceId).isEmpty());
        openNotebook(root, "tobook");
        assertNull(find(root, "← frombook 1/2"));
        openNotebook(root, "frombook");
        assertNull(find(root, "→ tobook 1/2"));

        controller.pause().stop().destroy();
    }

    @Test
    public void tappingALineOpensThatNotebookAndPage() {
        Application app = ApplicationProvider.getApplicationContext();
        BoardRepository repo = ZettelData.repository(app);
        BoardRepository.NotebookInfo shelf = repo.createNotebook("shelf");
        BoardRepository.NotebookInfo chapter = repo.createNotebook("chapter", shelf.id);
        BoardRepository.NotebookInfo from = repo.createNotebook("frombook");

        ActivityController<CanvasActivity> controller = Robolectric.buildActivity(CanvasActivity.class).setup();
        View root = controller.get().getWindow().getDecorView();
        openNotebook(root, "frombook");
        click(root, "shelf");
        openNotebook(root, "chapter");
        int height = repo.pages(chapter.id).get(0).slicePx;
        ink(repo, from.id, 10f);
        ink(repo, chapter.id, 2f * height + 10f);
        assertEquals(4, repo.pages(chapter.id).size());
        assertEquals(2, repo.pages(chapter.id).get(0).paper.lastInkedSlice());

        openNotebook(root, "frombook");
        clickOnPage(root, "1/2", "Link");
        click(root, "shelf");
        openNotebook(root, "chapter");
        assertNotNull(find(root, "Link from frombook 1/2 to chapter 3/4"));
        View scroller = pageScroller(root);
        scroller.scrollTo(0, 0);
        idle();
        relayout(root);
        assertNotNull(find(root, "Link from frombook 1/2 to chapter 1/4"));
        assertNull(find(root, "Link from frombook 1/2 to chapter 3/4"));
        click(root, "Confirm");

        openNotebook(root, "frombook");
        click(root, "→ chapter 1/4");
        assertTrue(((View) find(root, "shelf").getParent()).isSelected());
        assertTrue(((View) find(root, "chapter").getParent()).isSelected());
        scroller = pageScroller(root);
        assertEquals("the link opens page 1, not the last inked page", 0, scroller.getScrollY());
        TextView first = find(root, "1/4");
        assertNotNull(first);
        int top = offsetIn(first, scroller);
        assertTrue(top >= scroller.getScrollY() && top < scroller.getScrollY() + scroller.getHeight());

        controller.pause().stop().destroy();
    }

    private static void ink(BoardRepository repo, String notebookId, float y) {
        List<Board> pages = repo.pages(notebookId);
        Board page = pages.get(pages.size() - 1);
        page.paper.appendStroke(TestStrokes.stroke(4f, y), page.id);
        repo.saveInk(page);
    }

    private static void openNotebook(View root, String title) {
        click(root, title);
        idleForPaint();
        relayout(root);
    }

    private static View linkingBar(View root) {
        TextView text = find(root, "Linking from frombook 1/2");
        assertNotNull(text);
        return (View) text.getParent();
    }

    private static void clickOnPage(View root, String pageNumber, String label) {
        TextView number = find(root, pageNumber);
        assertNotNull(pageNumber, number);
        TextView control = textIn((View) number.getParent(), label);
        assertNotNull(label, control);
        assertTrue(control.performClick());
        idle();
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

    private static Rect cover(PageInkView ink, View view, List<Rect> holes) {
        int[] origin = new int[2];
        int[] loc = new int[2];
        ink.getLocationOnScreen(origin);
        view.getLocationOnScreen(loc);
        Rect bounds = new Rect(
                loc[0] - origin[0],
                loc[1] - origin[1],
                loc[0] - origin[0] + view.getWidth(),
                loc[1] - origin[1] + view.getHeight());
        for (Rect hole : holes) {
            if (hole.contains(bounds)) {
                return hole;
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

    private static void click(View root, String text) {
        TextView view = find(root, text);
        assertNotNull("no view with text " + text, view);
        assertTrue(view.performClick());
        idle();
    }

    private static TextView find(View root, String text) {
        return textIn(root, text);
    }

    private static void idle() {
        ShadowLooper.idleMainLooper();
    }

    private static void idleForPaint() {
        ShadowLooper.shadowMainLooper().idleFor(Duration.ofSeconds(2));
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
}
