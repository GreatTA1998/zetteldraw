package com.zetteldraw.penpoc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import com.onyx.android.sdk.data.note.TouchPoint;
import com.zetteldraw.penpoc.data.BoardRepository;
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

/** Drives the notebook UI without a Boox: create, rename, delete via the in-window forms. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class, qualifiers = "w600dp-h1000dp",
        shadows = IdleSurfaceViewShadow.class)
public class CanvasActivitySmokeTest {
    @Before
    public void freshRepository() {
        ZettelData.resetForTest();
    }

    @Test
    public void createRenameDeleteNotebookThroughTheUi() {
        ActivityController<CanvasActivity> controller = Robolectric.buildActivity(CanvasActivity.class).setup();
        CanvasActivity activity = controller.get();
        View root = activity.getWindow().getDecorView();
        BoardRepository repo = ZettelData.repository(activity);
        idle();

        assertNotNull("seed tabs share the top bar with Scratchpad", find(root, "comedy"));
        assertTrue("no tab ⋯ while the Scratchpad is open", tabMenus(root).isEmpty());
        assertNull("no separate Notebooks row", find(root, "Notebooks"));
        click(root, "comedy");
        assertTrue(find(root, "comedy").getParent() instanceof View);
        assertTrue("selected tab is inverted", ((View) find(root, "comedy").getParent()).isSelected());
        assertFalse(find(root, "Scratchpad").isSelected());
        assertEquals("only the selected tab carries ⋯", 1, tabMenus(root).size());

        click(root, "+");
        EditText name = findEdit(root);
        assertNotNull("name form shown in-window", name);
        name.setText("sketches");
        click(root, "Create");
        assertNull("form closed", findEdit(root));
        assertEquals(5, repo.notebooks().size());
        assertNotNull("new tab shown", find(root, "sketches"));
        assertTrue("new notebook opens", ((View) find(root, "sketches").getParent()).isSelected());
        assertSame("⋯ sits inside the selected tab",
                find(root, "sketches").getParent(), tabMenus(root).get(0).getParent());

        clickTabMenu(root);
        click(root, "Rename");
        name = findEdit(root);
        assertEquals("sketches", name.getText().toString());
        name.setText("drawings");
        click(root, "Save");
        assertEquals("drawings", repo.notebooks().get(4).title);
        assertNotNull(find(root, "drawings"));

        clickTabMenu(root);
        click(root, "Delete");
        assertNotNull("confirmation shown", find(root, "Delete “drawings”?"));
        click(root, "Cancel");
        assertEquals(5, repo.notebooks().size());

        clickTabMenu(root);
        click(root, "Delete");
        clickLast(root, "Delete");
        assertEquals(4, repo.notebooks().size());
        assertNull(find(root, "drawings"));

        controller.pause().stop().destroy();
    }

    @Test
    public void longPressAnyTabOpensItsMenuUnderThatTab() {
        ActivityController<CanvasActivity> controller = Robolectric.buildActivity(CanvasActivity.class).setup();
        View root = controller.get().getWindow().getDecorView();
        idle();

        TextView journal = find(root, "journal");
        assertTrue(journal.performLongClick());
        idle();
        assertTrue("long-press selects the tab", ((View) journal.getParent()).isSelected());
        TextView rename = find(root, "Rename");
        assertNotNull("menu shown in-window", rename);
        int[] tab = new int[2];
        int[] item = new int[2];
        ((View) journal.getParent()).getLocationInWindow(tab);
        ((View) rename.getParent()).getLocationInWindow(item);
        assertTrue("menu hangs below the tab", item[1] > tab[1]);

        click(root, "Rename");
        EditText name = findEdit(root);
        assertEquals("journal", name.getText().toString());
        name.setText("diary");
        click(root, "Save");
        assertNotNull(find(root, "diary"));
        assertNull(find(root, "journal"));

        click(root, "Scratchpad");
        assertTrue(find(root, "Scratchpad").isSelected());
        assertTrue(tabMenus(root).isEmpty());
        controller.pause().stop().destroy();
    }

    @Test
    public void pageMenuWipesAndDeletesWithConfirmAndKeepsTheTrailingBlank() {
        Application app = androidx.test.core.app.ApplicationProvider.getApplicationContext();
        BoardRepository repo = ZettelData.repository(app);
        Board first = repo.scratchpadPages().get(0);
        first.strokes.add(com.zetteldraw.penpoc.data.TestStrokes.stroke(20f, 20f));
        repo.saveInk(first);
        Board second = repo.scratchpadPages().get(1);
        second.strokes.add(com.zetteldraw.penpoc.data.TestStrokes.stroke(40f, 40f));
        repo.saveInk(second);

        ActivityController<CanvasActivity> controller = Robolectric.buildActivity(CanvasActivity.class).setup();
        View root = controller.get().getWindow().getDecorView();
        idle();
        List<View> menus = byDescription(root, "Page options");
        assertEquals("every page has a ⋯, blank ones too", 3, menus.size());
        assertNull("Wipe lives in the menu now", find(root, "Wipe"));

        menus.get(0).performClick();
        idle();
        click(root, "Wipe");
        assertNotNull(find(root, "Wipe this page?"));
        click(root, "Cancel");
        assertFalse(first.isBlank());
        byDescription(root, "Page options").get(0).performClick();
        idle();
        click(root, "Wipe");
        clickLast(root, "Wipe");
        assertTrue(first.isBlank());

        byDescription(root, "Page options").get(0).performClick();
        idle();
        assertNull("nothing to wipe on a blank page", find(root, "Wipe"));
        click(root, "Delete page");
        assertNotNull(find(root, "Delete this page?"));
        clickLast(root, "Delete");
        List<Board> pages = repo.scratchpadPages();
        assertEquals(2, pages.size());
        assertEquals(second.id, pages.get(0).id);
        assertTrue(pages.get(1).isBlank());
        assertEquals(2, byDescription(root, "Page options").size());

        byDescription(root, "Page options").get(1).performClick();
        idle();
        click(root, "Delete page");
        clickLast(root, "Delete");
        pages = repo.scratchpadPages();
        assertEquals("the trailing blank page regenerates", 2, pages.size());
        assertTrue(pages.get(1).isBlank());
        assertEquals(2, byDescription(root, "Page options").size());
        controller.pause().stop().destroy();
    }

    @Test
    public void notebookGrowsAPageOnFirstInkAndPagesAreNumbered() {
        Application app = androidx.test.core.app.ApplicationProvider.getApplicationContext();
        BoardRepository repo = ZettelData.repository(app);
        ActivityController<CanvasActivity> controller = Robolectric.buildActivity(CanvasActivity.class).setup();
        View root = controller.get().getWindow().getDecorView();
        idle();

        click(root, "comedy");
        assertEquals("an empty notebook shows one blank page", 1, byDescription(root, "Page options").size());
        assertEquals(1, byDescription(root, "Page 1").size());
        assertNotNull(find(root, "1"));
        assertNull("no empty-notebook hint any more", find(root, "No notebooks yet. Tap + to add one."));

        PageInkView ink = findInk(root);
        ArrayList<TouchPoint> stroke = new ArrayList<>();
        stroke.add(new TouchPoint(100f, 100f, 0.5f, 1f, 0, 0, 1L));
        stroke.add(new TouchPoint(160f, 140f, 0.5f, 1f, 0, 0, 2L));
        ink.addStroke(stroke);
        idle();
        List<Board> pages = repo.notebookPages(Notebook.COMEDY.uuid);
        assertEquals(2, pages.size());
        assertFalse(pages.get(0).isBlank());
        assertTrue(pages.get(1).isBlank());
        assertEquals("a new blank page follows", 2, byDescription(root, "Page options").size());
        assertEquals(1, byDescription(root, "Page 2").size());

        byDescription(root, "Page options").get(0).performClick();
        idle();
        click(root, "Delete page");
        clickLast(root, "Delete");
        pages = repo.notebookPages(Notebook.COMEDY.uuid);
        assertEquals("the notebook keeps one blank page", 1, pages.size());
        assertTrue(pages.get(0).isBlank());
        assertEquals(1, byDescription(root, "Page 1").size());
        assertTrue(byDescription(root, "Page 2").isEmpty());

        click(root, "Scratchpad");
        assertEquals(1, byDescription(root, "Page 1").size());
        controller.pause().stop().destroy();
    }

    @Test
    public void penStrokesAndEraseUndoAndRedoAndTheTrailingBlankFollows() {
        Application app = androidx.test.core.app.ApplicationProvider.getApplicationContext();
        BoardRepository repo = ZettelData.repository(app);
        ActivityController<CanvasActivity> controller = Robolectric.buildActivity(CanvasActivity.class).setup();
        View root = controller.get().getWindow().getDecorView();
        idle();
        PageInkView ink = findInk(root);
        Board page = repo.scratchpadPages().get(0);

        ink.addStroke(points(100f, 100f));
        idle();
        ink.addStroke(points(300f, 300f));
        idle();
        assertEquals(2, page.strokes.size());
        assertEquals("first ink grew a new blank page", 2, repo.scratchpadPages().size());
        assertEquals(2, byDescription(root, "Page options").size());

        clickDesc(root, "Undo");
        assertEquals(1, page.strokes.size());
        clickDesc(root, "Undo");
        assertTrue(page.isBlank());
        assertEquals("undoing to blank collapses the extra page", 1, repo.scratchpadPages().size());
        assertEquals(1, byDescription(root, "Page options").size());
        assertFalse(byDescription(root, "Undo").get(0).isEnabled());

        clickDesc(root, "Redo");
        clickDesc(root, "Redo");
        assertEquals(2, page.strokes.size());
        assertEquals("redo stores the page again and a blank follows", 2, repo.scratchpadPages().size());
        assertEquals(2, byDescription(root, "Page options").size());
        assertEquals(2, repo.scratchpadPages().get(0).strokes.size());

        InkRenderer.InkStroke first = page.strokes.get(0);
        clickDesc(root, "Eraser");
        assertTrue(byDescription(root, "Eraser").get(0).isSelected());
        ink.eraseStrokes(points(100f, 100f));
        idle();
        assertEquals(1, page.strokes.size());
        clickDesc(root, "Undo");
        assertEquals(2, page.strokes.size());
        assertSame("erased stroke comes back in its place", first, page.strokes.get(0));
        clickDesc(root, "Redo");
        assertEquals(1, page.strokes.size());

        ink.addStroke(points(500f, 500f));
        idle();
        assertFalse("a new edit clears redo", byDescription(root, "Redo").get(0).isEnabled());

        click(root, "comedy");
        assertFalse("history is per page list", byDescription(root, "Undo").get(0).isEnabled());
        controller.pause().stop().destroy();
    }

    private static ArrayList<TouchPoint> points(float x, float y) {
        ArrayList<TouchPoint> list = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            list.add(new TouchPoint(x + i * 6f, y + i * 4f, 0.5f, 1f, 0, 0, 1L + i));
        }
        return list;
    }

    private static void clickDesc(View root, String description) {
        List<View> views = byDescription(root, description);
        assertEquals(description, 1, views.size());
        assertTrue(description + " clickable", views.get(0).performClick());
        idle();
    }

    @Test
    public void moveFilesPagesIntoExistingAndNewNotebooks() {
        Application app = androidx.test.core.app.ApplicationProvider.getApplicationContext();
        BoardRepository repo = ZettelData.repository(app);
        Board first = repo.scratchpadPages().get(0);
        first.strokes.add(com.zetteldraw.penpoc.data.TestStrokes.stroke(20f, 20f));
        repo.saveInk(first);
        Board second = repo.scratchpadPages().get(1);
        second.strokes.add(com.zetteldraw.penpoc.data.TestStrokes.stroke(40f, 40f));
        repo.saveInk(second);

        ActivityController<CanvasActivity> controller = Robolectric.buildActivity(CanvasActivity.class).setup();
        View root = controller.get().getWindow().getDecorView();
        idle();

        clickFirstEnabled(root, "Move");
        clickLast(root, "journal");
        assertEquals(2, repo.notebookPages(Notebook.JOURNAL.uuid).size());

        clickFirstEnabled(root, "Move");
        click(root, "+ New notebook");
        EditText name = findEdit(root);
        name.setText("fresh");
        click(root, "Create");
        BoardRepository.NotebookInfo fresh = repo.notebooks().get(4);
        assertEquals("fresh", fresh.title);
        assertEquals(2, repo.notebookPages(fresh.id).size());
        assertEquals(1, repo.scratchpadPages().size());
        assertTrue(repo.scratchpadPages().get(0).isBlank());

        controller.pause().stop().destroy();
    }

    @Test
    public void lassoDragMovesStrokesThenUndoAndRedo() {
        Application app = androidx.test.core.app.ApplicationProvider.getApplicationContext();
        BoardRepository repo = ZettelData.repository(app);
        Board page = repo.scratchpadPages().get(0);
        page.strokes.add(com.zetteldraw.penpoc.data.TestStrokes.stroke(200f, 200f));
        page.strokes.add(com.zetteldraw.penpoc.data.TestStrokes.stroke(500f, 700f));
        repo.saveInk(page);
        BoardRepository.NotebookInfo notebook = repo.createNotebook("lasso");
        repo.movePageToNotebook(page.id, notebook.id);
        InkRenderer.InkStroke original = page.strokes.get(0);
        InkRenderer.InkStroke other = page.strokes.get(1);

        ActivityController<CanvasActivity> controller = Robolectric.buildActivity(CanvasActivity.class).setup();
        View root = controller.get().getWindow().getDecorView();
        idle();
        click(root, "lasso");
        PageInkView ink = findInk(root);
        assertNotNull(ink);

        View undo = byDescription(root, "Undo").get(0);
        View redo = byDescription(root, "Redo").get(0);
        assertFalse("nothing to undo yet", undo.isEnabled());
        assertFalse(redo.isEnabled());
        clickDesc(root, "Lasso");
        assertTrue("active tool is inverted", byDescription(root, "Lasso").get(0).isSelected());
        assertEquals(PageInkView.Tool.LASSO, ink.tool());
        assertNotNull(find(root, "Circle ink to select it"));

        ArrayList<TouchPoint> outline = new ArrayList<>();
        outline.add(new TouchPoint(150f, 150f, 0.5f, 1f, 0, 0, 1L));
        outline.add(new TouchPoint(300f, 150f, 0.5f, 1f, 0, 0, 2L));
        outline.add(new TouchPoint(300f, 300f, 0.5f, 1f, 0, 0, 3L));
        outline.add(new TouchPoint(150f, 300f, 0.5f, 1f, 0, 0, 4L));
        ink.finishLasso(outline);
        idle();
        assertTrue(ink.hasSelection());
        assertTrue(ink.capturesTouches());
        assertNotNull(find(root, "1 stroke selected. Drag it, or tap outside to cancel"));

        touch(ink, MotionEvent.ACTION_DOWN, 210f, 205f);
        touch(ink, MotionEvent.ACTION_MOVE, 260f, 235f);
        touch(ink, MotionEvent.ACTION_MOVE, 310f, 285f);
        touch(ink, MotionEvent.ACTION_UP, 310f, 285f);
        idle();

        assertFalse(ink.hasSelection());
        assertEquals("back to Pen after one move", PageInkView.Tool.PEN, ink.tool());
        assertEquals(original.id, page.strokes.get(0).id);
        assertEquals(original.points.get(0).x + 100f, page.strokes.get(0).points.get(0).x, 0.001f);
        assertEquals(original.points.get(0).y + 80f, page.strokes.get(0).points.get(0).y, 0.001f);
        assertSame(other, page.strokes.get(1));
        assertTrue(undo.isEnabled());

        InkRenderer.InkStroke moved = page.strokes.get(0);
        clickDesc(root, "Undo");
        assertSame(original, page.strokes.get(0));
        assertFalse(undo.isEnabled());
        assertTrue(redo.isEnabled());
        assertEquals("undo saves through the repository",
                original.points.get(0).y, repo.notebookPages(notebook.id).get(0).strokes.get(0).points.get(0).y, 0.001f);
        clickDesc(root, "Redo");
        assertSame(moved, page.strokes.get(0));
        clickDesc(root, "Undo");
        assertSame(original, page.strokes.get(0));

        clickDesc(root, "Lasso");
        ink.finishLasso(outline);
        idle();
        assertTrue(ink.hasSelection());
        touch(ink, MotionEvent.ACTION_DOWN, 20f, 900f);
        touch(ink, MotionEvent.ACTION_UP, 20f, 900f);
        idle();
        assertFalse("tap outside cancels", ink.hasSelection());
        assertSame(original, page.strokes.get(0));
        assertEquals(PageInkView.Tool.LASSO, ink.tool());

        controller.pause().stop().destroy();
    }

    /** The ⋯ inside a notebook tab (pages have their own ⋯ with another description). */
    private static List<View> tabMenus(View root) {
        List<View> out = new ArrayList<>();
        for (TextView view : collectAll(root, "⋯")) {
            if (String.valueOf(view.getContentDescription()).endsWith("rename or delete")) {
                out.add(view);
            }
        }
        return out;
    }

    private static void clickTabMenu(View root) {
        List<View> menus = tabMenus(root);
        assertEquals(1, menus.size());
        assertTrue(menus.get(0).performClick());
        idle();
    }

    private static List<TextView> collectAll(View root, String text) {
        List<TextView> all = new ArrayList<>();
        collect(root, text, all);
        return all;
    }

    private static List<View> byDescription(View view, String description) {
        List<View> out = new ArrayList<>();
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

    private static void touch(View target, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(0L, 0L, action, x, y, 0);
        target.dispatchTouchEvent(event);
        event.recycle();
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

    private static void clickFirstEnabled(View root, String text) {
        List<TextView> all = new ArrayList<>();
        collect(root, text, all);
        for (TextView view : all) {
            if (view.isEnabled()) {
                view.performClick();
                idle();
                return;
            }
        }
        throw new AssertionError("no enabled " + text);
    }

    private static void idle() {
        ShadowLooper.idleMainLooper();
    }

    private static void click(View root, String text) {
        TextView view = find(root, text);
        assertNotNull("no view with text " + text, view);
        assertTrue(view.performClick());
        idle();
    }

    private static void clickLast(View root, String text) {
        List<TextView> all = new ArrayList<>();
        collect(root, text, all);
        assertTrue(!all.isEmpty());
        all.get(all.size() - 1).performClick();
        idle();
    }

    private static TextView find(View root, String text) {
        List<TextView> all = new ArrayList<>();
        collect(root, text, all);
        return all.isEmpty() ? null : all.get(0);
    }

    private static void collect(View view, String text, List<TextView> out) {
        if (view.getVisibility() != View.VISIBLE) {
            return;
        }
        if (view instanceof TextView && !(view instanceof EditText)
                && text.contentEquals(((TextView) view).getText())) {
            out.add((TextView) view);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                collect(group.getChildAt(i), text, out);
            }
        }
    }

    private static EditText findEdit(View view) {
        if (view.getVisibility() != View.VISIBLE) {
            return null;
        }
        if (view instanceof EditText) {
            return (EditText) view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                EditText found = findEdit(group.getChildAt(i));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

}
