package com.zetteldraw.penpoc.data;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A disk that stalls: while held, every ink save staged by the writer thread
 * blocks until {@link #release()} (or a safety timeout, so a regression
 * fails instead of hanging the build). Pull staging is not slowed.
 */
public final class SlowInkFileStore extends InkFileStore {
    public static final long SAFETY_TIMEOUT_MS = 5_000;

    private volatile CountDownLatch open = new CountDownLatch(1);
    private final Semaphore stalled = new Semaphore(0);
    public final AtomicInteger stages = new AtomicInteger();

    public SlowInkFileStore(File dir) {
        super(dir);
    }

    @Override
    public File stage(String boardId, String tag, byte[] bytes) throws IOException {
        if ("zdi".equals(tag)) {
            stages.incrementAndGet();
            CountDownLatch gate = open;
            if (gate.getCount() > 0) {
                stalled.release();
            }
            try {
                gate.await(SAFETY_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return super.stage(boardId, tag, bytes);
    }

    public void hold() {
        stalled.drainPermits();
        open = new CountDownLatch(1);
    }

    public void release() {
        open.countDown();
    }

    /** Waits until a write is stuck on the disk. */
    public void awaitStall() throws InterruptedException {
        if (!stalled.tryAcquire(SAFETY_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            throw new AssertionError("no ink write started");
        }
    }
}
