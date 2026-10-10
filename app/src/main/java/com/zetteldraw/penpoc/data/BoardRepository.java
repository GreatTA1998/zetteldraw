package com.zetteldraw.penpoc.data;

import com.zetteldraw.penpoc.Board;

import java.util.List;

/**
 * What the UI calls. Nothing here touches the network or waits on sync.
 * Structural writes (move, wipe, delete, notebooks) finish before returning;
 * {@link #saveInk} is write-behind. Returned {@link Board} instances are
 * shared: the same id always maps to the same object, so the UI thread can
 * mutate {@code strokes} and then call {@link #saveInk}. Only the UI thread
 * mutates {@code strokes}.
 */
public interface BoardRepository {
    List<NotebookInfo> notebooks();

    /**
     * One page list: the Scratchpad ({@code notebookId == null}) or a notebook,
     * oldest first. It always ends with one blank page that is not stored;
     * its first {@link #saveInk} stores it at the next position and a new
     * blank follows. Empty for a deleted or unknown notebook.
     */
    List<Board> pages(String notebookId);

    default List<Board> scratchpadPages() {
        return pages(null);
    }

    default List<Board> notebookPages(String notebookId) {
        return pages(notebookId);
    }

    /**
     * Persist the page's current strokes: takes an immutable snapshot now and
     * writes it in the background, newest snapshot per page wins. Never blocks
     * on ink encoding or file writes. No-op on disk when nothing changed.
     */
    void saveInk(Board page);

    /**
     * Switches this notebook onto its ink log. Page files are not rewritten
     * or deleted. The log is not pushed until it reads back identical to those pages.
     */
    void ensureSheet(String notebookId, int pageHeight, int legacyPageHeight, long shortPagesSince);

    /** Append the page as the newest page of the notebook. */
    void movePageToNotebook(String boardId, String notebookId);

    /**
     * Same-notebook only: place {@code boardId} immediately after
     * {@code afterBoardId}. No-op when the pages are missing, blank, the same,
     * or in different notebooks. Dense pages stay atomic (one log rewrite).
     */
    void reorderPageAfter(String boardId, String afterBoardId);

    /** Clear one page's ink. */
    void wipePage(String boardId);

    /**
     * Tombstones the page (blank or not) so the delete syncs. Every list ends
     * in exactly one blank page; that page cannot be deleted (a no-op), so a
     * delete never looks like it undid itself. A link that names this page is
     * tombstoned with it. Nothing is left that would show a missing page.
     */
    void deletePage(String boardId);

    /**
     * Stores one link from {@code sourceId} to {@code targetId}. Null when the
     * two ids are the same or that live pair already exists. Copies no ink.
     */
    PageLink createLink(String sourceId, String targetId);

    /** Live links that start or end at this page, oldest first. */
    List<PageLink> linksTouching(String pageId);

    /**
     * Where this page sits right now, read from the lists already in memory.
     * Null when the page is gone. {@code notebookId} null is the Scratchpad.
     * {@code index} is 0-based and {@code count} includes the trailing blank.
     */
    PagePlace placeOf(String pageId);

    /** New top-level notebook, ordered after every existing one. Blank titles are rejected (returns null). */
    NotebookInfo createNotebook(String title);

    /**
     * New notebook under {@code parentId}, or top-level when that is null.
     * Blank titles and a missing parent are rejected (returns null).
     */
    NotebookInfo createNotebook(String title, String parentId);

    /** Blank titles are ignored. */
    void renameNotebook(String notebookId, String title);

    /**
     * Tombstones the notebook. Its own pages move back to the Scratchpad.
     * Its children are not deleted: each is promoted to this notebook's parent
     * (or to top-level) and keeps its pages.
     */
    void deleteNotebook(String notebookId);

    /**
     * Files the notebook under {@code parentId}, or makes it top-level when
     * that is null. False when the parent is missing, or is this notebook or
     * one of its descendants. Already being there changes nothing.
     */
    boolean placeNotebook(String notebookId, String parentId);

    /**
     * Runs on the UI thread after sync pulled changes in, once per sync pass.
     * Each screen adds its own and removes only its own, so one screen
     * closing can't silence another.
     */
    void addRemoteChangeListener(Runnable listener);

    void removeRemoteChangeListener(Runnable listener);

    final class NotebookInfo {
        public final String id;
        public final String title;
        /** Null when this notebook is top-level. */
        public final String parentId;

        public NotebookInfo(String id, String title, String parentId) {
            this.id = id;
            this.title = title;
            this.parentId = parentId;
        }
    }

    /** One stored link. The label is resolved when the page is shown, not stored here. */
    final class PageLink {
        public final String id;
        public final String sourceId;
        public final String targetId;

        public PageLink(String id, String sourceId, String targetId) {
            this.id = id;
            this.sourceId = sourceId;
            this.targetId = targetId;
        }
    }

    /** A page's place in its notebook. {@code notebookId} null is the Scratchpad. */
    final class PagePlace {
        public final String notebookId;
        public final int index;
        public final int count;

        public PagePlace(String notebookId, int index, int count) {
            this.notebookId = notebookId;
            this.index = index;
            this.count = count;
        }
    }
}
