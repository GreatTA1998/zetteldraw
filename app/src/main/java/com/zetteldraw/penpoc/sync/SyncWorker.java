package com.zetteldraw.penpoc.sync;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.zetteldraw.penpoc.data.ZettelData;
import com.zetteldraw.penpoc.data.db.ZettelDatabase;

import java.io.IOException;

/** WorkManager entry point. Network errors retry with WorkManager's backoff. */
public final class SyncWorker extends Worker {
    private static final String TAG = "zd-sync";

    public SyncWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        SyncConfig config = SyncConfig.load(getApplicationContext());
        if (!config.enabled()) {
            return Result.success();
        }
        SyncEngine engine = new SyncEngine(
                ZettelData.repository(getApplicationContext()),
                new SyncClient(config, ZettelDatabase.SCHEMA_VERSION),
                config,
                ZettelDatabase.SCHEMA_VERSION,
                System::currentTimeMillis);
        try {
            SyncEngine.Result r = engine.run();
            Log.i(TAG, "sync ok: pushed " + r.pushed + ", pulled " + r.pulledBoards + " boards");
            return Result.success();
        } catch (SyncClient.SchemaMismatch e) {
            Log.w(TAG, "schema mismatch: app " + ZettelDatabase.SCHEMA_VERSION + ", server " + e.serverVersion);
            return Result.failure();
        } catch (SyncClient.Unauthorized e) {
            Log.w(TAG, "device token rejected");
            return Result.failure();
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "sync failed, will retry", e);
            return Result.retry();
        }
    }
}
