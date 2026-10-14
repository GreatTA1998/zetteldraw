package com.zetteldraw.penpoc.data;

import com.zetteldraw.penpoc.data.db.BoardEntity;

import java.util.Objects;

/**
 * Clock compare shared with {@code server/src/lww.ts}. Product sync is single
 * live writer (push) + pull catch-up / Hand off — not multi-device co-edit.
 * Newer {@code updated_at} wins; server wins ties. Used for equal-length
 * catch-up and the exception shelf path (Hand off / history), not peer merge.
 */
public final class Lww {
    private Lww() {
    }

    /** Called with the pulled server row, so the server side wins ties. */
    public static boolean remoteWins(long localUpdatedAt, long remoteUpdatedAt) {
        return remoteUpdatedAt >= localUpdatedAt;
    }

    public static boolean needsConflictCopy(BoardEntity loser, BoardEntity winner) {
        if (loser.inkHash == null || loser.deletedAt != null) {
            return false;
        }
        return winner.deletedAt != null || !Objects.equals(loser.inkHash, winner.inkHash);
    }
}
