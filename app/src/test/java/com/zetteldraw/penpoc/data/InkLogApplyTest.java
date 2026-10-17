package com.zetteldraw.penpoc.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class InkLogApplyTest {
    @Test
    public void refuseShorterNeverWritesPrimary() {
        InkLogApply.Decision d = InkLogApply.decide(body(
                true,
                40, 22,
                17_000_000L, 500_000L,
                10L, 20L,
                false, false, false, 100L, 200L));
        assertEquals(InkLogApply.Decision.REFUSE_SHORTER, d);
        assertFalse(InkLogApply.writesPrimaryBody(d));
        assertTrue(InkLogApply.protectsPrimaryLength(d));
    }

    @Test
    public void catchUpAdoptsLongerBeatsNewerPendingShortClock() {
        InkLogApply.Decision d = InkLogApply.decide(body(
                true,
                22, 38,
                500_000L, 17_000_000L,
                10L, 9L,
                false, false, true, 999L, 100L));
        assertEquals(InkLogApply.Decision.ADOPT_LONGER, d);
        assertTrue(InkLogApply.writesPrimaryBody(d));
    }

    @Test
    public void liveWriterKeepsLocalWhenRemoteLonger() {
        InkLogApply.Decision d = InkLogApply.decide(body(
                false,
                22, 38,
                500_000L, 17_000_000L,
                10L, 9L,
                false, false, true, 999L, 100L));
        assertEquals(InkLogApply.Decision.KEEP_LIVE_LOCAL, d);
        assertFalse(InkLogApply.writesPrimaryBody(d));
        assertTrue(InkLogApply.protectsPrimaryLength(d));
    }

    @Test
    public void liveWriterStillFillsEmptyLocalFromRemote() {
        InkLogApply.Decision d = InkLogApply.decide(body(
                false,
                -1, 38,
                0L, 17_000_000L,
                null, 9L,
                false, false, false, 0L, 100L));
        assertEquals(InkLogApply.Decision.ADOPT_LONGER, d);
        assertTrue(InkLogApply.writesPrimaryBody(d));
    }

    @Test
    public void catchUpAdoptsLongerByBytesWhenSlicesEqual() {
        InkLogApply.Decision d = InkLogApply.decide(body(
                true,
                22, 22,
                500_000L, 17_000_000L,
                10L, 10L,
                false, false, false, 100L, 100L));
        assertEquals(InkLogApply.Decision.ADOPT_LONGER, d);
    }

    @Test
    public void holdFlushBlocksEqualLengthReplaceOnly() {
        InkLogApply.Decision equal = InkLogApply.decide(body(
                true,
                38, 38,
                17_000_000L, 17_000_000L,
                10L, 20L,
                true, false, false, 100L, 200L));
        assertEquals(InkLogApply.Decision.SKIP_HOLD_FLUSH, equal);

        InkLogApply.Decision longer = InkLogApply.decide(body(
                true,
                22, 38,
                500_000L, 17_000_000L,
                10L, 20L,
                true, false, false, 100L, 200L));
        assertEquals(InkLogApply.Decision.ADOPT_LONGER, longer);
    }

    @Test
    public void liveEqualLengthKeepsLocalNotReplace() {
        InkLogApply.Decision d = InkLogApply.decide(body(
                false,
                38, 38,
                17_000_000L, 17_000_000L,
                10L, 20L,
                false, false, false, 100L, 200L));
        assertEquals(InkLogApply.Decision.KEEP_LIVE_LOCAL, d);
        assertFalse(InkLogApply.writesPrimaryBody(d));
    }

    @Test
    public void noDecisionWritesPrimaryWhileProtectingLength() {
        for (InkLogApply.Decision d : InkLogApply.Decision.values()) {
            if (InkLogApply.protectsPrimaryLength(d)) {
                assertFalse(InkLogApply.writesPrimaryBody(d));
            }
        }
    }

    @Test
    public void shelvesLosingLocalOnlyWhenPendingReplaceDiffers() {
        assertTrue(InkLogApply.shelvesLosingLocal(
                InkLogApply.Decision.LWW_REPLACE, true, "aaa", null, "bbb", null));
        assertTrue(InkLogApply.shelvesLosingLocal(
                InkLogApply.Decision.ADOPT_LONGER, true, "aaa", null, "bbb", null));
        assertFalse("no pending outbox — not a Hand-off race shelf",
                InkLogApply.shelvesLosingLocal(
                        InkLogApply.Decision.LWW_REPLACE, false, "aaa", null, "bbb", null));
        assertFalse("same ink — idempotent",
                InkLogApply.shelvesLosingLocal(
                        InkLogApply.Decision.LWW_REPLACE, true, "aaa", null, "aaa", null));
        assertFalse("live keep does not replace primary",
                InkLogApply.shelvesLosingLocal(
                        InkLogApply.Decision.KEEP_LIVE_LOCAL, true, "aaa", null, "bbb", null));
    }

    @Test
    public void logLengthPredicatesRemainMonotonic() {
        assertTrue(LogLength.remoteShortens(40, 22));
        assertFalse(LogLength.remoteShortens(22, 40));
        assertTrue(LogLength.remoteLengthens(22, 40));
        assertFalse(LogLength.remoteLengthens(40, 40));
        assertTrue(LogLength.incomingBytesShrink(17_000_000, 500_000, true, true, false));
        assertFalse(LogLength.incomingBytesShrink(17_000_000, 500_000, true, true, true));
    }

    private static InkLogApply.Facts body(
            boolean allowAdopt,
            int localSlices, int remoteSlices,
            long localBytes, long remoteBytes,
            Long localRev, long remoteRev,
            boolean holdFlush, boolean unflushed, boolean pending,
            long localUpdatedAt, long remoteUpdatedAt) {
        return new InkLogApply.Facts(
                false, false, true, allowAdopt,
                localSlices, remoteSlices, localBytes, remoteBytes,
                localRev, remoteRev,
                holdFlush, unflushed, pending,
                localUpdatedAt, remoteUpdatedAt);
    }
}
