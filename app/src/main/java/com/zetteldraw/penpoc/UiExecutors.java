package com.zetteldraw.penpoc;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/** Background threads the UI hands work to: page-list loads and first-screen page renders. */
final class UiExecutors {
    static volatile Executor loader = single("zd-load");
    static volatile Executor renderer = single("zd-render");

    private UiExecutors() {
    }

    /** Robolectric's looper does not wait for other threads; run inline so tests stay deterministic. */
    static void useSynchronousForTest() {
        loader = Runnable::run;
        renderer = Runnable::run;
    }

    private static Executor single(String name) {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        });
    }
}
