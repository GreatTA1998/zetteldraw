package com.zetteldraw.penpoc.data;

/**
 * Sole policy for applying a remote notebook ink log to local disk/Ready.
 *
 * <p>Callers must not write the live primary {@code .zdl} from sync except by
 * honouring {@link Decision}. There is no {@code OVERWRITE_WITH_SHORTER}: a
 * shorter remote is shelved; a longer remote always adopts (beats LWW clocks and
 * pending short outbox). Equal length keeps holdFlush / unflushed / LWW guards.
 *
 * <p>Makes "short remote overwrites long local" locally impossible: the type
 * does not offer that outcome.
 *
 * @see LogLength
 */
public final class InkLogApply {
    private InkLogApply() {
    }

    public enum Decision {
        /** Remote is a conflict_of shelf copy — write under remote id only. */
        SHELVE_CONFLICT_COPY,
        /** Remote has fewer slices/bytes — keep local primary; shelf remote. */
        REFUSE_SHORTER,
        /** Remote has more slices or bytes — install remote; drop pending outbox. */
        ADOPT_LONGER,
        /** Equal length: local rev already caught up. */
        SKIP_REV,
        /** Equal length: in-flight Move holds flushes. */
        SKIP_HOLD_FLUSH,
        /** Equal length: memory longer than flushed disk. */
        SKIP_UNFLUSHED,
        /** Equal length: pending local outbox wins on updatedAt. */
        SKIP_PENDING_NEWER,
        /** Equal length: LWW replace with remote. */
        LWW_REPLACE,
        /** Tombstone / meta-only remote after guards. */
        UPSERT_META_ONLY,
    }

    /**
     * Inputs measured by the repository (Ready paper or disk replay). Negative
     * slice counts mean "unknown / missing local log".
     */
    public static final class Facts {
        public final boolean remoteIsConflictCopy;
        public final boolean remoteDeleted;
        public final boolean hasRemoteBody;
        public final int localSlices;
        public final int remoteSlices;
        public final long localBytes;
        public final long remoteBytes;
        public final Long localRev;
        public final long remoteRev;
        public final boolean holdFlush;
        public final boolean unflushedLocal;
        public final boolean pendingLocal;
        public final long localUpdatedAt;
        public final long remoteUpdatedAt;

        public Facts(
                boolean remoteIsConflictCopy,
                boolean remoteDeleted,
                boolean hasRemoteBody,
                int localSlices,
                int remoteSlices,
                long localBytes,
                long remoteBytes,
                Long localRev,
                long remoteRev,
                boolean holdFlush,
                boolean unflushedLocal,
                boolean pendingLocal,
                long localUpdatedAt,
                long remoteUpdatedAt) {
            this.remoteIsConflictCopy = remoteIsConflictCopy;
            this.remoteDeleted = remoteDeleted;
            this.hasRemoteBody = hasRemoteBody;
            this.localSlices = localSlices;
            this.remoteSlices = remoteSlices;
            this.localBytes = localBytes;
            this.remoteBytes = remoteBytes;
            this.localRev = localRev;
            this.remoteRev = remoteRev;
            this.holdFlush = holdFlush;
            this.unflushedLocal = unflushedLocal;
            this.pendingLocal = pendingLocal;
            this.localUpdatedAt = localUpdatedAt;
            this.remoteUpdatedAt = remoteUpdatedAt;
        }
    }

    public static Decision decide(Facts f) {
        if (f.remoteIsConflictCopy) {
            return Decision.SHELVE_CONFLICT_COPY;
        }
        if (f.hasRemoteBody && !f.remoteDeleted) {
            boolean remoteHasMoreBytes = f.remoteBytes > f.localBytes;
            if (LogLength.remoteShortens(f.localSlices, f.remoteSlices) && !remoteHasMoreBytes) {
                return Decision.REFUSE_SHORTER;
            }
            if (LogLength.remoteLengthens(f.localSlices, f.remoteSlices) || remoteHasMoreBytes) {
                return Decision.ADOPT_LONGER;
            }
            return equalLengthDecision(f);
        }
        if (f.localRev != null) {
            Decision skip = equalLengthDecision(f);
            if (skip != Decision.LWW_REPLACE) {
                return skip;
            }
        }
        return Decision.UPSERT_META_ONLY;
    }

    private static Decision equalLengthDecision(Facts f) {
        if (f.localRev != null && f.remoteRev <= f.localRev) {
            return Decision.SKIP_REV;
        }
        if (f.holdFlush) {
            return Decision.SKIP_HOLD_FLUSH;
        }
        if (f.unflushedLocal) {
            return Decision.SKIP_UNFLUSHED;
        }
        if (f.pendingLocal && !Lww.remoteWins(f.localUpdatedAt, f.remoteUpdatedAt)) {
            return Decision.SKIP_PENDING_NEWER;
        }
        if (f.hasRemoteBody && !f.remoteDeleted) {
            return Decision.LWW_REPLACE;
        }
        return Decision.UPSERT_META_ONLY;
    }

    /** True when this decision may replace the live primary log bytes. */
    public static boolean writesPrimaryBody(Decision d) {
        return d == Decision.ADOPT_LONGER || d == Decision.LWW_REPLACE;
    }

    /** True when the live primary must not shrink. */
    public static boolean protectsPrimaryLength(Decision d) {
        return d == Decision.REFUSE_SHORTER;
    }
}
