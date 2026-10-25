package com.zetteldraw.penpoc;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Seed notebooks. A notebook is an ordered sequence of boards.
 */
public enum Notebook {
    COMEDY("comedy", "comedy"),
    JOURNAL("journal", "journal"),
    ACTIONS_LIFE("actions.life", "actions.life"),
    MISCELLANEOUS("miscellaneous", "miscellaneous"),
    /** Built-in recover bin — pages land here via Delete (Move); not a tombstone. */
    TRASH("trash", "Trash");

    public final String id;
    public final String label;
    /**
     * Stable across devices so every install seeds the same notebook rows
     * and sync does not create duplicates.
     */
    public final String uuid;

    Notebook(String id, String label) {
        this.id = id;
        this.label = label;
        this.uuid = UUID.nameUUIDFromBytes(("zetteldraw:notebook:" + id)
                .getBytes(StandardCharsets.UTF_8)).toString();
    }

    /** True for the built-in Trash notebook (seeded like other enum rows). */
    public static boolean isTrash(String notebookId) {
        return TRASH.uuid.equals(notebookId);
    }
}
