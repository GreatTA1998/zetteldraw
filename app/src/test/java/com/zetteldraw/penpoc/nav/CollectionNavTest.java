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
    public void rejectsShorterThanTrusted() {
        nav.commitTrusted("scratchpad", 49, 4000, "page-41");
        assertTrue(nav.rejectsShorter("scratchpad", 23));
        assertFalse(nav.rejectsShorter("scratchpad", 49));
        assertFalse(nav.rejectsShorter("scratchpad", 50));
    }

    @Test
    public void restoreUsesFingerprint() {
        List<Board> pages = pages(5);
        nav.commitTrusted("scratchpad", 5, 200, pages.get(2).id);
        int y = nav.resolveTargetY("scratchpad", null, pages, true, layout);
        assertEquals(200, y);
    }

    @Test
    public void fingerprintMismatchFallsBackToFirstOpenTrailingBlank() {
        List<Board> pages = pages(5);
        nav.commitTrusted("scratchpad", 5, 350, pages.get(2).id);
        List<Board> shorter = pages(3);
        int y = nav.resolveTargetY("scratchpad", null, shorter, true, layout);
        assertEquals("count mismatch ⇒ first-open trailing blank, not stale Y", 200, y);
    }

    private static List<Board> pages(int n) {
        ArrayList<Board> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            list.add(new Board("p" + i, 1L));
        }
        return list;
    }
}
