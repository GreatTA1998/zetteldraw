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
    @Test
    public void createRenameDeleteNotebookThroughTheUi() {
        ActivityController<CanvasActivity> controller = Robolectric.buildActivity(CanvasActivity.class).setup();
        CanvasActivity activity = controller.get();
        View root = activity.getWindow().getDecorView();
        BoardRepository repo = ZettelData.repository(activity);
        idle();

        click(root, "Notebooks");
        assertNotNull("seed tab shown", find(root, "comedy"));

        click(root, "+");
        EditText name = findEdit(root);
        assertNotNull("name form shown in-window", name);
        name.setText("sketches");
        click(root, "Create");
        assertNull("form closed", findEdit(root));
        assertEquals(5, repo.notebooks().size());
        assertNotNull("new tab shown", find(root, "sketches"));

        click(root, "⋯");
        click(root, "Rename");
        name = findEdit(root);
        assertEquals("sketches", name.getText().toString());
        name.setText("drawings");
        click(root, "Save");
        assertEquals("drawings", repo.notebooks().get(4).title);
        assertNotNull(find(root, "drawings"));

        click(root, "⋯");
        click(root, "Delete");
        assertNotNull("confirmation shown", find(root, "Delete “drawings”?"));
        click(root, "Cancel");
        assertEquals(5, repo.notebooks().size());

        click(root, "⋯");
        click(root, "Delete");
        clickLast(root, "Delete");
        assertEquals(4, repo.notebooks().size());
        assertNull(find(root, "drawings"));

        controller.pause().stop().destroy();
    }

    @Test
    public void moveFilesPagesIntoExistingAndNewNotebooks() {
        Application app = androidx.test.core.app.ApplicationProvider.getApplicationContext();
        BoardRepository repo = ZettelData.repository(app);
        Board first = repo.scratchpadPages().get(0);
        first.strokes.add(com.zetteldraw.penpoc.data.TestStrokes.stroke(20f, 20f));
        repo.saveInk(first);
        Board second = repo.createScratchpadPage();
        second.strokes.add(com.zetteldraw.penpoc.data.TestStrokes.stroke(40f, 40f));
        repo.saveInk(second);
        repo.createScratchpadPage();

        ActivityController<CanvasActivity> controller = Robolectric.buildActivity(CanvasActivity.class).setup();
        View root = controller.get().getWindow().getDecorView();
        idle();

        clickFirstEnabled(root, "Move");
        click(root, "journal");
        assertEquals(1, repo.notebookPages(Notebook.JOURNAL.uuid).size());

        clickFirstEnabled(root, "Move");
        click(root, "+ New notebook");
        EditText name = findEdit(root);
        name.setText("fresh");
        click(root, "Create");
        BoardRepository.NotebookInfo fresh = repo.notebooks().get(4);
        assertEquals("fresh", fresh.title);
        assertEquals(1, repo.notebookPages(fresh.id).size());
        assertEquals(1, repo.scratchpadPages().size());
        assertTrue(repo.scratchpadPages().get(0).isBlank());

        controller.pause().stop().destroy();
    }

    @Test
    public void lassoDragMovesStrokesThenUndoPutsThemBack() {
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
        click(root, "Notebooks");
        click(root, "lasso");
        PageInkView ink = findInk(root);
        assertNotNull(ink);

        TextView undo = find(root, "Undo move");
        assertFalse("nothing to undo yet", undo.isEnabled());
        click(root, "Lasso");
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

        click(root, "Undo move");
        assertSame(original, page.strokes.get(0));
        assertFalse(undo.isEnabled());

        click(root, "Lasso");
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
