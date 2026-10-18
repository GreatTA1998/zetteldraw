package com.zetteldraw.penpoc.data;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;

/** Cold open must not touch Documents until activate. */
public class DeferredMirrorTest {
    @Test
    public void startsAsNoOpThenForwardsAfterActivate() {
        DeferredMirror deferred = new DeferredMirror();
        AtomicBoolean wrote = new AtomicBoolean(false);
        assertFalse(deferred.isActive());
        deferred.writeIndex("{}");
        deferred.writeLog("scratchpad", new byte[]{1});
        assertFalse(wrote.get());

        deferred.activate(new InkMirror() {
            @Override
            public void writeIndex(String json) {
                wrote.set(true);
            }
        });
        assertTrue(deferred.isActive());
        deferred.writeIndex("{}");
        assertTrue(wrote.get());
    }
}
