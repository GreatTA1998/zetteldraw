package com.zetteldraw.penpoc;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.view.View;
import android.widget.TextView;

import com.zetteldraw.penpoc.data.ZettelData;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

/**
 * Shared open path: nav may appear, but no page chrome until Ready ink.
 * Structure preview must not paint blank pages that tempt drawing.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class, qualifiers = "w600dp-h1000dp",
        shadows = IdleSurfaceViewShadow.class)
public class OpenLoadingShellTest {
    @Before
    public void setUp() {
        ZettelData.resetForTest();
        UiExecutors.useSynchronousForTest();
    }

    @Test
    public void whileEnsureBlockedOnlyLoadingShellNoPageChrome() {
        // Block ensure so the open shell stays up (same pattern as LaunchTest stuck load).
        UiExecutors.loader = task -> { };

        ActivityController<CanvasActivity> controller =
                Robolectric.buildActivity(CanvasActivity.class).setup();
        View root = controller.get().getWindow().getDecorView();
        ShadowLooper.idleMainLooper();

        assertTrue("no page numbers while ink not Ready",
                CanvasActivitySmokeTest.pageLabels(root).isEmpty());
        assertNotNull("loading shell visible", findText(root, "Loading pages…"));
        PageInkView ink = findInk(root);
        assertNotNull(ink);
        assertFalse("pen held off during open shell", ink.inkEnabled());

        controller.pause().stop().destroy();
    }

    private static TextView findText(View view, String text) {
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) {
            return (TextView) view;
        }
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
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
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                PageInkView found = findInk(group.getChildAt(i));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
