package com.zetteldraw.penpoc.data;

import com.zetteldraw.penpoc.Board;

/**
 * Ready helpers for tests after the soft trailing blank was deleted.
 * Ensures a sheet and, when empty, one real blank slice (Create new page).
 */
public final class TestSheets {
    private TestSheets() {
    }

    public static boolean ready(BoardRepository repo, String notebookId, int pageHeight) {
        if (!repo.ensureSheet(notebookId, pageHeight, pageHeight, 0L)) {
            return false;
        }
        if (repo.pages(notebookId).isEmpty()) {
            return repo.createPage(notebookId) != null;
        }
        return true;
    }

    public static Board blank(BoardRepository repo, String notebookId) {
        if (!repo.isSheetReady(notebookId)) {
            ready(repo, notebookId, Device.PAGE);
        }
        java.util.List<Board> pages = repo.pages(notebookId);
        if (pages.isEmpty()) {
            return repo.createPage(notebookId);
        }
        Board last = pages.get(pages.size() - 1);
        return last.isBlank() ? last : repo.createPage(notebookId);
    }
}
