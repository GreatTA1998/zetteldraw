package com.zetteldraw.penpoc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import com.zetteldraw.penpoc.data.BoardRepository;
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

/** Reorder mode: bottom snackbar, confirm after another same-notebook page, cancel no-ops. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class, qualifiers = "w600dp-h1000dp",
        shadows = IdleSurfaceViewShadow.class)
public class PageReorderUiTest {
    @Before
    public void freshRepository() {
        ZettelData.resetForTest();
        UiExecutors.useSynchronousForTest();
    }

    @Test
    public void reorderAfterPageTwoOfFourYieldsOneThreeTwoFour() {
        Application app = ApplicationProvider.getApplicationContext();
        BoardRepository repo = ZettelData.repository(app);
        BoardRepository.NotebookInfo book = repo.createNotebook("book");

        ActivityController<CanvasActivity> controller = Robolectric.buildActivity(CanvasActivity.class).setup();
        View root = controller.get().getWindow().getDecorView();
        openNotebook(root, "book");
        int height = repo.pages(book.id).get(0).slicePx;
        ink(repo, book.id, 10f);
        ink(repo, book.id, height + 10f);
        ink(repo, book.id, 2f * height + 10f);
        ink(repo, book.id, 3f * height + 10f);
        openNotebook(root, "book");
        List<Board> before = repo.pages(book.id);
        assertEquals(5, before.size());
        String id1 = before.get(0).id;
        String id2 = before.get(1).id;
        String id3 = before.get(2).id;
        String id4 = before.get(3).id;

        View scroller = pageScroller(root);
        // Open lands on last inked; scroll to page 2 so Confirm stays hidden on source.
        scroller.scrollTo(0, height);
        idle();
        relayout(root);
        clickOnPage(root, "2/5", "Reorder");
        assertNotNull(find(root, "Reordering book 2/5"));
        assertNull("confirm stays hidden on the source page", find(root, "Confirm"));

        // Scroll to page 3.
        scroller.scrollTo(0, 2 * height);
        idle();
        relayout(root);
        assertNotNull(find(root, "Place book 2/5 after book 3/5"));
        assertNotNull(find(root, "Confirm"));
        click(root, "Confirm");
        idleForPaint();
        relayout(root);

        List<Board> after = repo.pages(book.id);
        assertEquals(id1, after.get(0).id);
        assertEquals(id3, after.get(1).id);
        assertEquals(id2, after.get(2).id);
        assertEquals(id4, after.get(3).id);
        assertTrue(after.get(4).isBlank());
        assertNull(find(root, "Reordering book 2/5"));
        assertNull(find(root, "Confirm"));

        controller.pause().stop().destroy();
    }

    @Test
    public void confirmHiddenOnSourceAndCancelNoOps() {
        Application app = ApplicationProvider.getApplicationContext();
        BoardRepository repo = ZettelData.repository(app);
        BoardRepository.NotebookInfo book = repo.createNotebook("book");

        ActivityController<CanvasActivity> controller = Robolectric.buildActivity(CanvasActivity.class).setup();
        View root = controller.get().getWindow().getDecorView();
        openNotebook(root, "book");
        int height = repo.pages(book.id).get(0).slicePx;
        ink(repo, book.id, 10f);
        ink(repo, book.id, height + 10f);
        openNotebook(root, "book");
        String first = repo.pages(book.id).get(0).id;
        String second = repo.pages(book.id).get(1).id;

        View scroller = pageScroller(root);
        scroller.scrollTo(0, 0);
        idle();
        relayout(root);
        clickOnPage(root, "1/3", "Reorder");
        assertNotNull(find(root, "Reordering book 1/3"));
        assertNull(find(root, "Confirm"));
        click(root, "Cancel");
        assertNull(find(root, "Reordering book 1/3"));
        assertEquals(first, repo.pages(book.id).get(0).id);
        assertEquals(second, repo.pages(book.id).get(1).id);

        controller.pause().stop().destroy();
    }

    @Test
    public void crossNotebookConfirmIsRefused() {
        Application app = ApplicationProvider.getApplicationContext();
        BoardRepository repo = ZettelData.repository(app);
        BoardRepository.NotebookInfo book = repo.createNotebook("book");
        BoardRepository.NotebookInfo other = repo.createNotebook("other");

        ActivityController<CanvasActivity> controller = Robolectric.buildActivity(CanvasActivity.class).setup();
        View root = controller.get().getWindow().getDecorView();
        openNotebook(root, "book");
        openNotebook(root, "other");
        ink(repo, book.id, 10f);
        ink(repo, other.id, 10f);
        openNotebook(root, "book");
        String source = repo.pages(book.id).get(0).id;

        clickOnPage(root, "1/2", "Reorder");
        openNotebook(root, "other");
        assertNotNull(find(root, "Open the same notebook to place"));
        assertNull(find(root, "Confirm"));
        assertEquals(source, repo.pages(book.id).get(0).id);

        controller.pause().stop().destroy();
    }

    @Test
    public void snackbarIsNotParentedIntoTopTabStrip() {
        Application app = ApplicationProvider.getApplicationContext();
        BoardRepository repo = ZettelData.repository(app);
        BoardRepository.NotebookInfo book = repo.createNotebook("book");

        ActivityController<CanvasActivity> controller = Robolectric.buildActivity(CanvasActivity.class).setup();
        View root = controller.get().getWindow().getDecorView();
        openNotebook(root, "book");
        ink(repo, book.id, 10f);
        openNotebook(root, "book");

        View area = (View) pageScroller(root).getParent();
        int areaBefore = area.getHeight();
        View scroller = pageScroller(root);
        scroller.scrollTo(0, 0);
        idle();
        relayout(root);
        clickOnPage(root, "1/2", "Reorder");
        relayout(root);
        assertEquals("bottom strip must not shrink the drawing area", areaBefore, area.getHeight());
        TextView banner = find(root, "Reordering book 1/2");
        assertNotNull(banner);
        View strip = (View) banner.getParent().getParent();
        assertFalse(isDescendant(strip, (View) area.getParent()));
        assertSame(strip.getParent(), ((View) area.getParent()).getParent());

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

    private static boolean isDescendant(View child, View ancestor) {
        View current = child;
        while (current != null) {
            if (current == ancestor) {
                return true;
            }
            current = current.getParent() instanceof View ? (View) current.getParent() : null;
        }
        return false;
    }
}
