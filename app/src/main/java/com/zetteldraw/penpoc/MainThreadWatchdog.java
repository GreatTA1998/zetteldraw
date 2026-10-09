package com.zetteldraw.penpoc;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Debug builds: logs the main thread's stack (tag {@code zd-stall}) whenever
 * it fails to run a posted no-op for {@link #STALL_MS}, and how long the stall
 * lasted, so a device log shows what the UI was blocked on. Also logs any
 * {@link SurfaceWorker} stuck in one Onyx or surface call for as long.
 */
final class MainThreadWatchdog {
    private static final String TAG = "zd-stall";
    private static final long STALL_MS = 700;
    private static final long POLL_MS = 250;
    private static final Map<SurfaceWorker, Long> workers = new WeakHashMap<>();

    private MainThreadWatchdog() {
    }

    static void watch(SurfaceWorker worker) {
        synchronized (workers) {
            workers.put(worker, 0L);
        }
    }

    static void unwatch(SurfaceWorker worker) {
        synchronized (workers) {
            workers.remove(worker);
        }
    }

    static void start() {
        Handler main = new Handler(Looper.getMainLooper());
        Thread thread = new Thread(() -> {
            AtomicLong answered = new AtomicLong();
            long seq = 0;
            while (true) {
                long sent = ++seq;
                long postedAt = SystemClock.uptimeMillis();
                main.post(() -> answered.set(sent));
                sleep(STALL_MS);
                if (answered.get() >= sent) {
                    sleep(POLL_MS);
                    continue;
                }
                Log.w(TAG, "main thread blocked for " + STALL_MS + " ms:"
                        + trace(Looper.getMainLooper().getThread()));
                while (answered.get() < sent) {
                    sleep(POLL_MS);
                }
                long stalled = SystemClock.uptimeMillis() - postedAt;
                Log.w(TAG, "main thread stall ended after " + stalled + " ms");
                LaunchLog.mark("main thread was blocked for " + stalled + " ms");
            }
        }, "zd-watchdog");
        thread.setDaemon(true);
        thread.start();

        Thread surfaces = new Thread(() -> {
            while (true) {
                sleep(POLL_MS);
                long now = SystemClock.uptimeMillis();
                synchronized (workers) {
                    for (Map.Entry<SurfaceWorker, Long> e : workers.entrySet()) {
                        SurfaceWorker worker = e.getKey();
                        long since = worker.busySince();
                        if (since == 0 || now - since < STALL_MS || e.getValue() == since) {
                            continue;
                        }
                        e.setValue(since);
                        Thread t = worker.thread();
                        Log.w(TAG, "surface worker stuck in " + worker.busyOp() + " for " + (now - since) + " ms"
                                + (t == null ? "" : ":" + trace(t)));
                    }
                }
            }
        }, "zd-watchdog-surface");
        surfaces.setDaemon(true);
        surfaces.start();
    }

    private static String trace(Thread thread) {
        StringBuilder trace = new StringBuilder();
        for (StackTraceElement frame : thread.getStackTrace()) {
            trace.append("\n    at ").append(frame);
        }
        return trace.toString();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
