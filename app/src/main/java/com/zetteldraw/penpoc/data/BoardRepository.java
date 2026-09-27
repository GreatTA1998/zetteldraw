package com.zetteldraw.penpoc.data;

import com.zetteldraw.penpoc.Board;

import java.util.List;

/**
 * What the UI calls. Every write finishes locally (SQLite row + ink file)
 * before returning and never touches the network. Returned {@link Board}
 * instances are shared: the same id always maps to the same object, so the
 * UI can mutate {@code strokes} and then call {@link #saveInk}.
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

    /** Persist the page's current strokes. No-op when nothing changed. */
    void saveInk(Board page);

    /** Append the page as the newest page of the notebook. */
    void movePageToNotebook(String boardId, String notebookId);

    /** Clear one page's ink. */
    void wipePage(String boardId);

    /**
     * Tombstones the page (blank or not) so the delete syncs. A list's
     * trailing blank page is never lost: deleting it just leaves a fresh one.
     */
    void deletePage(String boardId);

    /** New notebook, ordered after every existing one. Blank titles are rejected (returns null). */
    NotebookInfo createNotebook(String title);

    /** Blank titles are ignored. */
    void renameNotebook(String notebookId, String title);

    /**
     * Tombstones the notebook. Its pages are not deleted: they move back to
     * the Scratchpad, appended as newest in their notebook order.
     */
    void deleteNotebook(String notebookId);

    /** Runs on the UI thread after sync pulled changes in. Null to clear. */
    void setRemoteChangeListener(Runnable listener);

    final class NotebookInfo {
        public final String id;
        public final String title;

        public NotebookInfo(String id, String title) {
            this.id = id;
            this.title = title;
        }
    }
}
