package com.zetteldraw.penpoc.data;

/**
 * Starts as {@link InkMirror#NONE} so cold open never touches Documents /
 * MediaProvider. Activated after first paint when the process is stable.
 */
final class DeferredMirror implements InkMirror {
    private volatile InkMirror active = InkMirror.NONE;

    void activate(InkMirror next) {
        if (next != null) {
            active = next;
        }
    }

    boolean isActive() {
        return active != InkMirror.NONE;
    }

    @Override
    public void writeIndex(String json) {
        active.writeIndex(json);
    }

    @Override
    public void writeLog(String sheetId, byte[] bytes) {
        active.writeLog(sheetId, bytes);
    }

    @Override
    public void deleteLog(String sheetId) {
        active.deleteLog(sheetId);
    }
}
