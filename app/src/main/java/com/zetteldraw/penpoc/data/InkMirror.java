package com.zetteldraw.penpoc.data;

/**
 * User-visible backup of ink files plus a JSON index
 * ({@code Documents/zetteldraw/}) so an uninstall is not fatal.
 * Calls arrive on the repository's background executor; failures are
 * swallowed because the mirror is never the source of truth.
 */
public interface InkMirror {
    void writeInk(String boardId, byte[] bytes);

    void deleteInk(String boardId);

    void writeIndex(String json);

    /** Notebook log. Default no-op so older mirrors keep compiling. */
    default void writeLog(String sheetId, byte[] bytes) {
    }

    default void deleteLog(String sheetId) {
    }

    InkMirror NONE = new InkMirror() {
        @Override
        public void writeInk(String boardId, byte[] bytes) {
        }

        @Override
        public void deleteInk(String boardId) {
        }

        @Override
        public void writeIndex(String json) {
        }
    };
}
