package com.zetteldraw.penpoc;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.view.View;

import androidx.test.core.app.ApplicationProvider;

import com.zetteldraw.penpoc.data.TestInk;
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
 * UI: Scratchpad with a tear in its log must show multi-page chrome after open
 * and after switching to another notebook and back (v70 1/1 regression).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class, qualifiers = "w600dp-h1000dp",
        shadows = IdleSurfaceViewShadow.class)
public class ScratchpadReentryUiTest {
    @Before
    public void freshRepository() {
        ZettelData.resetForTest();
        UiExecutors.useSynchronousForTest();

        BoardRepository _repo = ZettelData.repository(
                androidx.test.core.app.ApplicationProvider.getApplicationContext());
        _repo.ensureSheet(null, 800, 800, 0L);
        if (_repo.scratchpadPages().isEmpty()) {
            _repo.createPage(null);
        }

    }

    @Test
    public void scratchpadMultiPageSurvivesNotebookSwitchAndReturn() {
        Application app = ApplicationProvider.getApplicationContext();
        BoardRepository repo = ZettelData.repository(app);

        ActivityController<CanvasActivity> probe = Robolectric.buildActivity(CanvasActivity.class).setup();
        View root = probe.get().getWindow().getDecorView();
        idle();
        Board blank = repo.scratchpadPages().get(0);
        int height = blank.slicePx;
        assertTrue(height > 40);
        TestInk.appendCovering(blank.paper, TestStrokes.stroke(12f, 20f), blank.id);
        TestInk.appendCovering(blank.paper, TestStrokes.stroke(12f, height + 20f));
        TestInk.appendCovering(blank.paper, TestStrokes.stroke(12f, 2f * height + 20f));
        blank.paper.tearDelete(1);
        repo.saveInk(blank);
        idle();
        ((RoomBoardRepository) repo).invalidateOpenSheet(null);
        probe.pause().stop().destroy();

        ActivityController<CanvasActivity> controller = Robolectric.buildActivity(CanvasActivity.class).setup();
        root = controller.get().getWindow().getDecorView();
        idle();
        List<String> openLabels = CanvasActivitySmokeTest.pageLabels(root);
        assertTrue("open Scratchpad must be multi-page, was " + openLabels, openLabels.size() > 1);
        assertFalse(repo.scratchpadPages().get(0).isBlank());

        assertTrue(findText(root, "comedy").performClick());
        idle();
        assertTrue(byDescription(root, "Scratchpad").get(0).performClick());
        idle();

        List<String> backLabels = CanvasActivitySmokeTest.pageLabels(root);
        assertTrue("return must keep multi-page Scratchpad, was " + backLabels, backLabels.size() > 1);
        assertFalse("ink present after return", repo.scratchpadPages().get(0).isBlank());
        assertNotNull(findText(root, backLabels.get(0)));

        controller.pause().stop().destroy();
    }

    private static void idle() {
        ShadowLooper.idleMainLooper();
    }

    private static android.widget.TextView findText(View view, String text) {
        if (view.getVisibility() != View.VISIBLE) {
            return null;
        }
        if (view instanceof android.widget.TextView
                && text.contentEquals(((android.widget.TextView) view).getText())) {
            return (android.widget.TextView) view;
        }
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                android.widget.TextView found = findText(group.getChildAt(i), text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static List<View> byDescription(View view, String description) {
        List<View> out = new java.util.ArrayList<>();
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
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                collectByDescription(group.getChildAt(i), description, out);
            }
        }
    }
}
