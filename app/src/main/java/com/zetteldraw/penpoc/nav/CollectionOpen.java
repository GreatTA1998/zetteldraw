package com.zetteldraw.penpoc.nav;

import com.zetteldraw.penpoc.Board;
import com.zetteldraw.penpoc.data.BoardRepository;

import java.util.List;

/**
 * UI open path: bind a <em>Ready</em> page list and resolve scroll Y.
 *
 * <p>Length SoT is {@link BoardRepository} / ink sheet — never prefs or trust
 * snapshots. This module only answers "what Ready list + where to scroll".
 * CanvasActivity paints the result; it must not invent page counts here.
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
     * If the notebook sheet is Ready with a non-empty projection, remember scroll
     * and return a bind. Null means the caller must {@code ensureSheet} / load.
     *
     * @param notebookId null = Scratchpad
     */
    public static Bind tryBindReady(
            BoardRepository repository,
            CollectionNav nav,
            String collectionId,
            String notebookId,
            Integer keepScroll,
            boolean scratchpad,
            CollectionNav.PageLayout layout) {
        if (repository == null || nav == null || collectionId == null || layout == null) {
            return null;
        }
        if (!repository.isSheetReady(notebookId)) {
            return null;
        }
        List<Board> pages = repository.pages(notebookId);
        if (pages == null || pages.isEmpty()) {
            return null;
        }
        return bindScroll(nav, collectionId, pages, keepScroll, scratchpad, layout);
    }

    /**
     * Scroll-only bind for an already-Ready list (background load finished).
     * Does not re-check {@link BoardRepository#isSheetReady}.
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
