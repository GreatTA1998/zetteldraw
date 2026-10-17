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
     * Persist ink write-behind. Ready pages ({@code paper != null}) flush the
     * sheet log; unmigrated pages snapshot {@link Board#strokes} to page files.
     * Never blocks on encoding or file I/O. Log-backed notebooks refuse the
     * BoardEntity path when paper is missing.
     */
    void saveInk(Board page);

    /**
     * Switches this notebook onto its ink log. Page files are not rewritten
     * or deleted. The log is not pushed until it reads back identical to those pages.
     *
     * @return true when the sheet is {@linkplain #isSheetReady Ready} afterward
     *     (already was, or install/migrate succeeded). False when height is
     *     unknown, the notebook is gone, or replay/migrate could not finish —
     *     the UI must not show or remember scroll for that collection yet.
     */
    boolean ensureSheet(String notebookId, int pageHeight, int legacyPageHeight, long shortPagesSince);

    /**
     * True when {@link #pages} for this notebook projects from a fully installed
     * ink-log sheet (not the legacy Room board-row fallback).
     */
    boolean isSheetReady(String notebookId);

    /**
     * Append the page as the newest inked page of the notebook (before its
     * trailing blank). Log-backed sheets use the same {@link #placePageBefore}
     * spine as the UI Move menu (slice id preserved, dest-first disk).
     *
     * @return false when nothing changed (missing source/dest, blank source, etc.)
     */
    boolean movePageToNotebook(String boardId, String notebookId);

    /**
     * Place {@code boardId} immediately before {@code beforeBoardId}.
     * Destination notebook is inferred from the target page's sheet.
     * Prefer {@link #placePageBefore(String, String, String)} with an explicit
     * dest so a stale on-screen target cannot misfile into the wrong notebook.
     * Same-notebook Reorder and cross-notebook Move share this spine (UI already
     * did; the old {@code reorderPageAfter} dual path is gone).
     *
     * @return false when nothing changed
     */
    boolean placePageBefore(String boardId, String beforeBoardId);

    /**
     * Place {@code boardId} immediately before {@code beforeBoardId} in
     * {@code destNotebookId} ({@code null} = Scratchpad).
     * Same notebook and cross-notebook both use paper {@code tearMove*} (same
     * paper reorders in place; other notebook tears across). When the target is
     * a trailing blank <em>or</em> belongs to a different sheet than
     * {@code destNotebookId}, the page is appended at the end of the destination
     * (so a stale midline page from the notebook you just left cannot steal the
     * move). Dense pages stay atomic.
     *
     * @return false when nothing changed (missing source/dest, blank source, etc.)
     */
    boolean placePageBefore(String boardId, String beforeBoardId, String destNotebookId);

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

    /** Null {@code parentId} when this notebook is top-level. */
    record NotebookInfo(String id, String title, String parentId) {
    }

    /** One stored link. The label is resolved when the page is shown, not stored here. */
    record PageLink(String id, String sourceId, String targetId) {
    }

    /** A page's place in its notebook. {@code notebookId} null is the Scratchpad. */
    record PagePlace(String notebookId, int index, int count) {
    }
}
