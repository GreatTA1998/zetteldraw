package com.zetteldraw.penpoc;

/**
 * Seed notebooks. A notebook is an ordered sequence of boards.
 */
enum Notebook {
    COMEDY("comedy", "comedy"),
    JOURNAL("journal", "journal"),
    ACTIONS_LIFE("actions.life", "actions.life"),
    MISCELLANEOUS("miscellaneous", "miscellaneous");

    final String id;
    final String label;

    Notebook(String id, String label) {
        this.id = id;
        this.label = label;
    }

    static Notebook fromId(String id) {
        for (Notebook notebook : values()) {
            if (notebook.id.equals(id)) {
                return notebook;
            }
        }
        return null;
    }
}
