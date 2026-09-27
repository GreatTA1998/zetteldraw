package com.zetteldraw.penpoc;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Debug builds: logs the main thread's stack (tag {@code zd-stall}) whenever
 * it fails to run a posted no-op for {@link #STALL_MS}, and how long the stall
 * lasted, so a device log shows what the UI was blocked on.
 */
final class MainThreadWatchdog {
    private static final String TAG = "zd-stall";
    private static final long STALL_MS = 700;
    private static final long POLL_MS = 250;

    private MainThreadWatchdog() {
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
                StringBuilder trace = new StringBuilder();
                for (StackTraceElement frame : Looper.getMainLooper().getThread().getStackTrace()) {
                    trace.append("\n    at ").append(frame);
                }
                Log.w(TAG, "main thread blocked for " + STALL_MS + " ms:" + trace);
                while (answered.get() < sent) {
                    sleep(POLL_MS);
                }
                Log.w(TAG, "main thread stall ended after " + (SystemClock.uptimeMillis() - postedAt) + " ms");
            }
        }, "zd-watchdog");
        thread.setDaemon(true);
        thread.start();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
