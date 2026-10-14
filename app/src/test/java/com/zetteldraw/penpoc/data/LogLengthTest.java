package com.zetteldraw.penpoc.data;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LogLengthTest {
    @Test
    public void remoteShortensOnlyWhenLocalKnownAndLarger() {
        assertTrue(LogLength.remoteShortens(40, 22));
        assertFalse(LogLength.remoteShortens(22, 40));
        assertFalse(LogLength.remoteShortens(22, 22));
        assertFalse(LogLength.remoteShortens(-1, 22));
    }

    @Test
    public void remoteLengthensRecoversTruncate() {
        assertTrue(LogLength.remoteLengthens(22, 40));
        assertFalse(LogLength.remoteLengthens(40, 22));
        assertFalse(LogLength.remoteLengthens(40, 40));
        assertFalse(LogLength.remoteLengthens(-1, 40));
    }

    @Test
    public void incomingBytesShrinkGuardsCloudPrimary() {
        assertTrue(LogLength.incomingBytesShrink(17_000_000, 500_000, true, true, false));
        assertFalse(LogLength.incomingBytesShrink(500_000, 17_000_000, true, true, false));
        assertFalse(LogLength.incomingBytesShrink(17_000_000, 500_000, true, true, true));
        assertFalse(LogLength.incomingBytesShrink(17_000_000, 500_000, false, true, false));
    }
}
