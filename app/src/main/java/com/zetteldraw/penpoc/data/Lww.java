package com.zetteldraw.penpoc.data;

import com.zetteldraw.penpoc.data.db.BoardEntity;
import com.zetteldraw.penpoc.data.db.NotebookLogEntity;

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

    /**
     * Shelve loser ink when it still has a body the winner does not share.
     * Same rule for legacy {@link BoardEntity} {@code .zdi} and Ready
     * {@link NotebookLogEntity} {@code .zdl} (matches {@code needsConflictCopy} in
     * {@code server/src/lww.ts}).
     */
    public static boolean needsConflictCopy(
            String loserHash, Long loserDeletedAt,
            String winnerHash, Long winnerDeletedAt) {
        if (loserHash == null || loserDeletedAt != null) {
            return false;
        }
        return winnerDeletedAt != null || !Objects.equals(loserHash, winnerHash);
    }

    public static boolean needsConflictCopy(BoardEntity loser, BoardEntity winner) {
        return needsConflictCopy(loser.inkHash, loser.deletedAt, winner.inkHash, winner.deletedAt);
    }

    public static boolean needsConflictCopy(NotebookLogEntity loser, NotebookLogEntity winner) {
        return needsConflictCopy(loser.inkHash, loser.deletedAt, winner.inkHash, winner.deletedAt);
    }
}
