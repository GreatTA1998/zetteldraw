package com.zetteldraw.penpoc.data;

import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.util.Log;

import com.zetteldraw.penpoc.LaunchLog;
import com.zetteldraw.penpoc.data.db.ZettelDatabase;

import java.io.File;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Process-wide repository. BoardStore JSON stays on disk until the first
 * {@link RoomBoardRepository#ensureSheet} with a real page height — then Ready
 * {@code .zdl} is written directly (no importer {@code .zdi} / BOARD outbox).
 */
public final class ZettelData {
    private static final String TAG = "zd-data";
    private static RoomBoardRepository repository;
    private static DeferredMirror deferredMirror;
    private static Context appContext;
    private static boolean synchronousForTest;

    private ZettelData() {
    }

    public static synchronized RoomBoardRepository repository(Context context) {
        if (repository == null) {
            LaunchLog.mark("storage opening");
            Context app = context.getApplicationContext();
            appContext = app;
            // Cold open: no Documents/MediaProvider. Enable after first paint.
            deferredMirror = new DeferredMirror();
            if (synchronousForTest) {
                deferredMirror.activate(buildDocumentsMirror(app));
            }
            Executor writer = synchronousForTest ? Runnable::run : singleThread("zd-ink-writer");
            Executor mirrorExecutor = synchronousForTest ? Runnable::run : singleThread("zd-mirror");
            repository = new RoomBoardRepository(
                    ZettelDatabase.open(app),
                    new InkFileStore(new File(app.getFilesDir(), "ink")),
                    deferredMirror,
                    writer,
                    mirrorExecutor,
                    app.getMainExecutor(),
                    System::currentTimeMillis);
            repository.setLegacyFilesDir(app.getFilesDir());
            Log.i(TAG, "legacy JSON deferred until pageHeight (Ready-direct import)");
        }
        return repository;
    }

    /**
     * Turn on the Documents mirror after the UI has painted. Safe to call
     * repeatedly; no-ops when already active or when repository is unset.
     */
    public static synchronized void enableDocumentsMirror() {
        if (deferredMirror == null || deferredMirror.isActive() || appContext == null) {
            return;
        }
        try {
            deferredMirror.activate(buildDocumentsMirror(appContext));
            LaunchLog.mark("documents mirror enabled");
        } catch (Throwable t) {
            Log.w(TAG, "documents mirror activate failed; staying no-op", t);
            LaunchLog.mark("documents mirror skipped: " + t);
        }
    }

    private static InkMirror buildDocumentsMirror(Context app) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return new DocumentsMirror(app.getContentResolver());
        }
        return new DirectoryMirror(new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                "zetteldraw"));
    }

    private static ExecutorService singleThread(String name) {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Tests only: the next {@link #repository} call opens a fresh one for the
     * current app, writing synchronously so UI tests stay deterministic.
     * Write-behind itself is covered by repository tests with real threads.
     */
    public static synchronized void resetForTest() {
        repository = null;
        deferredMirror = null;
        appContext = null;
        synchronousForTest = true;
    }
}
