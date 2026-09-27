package com.zetteldraw.penpoc.data;

import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.util.Log;

import com.zetteldraw.penpoc.data.db.ZettelDatabase;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.Executors;

/** Process-wide repository. First call migrates BoardStore JSON into Room. */
public final class ZettelData {
    private static final String TAG = "zd-data";
    private static RoomBoardRepository repository;

    private ZettelData() {
    }

    public static synchronized RoomBoardRepository repository(Context context) {
        if (repository == null) {
            Context app = context.getApplicationContext();
            InkMirror mirror;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                mirror = new DocumentsMirror(app.getContentResolver());
            } else {
                mirror = new DirectoryMirror(new File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                        "zetteldraw"));
            }
            repository = new RoomBoardRepository(
                    ZettelDatabase.open(app),
                    new InkFileStore(new File(app.getFilesDir(), "ink")),
                    mirror,
                    Executors.newSingleThreadExecutor(),
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
}
