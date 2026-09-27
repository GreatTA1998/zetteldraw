package com.zetteldraw.penpoc;

import java.util.ArrayList;
import java.util.UUID;

/**
 * Atomic page: an ordered stroke list. Blank boards have no strokes.
 */
public final class Board {
    public final String id;
    public final long createdAt;
    public final ArrayList<InkRenderer.InkStroke> strokes = new ArrayList<>();

    public Board(String id, long createdAt) {
        this.id = id;
        this.createdAt = createdAt;
    }

    public static Board blank() {
        return new Board(UUID.randomUUID().toString(), System.currentTimeMillis());
    }

    public boolean isBlank() {
        return strokes.isEmpty();
    }

    Board copy() {
        Board copy = new Board(id, createdAt);
        copy.strokes.addAll(strokes);
        return copy;
    }
}
