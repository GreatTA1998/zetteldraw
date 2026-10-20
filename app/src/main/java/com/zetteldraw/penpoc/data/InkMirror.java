package com.zetteldraw.penpoc.data;

/**
 * User-visible backup of Ready ink logs plus a JSON index
 * ({@code Documents/zetteldraw/}) so an uninstall is not fatal.
 * Calls arrive on the repository's background executor; failures are
 * swallowed because the mirror is never the source of truth.
 *
 * <p>Index catalogs Ready {@code .zdl} ({@code ink_format: zdl}) only.
 */
public interface InkMirror {
    void writeIndex(String json);

    /** Notebook log. Default no-op so older mirrors keep compiling. */
    default void writeLog(String sheetId, byte[] bytes) {
    }

    default void deleteLog(String sheetId) {
    }

    InkMirror NONE = new InkMirror() {
        @Override
        public void writeIndex(String json) {
        }
    };
}
