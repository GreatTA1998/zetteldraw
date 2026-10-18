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
    public record Bind(List<Board> pages, int scrollY) {
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
        if (nav == null || collectionId == null || pages == null || layout == null) {
            return null;
        }
        // Empty Ready list is valid (Create new page only) — scroll Y is 0.
        if (pages.isEmpty()) {
            nav.rememberScroll(collectionId, 0, 0, null);
            return new Bind(pages, 0);
        }
        int target = nav.resolveTargetY(collectionId, keepScroll, pages, scratchpad, layout);
        String anchor = nav.anchorPageId(pages, target, layout);
        nav.rememberScroll(collectionId, target, pages.size(), anchor);
        return new Bind(pages, target);
    }
}
