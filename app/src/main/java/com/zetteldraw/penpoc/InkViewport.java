package com.zetteldraw.penpoc;

import com.onyx.android.sdk.data.note.TouchPoint;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

/**
 * What the ink surface shows: a page layout plus the scroll offset. Immutable,
 * so a stroke can be converted with exactly the geometry that was on screen
 * while it was drawn, however late its points arrive.
 */
final class InkViewport {
    static final InkViewport EMPTY = new InkViewport(Layout.EMPTY, 0);

    /** Pages stacked top to bottom, each with its own height and a fixed gap below it. */
    static final class Layout {
        static final Layout EMPTY = new Layout(Collections.emptyList(), new int[0], 0);

        final List<Board> pages;
        /** Content y of each page's top edge. */
        final int[] tops;
        final int[] heights;
        final int gap;
        private final HashMap<String, Integer> indexById = new HashMap<>();

        /** {@code pageHeights[i]} is the height of {@code pages.get(i)}. */
        Layout(List<Board> pages, int[] pageHeights, int gap) {
            this.pages = Collections.unmodifiableList(new ArrayList<>(pages));
            this.gap = Math.max(0, gap);
            tops = new int[pages.size()];
            heights = new int[pages.size()];
            int y = 0;
            for (int i = 0; i < pages.size(); i++) {
                tops[i] = y;
                heights[i] = Math.max(1, pageHeights[i]);
                y += heights[i] + this.gap;
                indexById.put(pages.get(i).id, i);
            }
        }

        int size() {
            return pages.size();
        }

        int indexOf(String pageId) {
            Integer index = indexById.get(pageId);
            return index == null ? -1 : index;
        }
    }

    final Layout layout;
    final int scrollY;

    InkViewport(Layout layout, int scrollY) {
        this.layout = layout;
        this.scrollY = Math.max(0, scrollY);
    }

    InkViewport withScroll(int y) {
        return Math.max(0, y) == scrollY ? this : new InkViewport(layout, y);
    }

    boolean isEmpty() {
        return layout.pages.isEmpty();
    }

    Board page(int index) {
        return layout.pages.get(index);
    }

    /** Surface y of the page's top edge. */
    float pageTop(int index) {
        return (float) layout.tops[index] - scrollY;
    }

    /** Page under a surface y (its gap below counts as the page), or -1. */
    int pageIndexAt(float surfaceY) {
        if (layout.pages.isEmpty()) {
            return -1;
        }
        float y = surfaceY + scrollY;
        if (y < 0) {
            return -1;
        }
        int index = 0;
        while (index + 1 < layout.size() && layout.tops[index + 1] <= y) {
            index++;
        }
        return y < layout.tops[index] + layout.heights[index] + layout.gap ? index : -1;
    }

    /** Copies of {@code surfacePoints} in page {@code index}'s own coordinates. */
    ArrayList<TouchPoint> toPage(List<TouchPoint> surfacePoints, int index) {
        ArrayList<TouchPoint> copy = InkRenderer.copyPoints(surfacePoints);
        float dy = scrollY - (float) layout.tops[index];
        for (TouchPoint point : copy) {
            point.y += dy;
        }
        return copy;
    }

    int firstVisible() {
        int index = 0;
        while (index < layout.size() && layout.tops[index] + layout.heights[index] + layout.gap <= scrollY) {
            index++;
        }
        return index;
    }

    int lastVisible(int surfaceHeight) {
        int bottom = scrollY + Math.max(1, surfaceHeight);
        int index = layout.size() - 1;
        while (index > 0 && layout.tops[index] > bottom) {
            index--;
        }
        return index;
    }

    /**
     * True when every page shown here sits at the same surface position in
     * {@code other}, so any stroke on those pages converts identically under
     * both (e.g. a blank page was appended after the last one).
     */
    boolean mapsLike(InkViewport other) {
        if (other == this) {
            return true;
        }
        if (scrollY != other.scrollY || layout.gap != other.layout.gap) {
            return false;
        }
        for (int i = 0; i < layout.size(); i++) {
            int j = other.layout.indexOf(layout.pages.get(i).id);
            if (j < 0 || other.layout.pages.get(j) != layout.pages.get(i)
                    || other.layout.tops[j] != layout.tops[i] || other.layout.heights[j] != layout.heights[i]) {
                return false;
            }
        }
        return true;
    }
}
