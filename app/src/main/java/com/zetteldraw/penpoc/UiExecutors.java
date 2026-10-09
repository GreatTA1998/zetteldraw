package com.zetteldraw.penpoc;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/** Background threads the UI hands work to: page-list loads, load retries, first-screen renders, the surface. */
final class UiExecutors {
    static volatile Executor loader = single("zd-load");
    /** A retry must not queue behind a load that is stuck, so each may get a fresh thread. */
    static volatile Executor retryLoader = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "zd-load-retry");
        thread.setDaemon(true);
        return thread;
    });
    static volatile Executor renderer = single("zd-render");
    /** Each ink view gets its own {@link SurfaceWorker} thread; false runs surface work inline. */
    static volatile boolean surfaceThreads = true;
    /** Tests: runs every new {@link SurfaceWorker} on this executor instead. */
    static volatile Executor surfaceForTest;

    private UiExecutors() {
    }

    /** Robolectric's looper does not wait for other threads; run inline so tests stay deterministic. */
    static void useSynchronousForTest() {
        loader = Runnable::run;
        retryLoader = Runnable::run;
        renderer = Runnable::run;
        surfaceThreads = false;
    }

    private static Executor single(String name) {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        });
    }
}
