package com.zetteldraw.penpoc.nav;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import com.zetteldraw.penpoc.Board;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class)
public class CollectionNavTest {
    private CollectionNav nav;
    private final CollectionNav.PageLayout layout = new CollectionNav.PageLayout() {
        @Override
        public int heightOf(Board page) {
            return 100;
        }

        @Override
        public int gap(List<Board> pages) {
            return 0;
        }

        @Override
        public int lastInkOffset(List<Board> pages) {
            return Math.max(0, (pages.size() - 2) * 100);
        }
    };

    @Before
    public void setUp() {
        nav = new CollectionNav(ApplicationProvider.getApplicationContext()
                .getSharedPreferences("canvas-nav-test", Context.MODE_PRIVATE));
    }

    @Test
    public void restoreUsesFingerprint() {
        List<Board> pages = pages(5);
        nav.rememberScroll("scratchpad", 200, 5, pages.get(2).id);
        int y = nav.resolveTargetY("scratchpad", null, pages, true, layout);
        assertEquals(200, y);
    }

    @Test
    public void fingerprintMismatchFallsBackToFirstOpenTrailingBlank() {
        List<Board> pages = pages(5);
        nav.rememberScroll("scratchpad", 350, 5, pages.get(2).id);
        List<Board> shorter = pages(3);
        int y = nav.resolveTargetY("scratchpad", null, shorter, true, layout);
        assertEquals("count mismatch ⇒ first-open trailing blank, not stale Y", 200, y);
    }

    @Test
    public void clearRemovesLegacyTrustKey() {
        android.content.SharedPreferences prefs = ApplicationProvider.getApplicationContext()
                .getSharedPreferences("canvas-nav-clear", Context.MODE_PRIVATE);
        prefs.edit().putInt(CollectionNav.PREF_TRUST_PAGES_PREFIX + "scratchpad", 23).apply();
        CollectionNav local = new CollectionNav(prefs);
        local.clear("scratchpad");
        assertFalse(prefs.contains(CollectionNav.PREF_TRUST_PAGES_PREFIX + "scratchpad"));
    }

    @Test
    public void rememberScrollDropsLegacyTrust() {
        android.content.SharedPreferences prefs = ApplicationProvider.getApplicationContext()
                .getSharedPreferences("canvas-nav-drop-trust", Context.MODE_PRIVATE);
        prefs.edit().putInt(CollectionNav.PREF_TRUST_PAGES_PREFIX + "scratchpad", 23).apply();
        CollectionNav local = new CollectionNav(prefs);
        local.rememberScroll("scratchpad", 10, 39, "p0");
        assertFalse("length trust must not survive paint",
                prefs.contains(CollectionNav.PREF_TRUST_PAGES_PREFIX + "scratchpad"));
        assertTrue(prefs.contains(CollectionNav.PREF_SCROLL_PREFIX + "scratchpad"));
    }

    private static List<Board> pages(int n) {
        ArrayList<Board> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            list.add(new Board("p" + i, 1L));
        }
        return list;
    }
}
