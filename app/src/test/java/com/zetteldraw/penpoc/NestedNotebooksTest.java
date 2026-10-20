package com.zetteldraw.penpoc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import com.zetteldraw.penpoc.data.TestInk;
import com.zetteldraw.penpoc.data.BoardRepository;
import com.zetteldraw.penpoc.data.NotebookPaper;
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

import java.util.ArrayList;
import java.util.List;

/** Nested bars, and opening a notebook on its last inked page without painting the pages above it. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class, qualifiers = "w600dp-h1000dp",
        shadows = IdleSurfaceViewShadow.class)
public class NestedNotebooksTest {
    @Before
    public void freshRepository() {
        ZettelData.resetForTest();
        UiExecutors.useSynchronousForTest();

        BoardRepository _repo = ZettelData.repository(
                androidx.test.core.app.ApplicationProvider.getApplicationContext());
        _repo.ensureSheet(null, 800);
        if (_repo.scratchpadPages().isEmpty()) {
            _repo.createPage(null);
        }

        ApplicationProvider.getApplicationContext()
                .getSharedPreferences("canvas", Context.MODE_PRIVATE)
                .edit()
                .clear()
                .apply();
    }

    @Test
    public void childrenStayOffTheTopBarAndSiblingSwitchesCloseTheDeeperBar() {
        Application app = ApplicationProvider.getApplicationContext();
        BoardRepository repo = ZettelData.repository(app);
        BoardRepository.NotebookInfo shelf = repo.createNotebook("shelf");
        BoardRepository.NotebookInfo chapter = repo.createNotebook("chapter", shelf.id());
        BoardRepository.NotebookInfo notes = repo.createNotebook("notes", shelf.id());
        repo.createNotebook("scene", chapter.id());

        ActivityController<CanvasActivity> controller = Robolectric.buildActivity(CanvasActivity.class).setup();
        View root = controller.get().getWindow().getDecorView();
        idle();

        ViewGroup topStrip = (ViewGroup) find(root, "comedy").getParent().getParent();
        assertNotNull(textIn(topStrip, "shelf"));
        assertTrue("children are not on the top bar", textIn(topStrip, "chapter") == null);
        assertTrue(textIn(topStrip, "notes") == null);
        assertTrue(textIn(topStrip, "scene") == null);

        click(root, "shelf");
        assertEquals(1, byDescription(root, "Child notebooks").size());
        assertTrue(((View) find(root, "shelf").getParent()).isSelected());
        assertNotNull(find(root, "chapter"));
        assertTrue("a grandchild stays off until its parent is open", find(root, "scene") == null);

        click(root, "chapter");
        assertEquals("two levels of bars", 2, byDescription(root, "Child notebooks").size());
        assertNotNull(find(root, "scene"));
        // Leaf-only underline: ancestors open child bars but are not selected.
        assertFalse("parent is not underlined while a child is open",
                ((View) find(root, "shelf").getParent()).isSelected());
        assertTrue(((View) find(root, "chapter").getParent()).isSelected());

        click(root, "notes");
        assertEquals("switching siblings closes the deeper bar", 1, byDescription(root, "Child notebooks").size());
        assertTrue(find(root, "scene") == null);
        assertTrue(((View) find(root, "notes").getParent()).isSelected());
        assertFalse(((View) find(root, "chapter").getParent()).isSelected());
        assertFalse("ancestor shelf is not underlined while notes is open",
                ((View) find(root, "shelf").getParent()).isSelected());

        click(root, "shelf");
        assertEquals("the child bar stays when its parent is tapped again", 1,
                byDescription(root, "Child notebooks").size());
        assertFalse(((View) find(root, "notes").getParent()).isSelected());
        assertTrue(((View) find(root, "shelf").getParent()).isSelected());
        assertTrue(find(root, "chapter") != null);

        clickDesc(root, "Scratchpad");
        assertEquals("Scratchpad closes every lower bar", 0, byDescription(root, "Child notebooks").size());

        controller.pause().stop().destroy();
    }

    /**
     * Parent and child are distinct sheets: underline is leaf-only, and switching
     * paints different page lists (Dating/Stacey "same notebook" regression).
     */
    @Test
    public void parentAndChildAreDistinctSheetsWithLeafOnlyUnderline() {
        Application app = ApplicationProvider.getApplicationContext();
        BoardRepository repo = ZettelData.repository(app);
        BoardRepository.NotebookInfo dating = repo.createNotebook("dating");
        BoardRepository.NotebookInfo stacey = repo.createNotebook("stacey", dating.id());

        ActivityController<CanvasActivity> controller = Robolectric.buildActivity(CanvasActivity.class).setup();
        View root = controller.get().getWindow().getDecorView();
        idle();
        click(root, "dating");
        idleForPaint();
        relayout(root);
        assertNotNull(find(root, "Create new page"));
        assertTrue(find(root, "Create new page").performClick());
        idleForPaint();
        relayout(root);
        assertNotNull(find(root, "1/" + repo.pages(dating.id()).size()));
        TestInk.appendCovering(repo.pages(dating.id()).get(0).paper, TestStrokes.stroke(11f, 20f));
        repo.saveInk(repo.pages(dating.id()).get(0));

        click(root, "stacey");
        idleForPaint();
        relayout(root);
        assertNotNull(find(root, "Create new page"));
        assertTrue(find(root, "Create new page").performClick());
        idleForPaint();
        relayout(root);
        assertNotNull("Stacey pages are on screen after switch", find(root, "1/" + repo.pages(stacey.id()).size()));
        assertTrue(((View) find(root, "stacey").getParent()).isSelected());
        assertFalse("Dating must not stay underlined while Stacey is open",
                ((View) find(root, "dating").getParent()).isSelected());
        TestInk.appendCovering(repo.pages(stacey.id()).get(0).paper, TestStrokes.stroke(99f, 20f));
        repo.saveInk(repo.pages(stacey.id()).get(0));
        assertNotEquals(NotebookPaper.sheetId(dating.id()), NotebookPaper.sheetId(stacey.id()));

        click(root, "dating");
        idleForPaint();
        relayout(root);
        assertTrue(((View) find(root, "dating").getParent()).isSelected());
        assertFalse("Stacey must not stay underlined while Dating is open",
                ((View) find(root, "stacey").getParent()).isSelected());
        assertNotNull(find(root, "1/" + repo.pages(dating.id()).size()));

        click(root, "stacey");
        idleForPaint();
        relayout(root);
        assertTrue(((View) find(root, "stacey").getParent()).isSelected());
        assertFalse(((View) find(root, "dating").getParent()).isSelected());
        assertEquals(99f, repo.pages(stacey.id()).get(0).paper.strokes().get(0).points.get(0).x, 0.01f);
        assertEquals(11f, repo.pages(dating.id()).get(0).paper.strokes().get(0).points.get(0).x, 0.01f);

        controller.pause().stop().destroy();
    }

    @Test
    public void moveSnackbarIntoChildNotebookAndExcludeRectsCoverEveryBar() {
        Application app = ApplicationProvider.getApplicationContext();
        BoardRepository repo = ZettelData.repository(app);
        BoardRepository.NotebookInfo shelf = repo.createNotebook("shelf");
        BoardRepository.NotebookInfo chapter = repo.createNotebook("chapter", shelf.id());

        ActivityController<CanvasActivity> controller = Robolectric.buildActivity(CanvasActivity.class).setup();
        CanvasActivity activity = controller.get();
        View root = activity.getWindow().getDecorView();
        idle();
        Board first = repo.scratchpadPages().get(0);
        TestInk.appendCovering(first.paper, TestStrokes.stroke(20f, first.paperOrigin + 20f), first.id);
        repo.saveInk(first);
        String sourceId = repo.scratchpadPages().get(0).id;
        clickDesc(root, "Scratchpad");
        idle();
        click(root, "shelf");
        idle();
        relayout(root);

        PageInkView ink = findInk(root);
        List<Rect> holes = ink.penExcludes();
        View topBar = (View) byDescription(root, "Scratchpad").get(0).getParent();
        assertNotNull(cover(ink, topBar, holes));
        List<View> childBars = byDescription(root, "Child notebooks");
        assertEquals(1, childBars.size());
        assertNotNull("the child bar is a hole in the pen reader", cover(ink, childBars.get(0), holes));

        clickDesc(root, "Scratchpad");
        idle();
        click(root, "Move");
        assertNull("Move opens hierarchy menu, not snackbar", find(root, "Moving Scratchpad 1"));
        assertNull(find(root, "Reordering Scratchpad 1"));
        // Overlay menu item is the last match (tab bars are earlier in the tree).
        assertTrue(last(root, "chapter").performClick());
        idle();
        Board moved = repo.pages(chapter.id()).get(0);
        assertEquals(sourceId, moved.id);
        assertFalse(moved.isBlank());
        boolean found = false;
        for (com.zetteldraw.penpoc.InkRenderer.InkStroke stroke : moved.paper.strokes()) {
            if (stroke.points.get(0).x == 20f) {
                found = true;
            }
        }
        assertTrue("the page was filed into chapter", found);

        controller.pause().stop().destroy();
    }

    @Test
    public void firstOpenShowsLastInkedPageAndDoesNotRenderEarlierOnes() {
        Application app = ApplicationProvider.getApplicationContext();
        BoardRepository repo = ZettelData.repository(app);
        BoardRepository.NotebookInfo notebook = repo.createNotebook("resume");
        int height = seedLastInkedOnPageThree(app, repo, notebook.id());

        ActivityController<CanvasActivity> controller = Robolectric.buildActivity(CanvasActivity.class).setup();
        CanvasActivity activity = controller.get();
        View root = activity.getWindow().getDecorView();
        idle();
        PageInkView ink = findInk(root);
        ink.onSurface(true);
        click(root, "resume");
        idleForPaint();
        relayout(root);
        ink.onWindowVisibilityChanged(View.VISIBLE);
        ink.onSurface(true);
        idleForPaint();

        List<Board> pages = repo.pages(notebook.id());
        assertEquals(3, pages.size());
        assertEquals(2, pages.get(0).paper.lastInkedSlice());
        View scroller = pageScroller(root);
        assertEquals(2 * height, scroller.getScrollY());
        TextView third = find(root, "3/3");
        assertNotNull(third);
        int thirdTop = offsetIn(third, scroller);
        assertTrue("page 3 is in the viewport",
                thirdTop >= scroller.getScrollY() && thirdTop < scroller.getScrollY() + scroller.getHeight());
        TextView firstLabel = find(root, "1/3");
        assertTrue("page 1 sits above the viewport",
                offsetIn(firstLabel, scroller) + firstLabel.getHeight() <= scroller.getScrollY());

        List<String> painted = ink.paintedPageIds();
        assertFalse(painted.contains(pages.get(0).id));
        assertFalse(painted.contains(pages.get(1).id));
        assertTrue(painted.contains(pages.get(2).id));

        controller.pause().stop().destroy();
    }

    @Test
    public void returningToANotebookRestoresTheScrollLeftBehind() {
        Application app = ApplicationProvider.getApplicationContext();
        BoardRepository repo = ZettelData.repository(app);
        BoardRepository.NotebookInfo notebook = repo.createNotebook("resume");
        int height = seedLastInkedOnPageThree(app, repo, notebook.id());

        ActivityController<CanvasActivity> controller = Robolectric.buildActivity(CanvasActivity.class).setup();
        View root = controller.get().getWindow().getDecorView();
        idle();
        PageInkView ink = findInk(root);
        ink.onSurface(true);
        click(root, "resume");
        idleForPaint();
        relayout(root);

        View scroller = pageScroller(root);
        assertEquals("first open lands on last inked", 2 * height, scroller.getScrollY());
        ((PageScroller) scroller).scrollTo(0, height);
        idle();
        assertEquals(height, scroller.getScrollY());

        clickDesc(root, "Scratchpad");
        idle();
        click(root, "resume");
        idleForPaint();
        relayout(root);
        scroller = pageScroller(root);
        assertEquals("return restores the left-behind page, not last inked",
                height, scroller.getScrollY());
        assertNotNull(find(root, "2/3"));

        controller.pause().stop().destroy();
    }

    @Test
    public void scrollPositionSurvivesProcessDeathViaPrefs() {
        Application app = ApplicationProvider.getApplicationContext();
        BoardRepository repo = ZettelData.repository(app);
        BoardRepository.NotebookInfo notebook = repo.createNotebook("resume");
        int height = seedLastInkedOnPageThree(app, repo, notebook.id());

        ActivityController<CanvasActivity> first = Robolectric.buildActivity(CanvasActivity.class).setup();
        View root = first.get().getWindow().getDecorView();
        idle();
        click(root, "resume");
        idleForPaint();
        relayout(root);
        View scroller = pageScroller(root);
        ((PageScroller) scroller).scrollTo(0, height);
        idle();
        first.pause().stop().destroy();

        ActivityController<CanvasActivity> second = Robolectric.buildActivity(CanvasActivity.class).setup();
        root = second.get().getWindow().getDecorView();
        idle();
        click(root, "resume");
        idleForPaint();
        relayout(root);
        assertEquals("prefs restore the left-behind page after a new activity",
                height, pageScroller(root).getScrollY());
        second.pause().stop().destroy();
    }

    /**
     * Opens the notebook once so the UI page height is known, inks page 3, then
     * clears the remembered scroll so the next open is a true first open.
     */
    private static int seedLastInkedOnPageThree(Application app, BoardRepository repo, String notebookId) {
        ActivityController<CanvasActivity> probe = Robolectric.buildActivity(CanvasActivity.class).setup();
        View root = probe.get().getWindow().getDecorView();
        idle();
        click(root, "resume");
        idle();
        relayout(root);
        if (repo.pages(notebookId).isEmpty()) {
            assertNotNull(find(root, "Create new page"));
            assertTrue(find(root, "Create new page").performClick());
            idle();
            relayout(root);
        }
        List<Board> pages = repo.pages(notebookId);
        int height = pages.get(0).slicePx;
        assertTrue(height > 40);
        TestInk.appendCovering(pages.get(0).paper, TestStrokes.stroke(12f, 2f * height + 10f), pages.get(0).id);
        repo.saveInk(pages.get(0));
        probe.pause().stop().destroy();
        app.getSharedPreferences("canvas", Context.MODE_PRIVATE)
                .edit()
                .remove("scroll_" + notebookId)
                .apply();
        return height;
    }

    @Test
    public void placeUnderUsesSharedHierarchyMenuNotSnackbar() {
        Application app = ApplicationProvider.getApplicationContext();
        BoardRepository repo = ZettelData.repository(app);
        BoardRepository.NotebookInfo shelf = repo.createNotebook("shelf");
        BoardRepository.NotebookInfo chapter = repo.createNotebook("chapter", shelf.id());
        BoardRepository.NotebookInfo notes = repo.createNotebook("notes");

        ActivityController<CanvasActivity> controller = Robolectric.buildActivity(CanvasActivity.class).setup();
        View root = controller.get().getWindow().getDecorView();
        idle();

        assertTrue(find(root, "notes").performLongClick());
        idle();
        click(root, "Place under…");
        assertNull("Place under is hierarchy menu, not snackbar", find(root, "Placing notes under…"));
        assertNull(find(root, "Confirm"));
        // Self omitted from picker: only the standing tab named "notes" remains.
        ArrayList<TextView> notesHits = new ArrayList<>();
        collectText(root, "notes", notesHits);
        assertEquals("cannot place under itself", 1, notesHits.size());
        assertTrue(last(root, "chapter").performClick());
        idle();

        assertEquals(chapter.id(), repo.notebooks().stream()
                .filter(n -> n.id().equals(notes.id())).findFirst().get().parentId());

        // shelf → chapter → notes; descendants omitted from the picker (child-bar
        // "chapter" may still show; nested "notes" is not a menu row).
        assertTrue(find(root, "shelf").performLongClick());
        idle();
        click(root, "Place under…");
        ArrayList<TextView> chapterHits = new ArrayList<>();
        collectText(root, "chapter", chapterHits);
        assertEquals("chapter only as standing child tab, not a second menu row",
                1, chapterHits.size());
        ArrayList<TextView> notesHitsAfter = new ArrayList<>();
        collectText(root, "notes", notesHitsAfter);
        assertEquals("nested notes omitted from place-under picker", 0, notesHitsAfter.size());
        assertNotNull("seed notebooks still offered as parents", last(root, "journal"));

        controller.pause().stop().destroy();
    }

    @Test
    public void openingTheLastPageOfTwentyPaintsOnlyTheOnScreenSlice() {
        int height = 400;
        NotebookPaper paper = NotebookPaper.empty(height);
        TestInk.appendCovering(paper, TestStrokes.stroke(8f, 19f * height + 10f));
        assertEquals(19, paper.lastInkedSlice());
        assertEquals(20, paper.sliceCount());

        ActivityController<CanvasActivity> controller = Robolectric.buildActivity(CanvasActivity.class).setup();
        CanvasActivity activity = controller.get();
        idle();

        PageInkView ink = new PageInkView(activity);
        activity.addContentView(ink, new FrameLayout.LayoutParams(400, height - 1));
        ink.measure(
                View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height - 1, View.MeasureSpec.EXACTLY));
        ink.layout(0, 0, 400, height - 1);
        ink.onWindowVisibilityChanged(View.VISIBLE);
        ink.onSurface(true);
        idle();

        List<Board> pages = new ArrayList<>();
        int[] heights = new int[paper.sliceCount()];
        for (int i = 0; i < paper.sliceCount(); i++) {
            Board page = new Board(paper.sliceId(i), 1L);
            page.paper = paper;
            page.sliceIndex = i;
            page.paperOrigin = paper.origin(i);
            page.slicePx = height;
            pages.add(page);
            heights[i] = height;
        }
        ink.setPages(pages, heights, 0, 0);
        idle();
        int openingTheFirst = ink.paintedPageIds().size();
        assertEquals(1, openingTheFirst);
        assertTrue(ink.paintedPageIds().contains(pages.get(0).id));

        ink.setPages(pages, heights, 0, 19 * height);
        idle();
        List<String> painted = ink.paintedPageIds();
        assertEquals(openingTheFirst, painted.size());
        assertTrue(painted.contains(pages.get(19).id));
        for (int i = 0; i < 19; i++) {
            assertFalse("page " + (i + 1) + " was rendered", painted.contains(pages.get(i).id));
        }

        controller.pause().stop().destroy();
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

    private static void clickDesc(View root, String description) {
        List<View> views = byDescription(root, description);
        assertEquals(description, 1, views.size());
        assertTrue(views.get(0).performClick());
        idle();
    }

    private static List<View> byDescription(View view, String description) {
        ArrayList<View> out = new ArrayList<>();
        collectByDescription(view, description, out);
        return out;
    }

    private static void collectByDescription(View view, String description, List<View> out) {
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

    private static void idle() {
        ShadowLooper.idleMainLooper();
    }

    /** A new page list waits until the pen reports off, then paints on a short deadline. */
    private static void idleForPaint() {
        ShadowLooper.shadowMainLooper().idleFor(java.time.Duration.ofSeconds(2));
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

    private static TextView last(View root, String text) {
        ArrayList<TextView> all = new ArrayList<>();
        collectText(root, text, all);
        assertFalse(all.isEmpty());
        return all.get(all.size() - 1);
    }

    private static void collectText(View view, String text, List<TextView> out) {
        if (view.getVisibility() != View.VISIBLE) {
            return;
        }
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) {
            out.add((TextView) view);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                collectText(group.getChildAt(i), text, out);
            }
        }
    }

    /** Top of {@code view} in {@code ancestor}'s content coordinates. */
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
