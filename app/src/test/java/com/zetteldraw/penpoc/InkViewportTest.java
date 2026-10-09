package com.zetteldraw.penpoc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.onyx.android.sdk.data.note.TouchPoint;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class InkViewportTest {
    private final Board a = new Board("a", 1L);
    private final Board b = new Board("b", 2L);
    private final Board c = new Board("c", 3L);

    private InkViewport.Layout layout(Board... pages) {
        int[] heights = new int[pages.length];
        Arrays.fill(heights, 1000);
        return new InkViewport.Layout(Arrays.asList(pages), heights, 24);
    }

    @Test
    public void surfacePointsMapThroughTheScrollTheyWereDrawnAt() {
        InkViewport top = new InkViewport(layout(a, b), 0);
        InkViewport scrolled = top.withScroll(800);
        List<TouchPoint> pen = Collections.singletonList(new TouchPoint(50f, 900f, 0.5f, 1f, 0, 0, 1L));

        assertEquals(0, top.pageIndexAt(900f));
        assertEquals(900f, top.toPage(pen, 0).get(0).y, 0f);

        assertEquals("the same surface point is on page 2 after scrolling", 1, scrolled.pageIndexAt(900f));
        assertEquals(1700f - 1024f, scrolled.toPage(pen, 1).get(0).y, 0f);
        assertEquals("the original points are never touched", 900f, pen.get(0).y, 0f);
    }

    @Test
    public void theGapBelowAPageBelongsToThatPage() {
        InkViewport view = new InkViewport(layout(a, b), 0);
        assertEquals(0, view.pageIndexAt(1010f));
        assertEquals(1, view.pageIndexAt(1024f));
        assertEquals(-1, view.pageIndexAt(5000f));
        assertEquals(-1, new InkViewport(layout(a), 0).pageIndexAt(-5f));
    }

    @Test
    public void appendingAPageKeepsEveryStrokeMapping() {
        InkViewport before = new InkViewport(layout(a, b), 300);
        InkViewport longer = new InkViewport(layout(a, b, c), before.scrollY);
        assertTrue(before.mapsLike(longer));
        // The longer list is not a subset of the shorter one — callers that ask
        // whether geometry remapped must use shown.mapsLike(next), not the reverse.
        assertFalse("next.mapsLike(shown) is the wrong direction for an append",
                longer.mapsLike(before));
        assertTrue(before.mapsLike(before.withScroll(300)));
        assertFalse("scrolling remaps", before.mapsLike(before.withScroll(301)));
        assertFalse("another list remaps", before.mapsLike(new InkViewport(layout(c), before.scrollY)));
        assertFalse("a page moved remaps", before.mapsLike(new InkViewport(layout(b, a), before.scrollY)));
        int[] taller = {1000, 1400};
        assertFalse("a page resized remaps",
                before.mapsLike(new InkViewport(new InkViewport.Layout(Arrays.asList(a, b), taller, 24),
                        before.scrollY)));
    }

    @Test
    public void visibleRangeFollowsScroll() {
        InkViewport view = new InkViewport(layout(a, b, c), 1500);
        assertEquals(1, view.firstVisible());
        assertEquals(2, view.lastVisible(900));
        assertEquals(1024f - 1500f, view.pageTop(1), 0f);
        assertSame(b, view.page(view.firstVisible()));
    }
}
