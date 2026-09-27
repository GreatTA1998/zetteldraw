package com.zetteldraw.penpoc.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

public class PositionsTest {
    @Test
    public void matchesReferenceVectors() {
        assertEquals("a0", Positions.between(null, null));
        assertEquals("a1", Positions.between("a0", null));
        assertEquals("Zz", Positions.between(null, "a0"));
        assertEquals("a0V", Positions.between("a0", "a1"));
        assertEquals("a1V", Positions.between("a1", "a2"));
        assertEquals("a0l", Positions.between("a0V", "a1"));
        assertEquals("ZzV", Positions.between("Zz", "a0"));
        assertEquals("a0", Positions.between("Zz", "a1"));
        assertEquals("Xzzz", Positions.between(null, "Y00"));
        assertEquals("c000", Positions.between("bzz", null));
        assertEquals("a0G", Positions.between("a0", "a0V"));
        assertEquals("a08", Positions.between("a0", "a0G"));
        assertEquals("b127", Positions.between("b125", "b129"));
        assertEquals("a1", Positions.between("a0", "a1V"));
        assertEquals("a0", Positions.between("Zz", "a01"));
        assertEquals("a0", Positions.between(null, "a0V"));
        assertEquals("b99", Positions.between(null, "b999"));
    }

    @Test
    public void appendingNewestAlwaysSortsLast() {
        String last = null;
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < 5000; i++) {
            String next = Positions.after(last);
            if (last != null) {
                assertTrue(last + " < " + next, last.compareTo(next) < 0);
            }
            keys.add(next);
            last = next;
        }
        List<String> sorted = new ArrayList<>(keys);
        Collections.sort(sorted);
        assertEquals(keys, sorted);
        assertTrue("keys stay short", last.length() <= 4);
    }

    @Test
    public void randomInsertsKeepInsertionOrder() {
        Random random = new Random(7);
        List<String> ordered = new ArrayList<>();
        ordered.add(Positions.between(null, null));
        for (int i = 0; i < 2000; i++) {
            int at = random.nextInt(ordered.size() + 1);
            String before = at == 0 ? null : ordered.get(at - 1);
            String after = at == ordered.size() ? null : ordered.get(at);
            String key = Positions.between(before, after);
            if (before != null) {
                assertTrue(before.compareTo(key) < 0);
            }
            if (after != null) {
                assertTrue(key.compareTo(after) < 0);
            }
            ordered.add(at, key);
        }
        List<String> sorted = new ArrayList<>(ordered);
        Collections.sort(sorted);
        assertEquals(ordered, sorted);
    }

    @Test
    public void rejectsBadInput() {
        assertThrows(IllegalArgumentException.class, () -> Positions.between("a1", "a0"));
        assertThrows(IllegalArgumentException.class, () -> Positions.between("a0", "a0"));
        assertThrows(IllegalArgumentException.class, () -> Positions.between("a00", null));
        assertThrows(IllegalArgumentException.class, () -> Positions.between("", null));
    }
}
