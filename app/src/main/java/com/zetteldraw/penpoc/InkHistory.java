package com.zetteldraw.penpoc;

import com.zetteldraw.penpoc.data.NotebookPaper;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Undo / redo for ink edits (pen strokes, eraser deletes, lasso moves on the notebook paper), by
 * stroke id so an edit still applies cleanly when other strokes changed in
 * between. One capped stack for the open page list; in memory only.
 */
final class InkHistory {
    static final int LIMIT = 50;

    private final ArrayDeque<Edit> undo = new ArrayDeque<>();
    private final ArrayDeque<Edit> redo = new ArrayDeque<>();

    synchronized void record(Edit edit) {
        if (edit == null || edit.parts.isEmpty()) {
            return;
        }
        undo.push(edit);
        while (undo.size() > LIMIT) {
            undo.removeLast();
        }
        redo.clear();
    }

    synchronized boolean canUndo() {
        return !undo.isEmpty();
    }

    synchronized boolean canRedo() {
        return !redo.isEmpty();
    }

    synchronized int undoSize() {
        return undo.size();
    }

    synchronized void clear() {
        undo.clear();
        redo.clear();
    }

    /** Drops edits touching pages that are no longer shown (deleted, moved away). */
    synchronized void retainPages(Set<String> pageIds) {
        undo.removeIf(edit -> !edit.onlyTouches(pageIds));
        redo.removeIf(edit -> !edit.onlyTouches(pageIds));
    }

    /** Reverts the newest edit; returns the pages it changed (empty when there was nothing to undo). */
    synchronized Set<Board> undo() {
        Edit edit = undo.poll();
        if (edit == null) {
            return Collections.emptySet();
        }
        redo.push(edit);
        return edit.revert();
    }

    /** Re-applies the newest undone edit; returns the pages it changed. */
    synchronized Set<Board> redo() {
        Edit edit = redo.poll();
        if (edit == null) {
            return Collections.emptySet();
        }
        undo.push(edit);
        return edit.apply();
    }

    /** One user action; an eraser pass can touch several pages. */
    static final class Edit {
        final List<Part> parts = new ArrayList<>();

        static Edit of(Part part) {
            Edit edit = new Edit();
            edit.add(part);
            return edit;
        }

        void add(Part part) {
            if (part != null && !part.isEmpty()) {
                parts.add(part);
            }
        }

        boolean onlyTouches(Set<String> pageIds) {
            for (Part part : parts) {
                if (!pageIds.contains(part.page.id)) {
                    return false;
                }
            }
            return true;
        }

        Set<Board> revert() {
            LinkedHashSet<Board> changed = new LinkedHashSet<>();
            for (int i = parts.size() - 1; i >= 0; i--) {
                if (parts.get(i).revert()) {
                    changed.add(parts.get(i).page);
                }
            }
            return changed;
        }

        Set<Board> apply() {
            LinkedHashSet<Board> changed = new LinkedHashSet<>();
            for (Part part : parts) {
                if (part.apply()) {
                    changed.add(part.page);
                }
            }
            return changed;
        }
    }

    /** What one edit did to one notebook paper: strokes added, removed, or shifted by id. */
    static final class Part {
        private enum PaperKind { NONE, ADD, REMOVE, SHIFT }

        final Board page;
        /** Paper edits append an inverse record. They do not rewrite the log. */
        private final PaperKind paperKind;
        private final List<InkRenderer.InkStroke> paperStrokes;
        private final List<String> paperIds;
        private final float paperDx;
        private final float paperDy;

        private Part(Board page, PaperKind paperKind, List<InkRenderer.InkStroke> paperStrokes,
                     List<String> paperIds, float paperDx, float paperDy) {
            this.page = page;
            this.paperKind = paperKind;
            this.paperStrokes = paperStrokes;
            this.paperIds = paperIds;
            this.paperDx = paperDx;
            this.paperDy = paperDy;
        }

        /** Pen-up on the paper. Undo deletes the stroke by id. Redo appends it again. */
        static Part paperAdded(Board page, InkRenderer.InkStroke stroke) {
            return paper(page, PaperKind.ADD, List.of(stroke), List.of(), 0f, 0f);
        }

        /** Paste / multi-stroke add on the paper. */
        static Part paperAdded(Board page, List<InkRenderer.InkStroke> strokes) {
            return paper(page, PaperKind.ADD, strokes, List.of(), 0f, 0f);
        }

        /** Eraser. The whole stroke, on every slice. */
        static Part paperRemoved(Board page, List<InkRenderer.InkStroke> strokes) {
            return paper(page, PaperKind.REMOVE, strokes, List.of(), 0f, 0f);
        }

        /** Lasso. Undo is the same shift with the sign flipped. Ids stay. */
        static Part paperShifted(Board page, Collection<String> ids, float dx, float dy) {
            return paper(page, PaperKind.SHIFT, List.of(), new ArrayList<>(ids), dx, dy);
        }

        private static Part paper(Board page, PaperKind kind, List<InkRenderer.InkStroke> strokes,
                                  List<String> ids, float dx, float dy) {
            return new Part(page, kind, strokes, ids, dx, dy);
        }

        boolean isEmpty() {
            return paperKind == PaperKind.NONE;
        }

        boolean revert() {
            return applyPaper(true);
        }

        boolean apply() {
            return applyPaper(false);
        }

        private boolean applyPaper(boolean undo) {
            NotebookPaper paper = page.paper;
            if (paper == null) {
                return false;
            }
            switch (paperKind) {
                case ADD:
                    if (undo) {
                        paper.deleteIds(idsOf(paperStrokes));
                    } else {
                        for (InkRenderer.InkStroke stroke : paperStrokes) {
                            paper.appendStroke(stroke);
                        }
                    }
                    return true;
                case REMOVE:
                    if (undo) {
                        for (InkRenderer.InkStroke stroke : paperStrokes) {
                            paper.appendStroke(stroke);
                        }
                    } else {
                        paper.deleteIds(idsOf(paperStrokes));
                    }
                    return true;
                case SHIFT:
                    paper.translate(paperIds, undo ? -paperDx : paperDx, undo ? -paperDy : paperDy);
                    return true;
                default:
                    return false;
            }
        }

        private static List<String> idsOf(List<InkRenderer.InkStroke> strokes) {
            ArrayList<String> ids = new ArrayList<>();
            for (InkRenderer.InkStroke stroke : strokes) {
                ids.add(stroke.id);
            }
            return ids;
        }
    }
}
