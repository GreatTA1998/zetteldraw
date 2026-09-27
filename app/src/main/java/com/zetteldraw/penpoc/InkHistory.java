package com.zetteldraw.penpoc;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Undo / redo for ink edits (pen strokes, eraser deletes, lasso moves), by
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

    /** A stroke and where it sat in its page's list. */
    static final class Placed {
        final InkRenderer.InkStroke stroke;
        final int index;

        Placed(InkRenderer.InkStroke stroke, int index) {
            this.stroke = stroke;
            this.index = index;
        }
    }

    /** What one edit did to one page: strokes added, removed, or replaced (moved) by id. */
    static final class Part {
        final Board page;
        private final List<Placed> added;
        private final List<Placed> removed;
        private final Map<String, InkRenderer.InkStroke> before;
        private final Map<String, InkRenderer.InkStroke> after;

        private Part(Board page, List<Placed> added, List<Placed> removed,
                     Map<String, InkRenderer.InkStroke> before, Map<String, InkRenderer.InkStroke> after) {
            this.page = page;
            this.added = added;
            this.removed = removed;
            this.before = before;
            this.after = after;
        }

        static Part added(Board page, InkRenderer.InkStroke stroke) {
            return new Part(page, List.of(new Placed(stroke, page.strokes.indexOf(stroke))),
                    List.of(), Map.of(), Map.of());
        }

        /** {@code removed} in ascending original index. */
        static Part removed(Board page, List<Placed> removed) {
            return new Part(page, List.of(), removed, Map.of(), Map.of());
        }

        static Part moved(Board page, Map<String, InkRenderer.InkStroke> before,
                          Map<String, InkRenderer.InkStroke> after) {
            return new Part(page, List.of(), List.of(), before, after);
        }

        boolean isEmpty() {
            return added.isEmpty() && removed.isEmpty() && before.isEmpty();
        }

        boolean revert() {
            boolean changed = removeIds(added);
            changed |= replace(before);
            changed |= insert(removed);
            return changed;
        }

        boolean apply() {
            boolean changed = removeIds(removed);
            changed |= replace(after);
            changed |= insert(added);
            return changed;
        }

        private boolean removeIds(List<Placed> strokes) {
            if (strokes.isEmpty()) {
                return false;
            }
            HashSet<String> ids = new HashSet<>();
            for (Placed placed : strokes) {
                ids.add(placed.stroke.id);
            }
            return page.strokes.removeIf(stroke -> ids.contains(stroke.id));
        }

        private boolean replace(Map<String, InkRenderer.InkStroke> byId) {
            boolean changed = false;
            for (int i = 0; i < page.strokes.size(); i++) {
                InkRenderer.InkStroke next = byId.get(page.strokes.get(i).id);
                if (next != null && next != page.strokes.get(i)) {
                    page.strokes.set(i, next);
                    changed = true;
                }
            }
            return changed;
        }

        private boolean insert(List<Placed> strokes) {
            boolean changed = false;
            for (Placed placed : strokes) {
                if (indexOfId(placed.stroke.id) >= 0) {
                    continue;
                }
                int at = placed.index < 0 ? page.strokes.size() : Math.min(placed.index, page.strokes.size());
                page.strokes.add(at, placed.stroke);
                changed = true;
            }
            return changed;
        }

        private int indexOfId(String id) {
            for (int i = 0; i < page.strokes.size(); i++) {
                if (page.strokes.get(i).id.equals(id)) {
                    return i;
                }
            }
            return -1;
        }
    }
}
