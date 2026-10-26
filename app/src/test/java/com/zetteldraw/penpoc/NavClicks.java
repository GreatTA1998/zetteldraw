package com.zetteldraw.penpoc;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayList;
import java.util.List;

/**
 * Compact-nav helpers. Chip tap: if named notebook ≠ active → select it;
 * if already active → toggle hierarchy menu. Tests that need a different
 * notebook may need one select-tap then a menu-tap.
 */
final class NavClicks {
    private NavClicks() {}

    static void openNotebook(View root, String title) {
        View switcher = byDescription(root, "Switch notebook");
        assertNotNull("Switch notebook chip missing", switcher);
        TextView chip = (TextView) switcher;
        String chipTitle = chip.getText().toString();
        boolean chipIsTarget = title.contentEquals(chipTitle);
        boolean chipActive = switcher.isSelected();

        if (chipIsTarget && !chipActive) {
            // Scratchpad (or other non-chip collection): first tap selects it.
            assertTrue(switcher.performClick());
            idle();
            assertTrue("chip should be active after select", switcher.isSelected());
            return;
        }
        if (chipIsTarget) {
            // Already on the target: toggle menu and re-pick to refresh the open sheet
            // (same path as a user who opens the hierarchy and chooses the current notebook).
            assertTrue(switcher.performClick());
            idle();
            TextView again = last(root, title);
            assertNotNull(again);
            assertTrue(again.performClick());
            idle();
            return;
        }

        // Need the hierarchy menu to pick a different title.
        if (!switcher.isSelected()) {
            // On Scratchpad: first tap selects the chip's MRU notebook.
            assertTrue(switcher.performClick());
            idle();
        }
        assertTrue("open hierarchy menu", switcher.performClick());
        idle();
        TextView pick = last(root, title);
        assertNotNull("hierarchy missing " + title, pick);
        assertTrue(pick.performClick());
        idle();
    }

    static void longPressOpenNotebook(View root, String title) {
        openNotebook(root, title);
        View switcher = byDescription(root, "Switch notebook");
        assertNotNull(switcher);
        assertTrue(switcher.performLongClick());
        idle();
    }

    static View byDescription(View view, String description) {
        if (view.getVisibility() == View.VISIBLE
                && description.contentEquals(String.valueOf(view.getContentDescription()))) {
            return view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = byDescription(group.getChildAt(i), description);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static TextView last(View root, String text) {
        ArrayList<TextView> all = new ArrayList<>();
        collectText(root, text, all);
        assertFalse("no view with text " + text, all.isEmpty());
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

    private static void idle() {
        ShadowLooper.idleMainLooper();
    }
}
