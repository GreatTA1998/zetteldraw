package com.zetteldraw.penpoc;

import java.util.ArrayList;
import java.util.UUID;

/**
 * Atomic page: an ordered stroke list. Blank boards have no strokes.
 */
final class Board {
    final String id;
    final long createdAt;
    final ArrayList<InkRenderer.InkStroke> strokes = new ArrayList<>();

    Board(String id, long createdAt) {
        this.id = id;
        this.createdAt = createdAt;
    }

    static Board blank() {
        return new Board(UUID.randomUUID().toString(), System.currentTimeMillis());
    }

    boolean isBlank() {
        return strokes.isEmpty();
    }

    Board copy() {
        Board copy = new Board(id, createdAt);
        copy.strokes.addAll(strokes);
        return copy;
    }
}
