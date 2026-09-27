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

    /** Oldest first; always ends with one blank page. */
    List<Board> scratchpadPages();

    /** Oldest first, newest last. */
    List<Board> notebookPages(String notebookId);

    /**
     * Make sure the scratchpad ends with a blank page (call after the last
     * page gets its first stroke). Returns that blank page. Blank pages are
     * not stored until they get ink.
     */
    Board createScratchpadPage();

    /** Persist the page's current strokes. No-op when nothing changed. */
    void saveInk(Board page);

    /** Append the page as the newest page of the notebook. */
    void movePageToNotebook(String boardId, String notebookId);

    /** Clear one page's ink. */
    void wipePage(String boardId);

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
