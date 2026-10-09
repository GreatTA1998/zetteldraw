package com.zetteldraw.penpoc.data;

import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.util.Log;

import com.zetteldraw.penpoc.LaunchLog;
import com.zetteldraw.penpoc.data.db.ZettelDatabase;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Process-wide repository. First call migrates BoardStore JSON into Room. */
public final class ZettelData {
    private static final String TAG = "zd-data";
    private static RoomBoardRepository repository;
    private static boolean synchronousForTest;

    private ZettelData() {
    }

    public static synchronized RoomBoardRepository repository(Context context) {
        if (repository == null) {
            LaunchLog.mark("storage opening");
            Context app = context.getApplicationContext();
            InkMirror mirror;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                mirror = new DocumentsMirror(app.getContentResolver());
            } else {
                mirror = new DirectoryMirror(new File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                        "zetteldraw"));
            }
            Executor writer = synchronousForTest ? Runnable::run : singleThread("zd-ink-writer");
            Executor mirrorExecutor = synchronousForTest ? Runnable::run : singleThread("zd-mirror");
            repository = new RoomBoardRepository(
                    ZettelDatabase.open(app),
                    new InkFileStore(new File(app.getFilesDir(), "ink")),
                    mirror,
                    writer,
                    mirrorExecutor,
                    app.getMainExecutor(),
                    System::currentTimeMillis);
            try {
                int imported = LegacyBoardImporter.importIfPresent(app.getFilesDir(), repository);
                if (imported >= 0) {
                    Log.i(TAG, "imported " + imported + " boards from " + LegacyBoardImporter.FILE_NAME);
                }
            } catch (IOException e) {
                Log.e(TAG, "legacy import failed; will retry next launch", e);
            }
        }
        return repository;
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
        synchronousForTest = true;
    }
}
