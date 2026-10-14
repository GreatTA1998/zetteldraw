package com.zetteldraw.penpoc.data;

/**
 * Sole policy for applying a remote notebook ink log to local disk/Ready.
 *
 * <p><strong>Live writer:</strong> push-only for ink. Never adopt remote over
 * local (except equal/idempotent skips). Cloud is backup only while leased —
 * no silent heal racing the live SoT. {@code allowAdoptRemote=false} forces
 * {@link Decision#KEEP_LIVE_LOCAL} when remote would replace the primary body.
 *
 * <p><strong>Catch-up</strong> (non-writer / first restore / Hand-off seed):
 * {@code allowAdoptRemote=true} — longer remote may {@link Decision#ADOPT_LONGER}.
 *
 * <p>There is never {@code OVERWRITE_WITH_SHORTER}. Local loss of a longer disk
 * is structurally impossible from this type.
 *
 * @see LogLength
 * @see docs/system-architecture.md
 */
public final class InkLogApply {
    private InkLogApply() {
    }

    public enum Decision {
        /** Remote is a conflict_of shelf copy — write under remote id only. */
        SHELVE_CONFLICT_COPY,
        /** Remote has fewer slices/bytes — keep local primary; shelf remote. */
        REFUSE_SHORTER,
        /**
         * Live writer: remote would replace local body — keep local SoT.
         * Push will upload local; cloud is not authority while leased.
         */
        KEEP_LIVE_LOCAL,
        /** Catch-up: longer cloud backup — install remote; drop pending outbox. */
        ADOPT_LONGER,
        /** Equal length: local rev already caught up. */
        SKIP_REV,
        /** Equal length: in-flight Move holds flushes. */
        SKIP_HOLD_FLUSH,
        /** Equal length: memory longer than flushed disk. */
        SKIP_UNFLUSHED,
        /** Equal length: pending local outbox still newer than remote. */
        SKIP_PENDING_NEWER,
        /** Catch-up equal length: adopt remote body (Hand-off seed / restore). */
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
        /** False while this device is the live writer (push-only ink). */
        public final boolean allowAdoptRemote;
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
                boolean allowAdoptRemote,
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
            this.allowAdoptRemote = allowAdoptRemote;
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
                // Empty local is seed/fill, not clobber. Live push-only blocks
                // only when local already has ink.
                if (!f.allowAdoptRemote && hasLocalInk(f)) {
                    return Decision.KEEP_LIVE_LOCAL;
                }
                return Decision.ADOPT_LONGER;
            }
            return equalLengthDecision(f);
        }
        if (f.localRev != null) {
            Decision skip = equalLengthDecision(f);
            if (skip != Decision.LWW_REPLACE) {
                return skip;
            }
            if (!f.allowAdoptRemote) {
                return Decision.KEEP_LIVE_LOCAL;
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
            if (!f.allowAdoptRemote && hasLocalInk(f)) {
                return Decision.KEEP_LIVE_LOCAL;
            }
            return Decision.LWW_REPLACE;
        }
        return Decision.UPSERT_META_ONLY;
    }

    private static boolean hasLocalInk(Facts f) {
        return f.localBytes > 0 || f.localSlices > 0;
    }

    /** True when this decision may replace the live primary log bytes. */
    public static boolean writesPrimaryBody(Decision d) {
        return d == Decision.ADOPT_LONGER || d == Decision.LWW_REPLACE;
    }

    /** True when the live primary must not shrink. */
    public static boolean protectsPrimaryLength(Decision d) {
        return d == Decision.REFUSE_SHORTER || d == Decision.KEEP_LIVE_LOCAL;
    }
}
