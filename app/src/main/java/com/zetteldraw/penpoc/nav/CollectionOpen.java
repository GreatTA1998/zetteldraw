package com.zetteldraw.penpoc.nav;

import com.zetteldraw.penpoc.Board;

import java.util.List;

/**
 * UI open path: resolve scroll Y for a <em>Ready</em> page list.
 *
 * <p>Length SoT is the repository / ink sheet — never prefs or trust
 * snapshots. This module only answers "where to scroll". CanvasActivity
 * loads Ready pages and paints; it must not invent page counts here.
 *
 * @see CollectionNav
 * @see docs/ink-sot-rewrite.md
 */
public final class CollectionOpen {
    private CollectionOpen() {
    }

    /** Ready pages + scroll target for paint. */
    public static final class Bind {
        public final List<Board> pages;
        public final int scrollY;

        public Bind(List<Board> pages, int scrollY) {
            this.pages = pages;
            this.scrollY = scrollY;
        }
    }

    /**
     * Scroll-only bind for a Ready list (snappy open or background load finished).
     * Does not check {@code isSheetReady} — caller already has the Ready pages.
     */
    public static Bind bindScroll(
            CollectionNav nav,
            String collectionId,
            List<Board> pages,
            Integer keepScroll,
            boolean scratchpad,
            CollectionNav.PageLayout layout) {
        if (nav == null || collectionId == null || pages == null || pages.isEmpty() || layout == null) {
            return null;
        }
        int target = nav.resolveTargetY(collectionId, keepScroll, pages, scratchpad, layout);
        String anchor = nav.anchorPageId(pages, target, layout);
        nav.rememberScroll(collectionId, target, pages.size(), anchor);
        return new Bind(pages, target);
    }
}
