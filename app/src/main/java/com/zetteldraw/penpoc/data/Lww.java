package com.zetteldraw.penpoc.data;

import com.zetteldraw.penpoc.data.db.BoardEntity;

import java.util.Objects;

/**
 * Last-write-wins per row, same rule as {@code server/src/lww.ts}: the newer
 * {@code updated_at} wins and the side that is already on the server wins a
 * tie. A losing board that still carries ink the winner does not have is kept
 * as a hidden conflict copy.
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
