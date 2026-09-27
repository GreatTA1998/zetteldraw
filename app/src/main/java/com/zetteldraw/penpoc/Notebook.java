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
    MISCELLANEOUS("miscellaneous", "miscellaneous");

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

    public static Notebook fromId(String id) {
        for (Notebook notebook : values()) {
            if (notebook.id.equals(id)) {
                return notebook;
            }
        }
        return null;
    }
}
