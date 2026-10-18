package com.zetteldraw.penpoc.data;

/**
 * Single policy for notebook ink-log length.
 *
 * <p>Source of truth for how many slices a notebook has is the on-disk {@code .zdl}
 * (replayed {@link NotebookPaper#sliceCount()}), not Room board rows, not scroll
 * prefs, and not a "trusted" UI snapshot. Sync may lengthen a log; it may never
 * shorten one except via an explicit local tear/delete that rewrote the file.
 *
 * <p>UI pages are Ready slices only ({@code pages = sliceCount}); soft tails
 * must not feed this policy.
 */
public final class LogLength {
    private LogLength() {
    }

    /** Remote has fewer slices than local Ready/disk — refuse overwrite. */
    public static boolean remoteShortens(int localSlices, int remoteSlices) {
        return localSlices >= 0 && remoteSlices >= 0 && remoteSlices < localSlices;
    }

    /**
     * Remote has more slices than local — always install. Recovers a device whose
     * {@code .zdl} was truncated while the server (or a longer peer) still holds
     * the full log. Beats LWW timestamps and pending short outbox rows.
     */
    public static boolean remoteLengthens(int localSlices, int remoteSlices) {
        return localSlices >= 0 && remoteSlices > localSlices;
    }

    /**
     * Server-side / meta proxy: a live incoming row with fewer ink bytes than the
     * live existing primary must not replace it (slice parse may be unavailable).
     */
    public static boolean incomingBytesShrink(int existingBytes, int incomingBytes,
                                              boolean existingLive, boolean incomingLive,
                                              boolean sameHash) {
        if (!existingLive || !incomingLive || sameHash) {
            return false;
        }
        return existingBytes > 0 && incomingBytes >= 0 && incomingBytes < existingBytes;
    }
}
