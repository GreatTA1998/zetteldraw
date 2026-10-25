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
 * Compact-nav helpers: notebooks are not listed on the top strip. Open the
 * shared hierarchy switcher (Switch notebook), then pick a title.
 */
final class NavClicks {
    private NavClicks() {}

    static void openNotebook(View root, String title) {
        View switcher = byDescription(root, "Switch notebook");
        assertNotNull("Switch notebook chip missing", switcher);
        assertTrue(switcher.performClick());
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
