package com.zetteldraw.penpoc.data;

/**
 * User-visible backup of Ready ink logs plus a JSON index
 * ({@code Documents/zetteldraw/}) so an uninstall is not fatal.
 * Calls arrive on the repository's background executor; failures are
 * swallowed because the mirror is never the source of truth.
 *
 * <p>BoardEntity {@code .zdi} page-file copies are not mirrored — migration
 * residue lives only in app-private storage until {@code ensureSheet}.
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
