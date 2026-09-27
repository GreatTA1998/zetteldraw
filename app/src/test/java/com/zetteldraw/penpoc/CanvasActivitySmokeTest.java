package com.zetteldraw.penpoc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

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
