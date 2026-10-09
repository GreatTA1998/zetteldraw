package com.zetteldraw.penpoc.sync;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.zetteldraw.penpoc.BuildConfig;
import com.zetteldraw.penpoc.LaunchLog;
import com.zetteldraw.penpoc.data.ZettelData;
import com.zetteldraw.penpoc.data.db.ZettelDatabase;

import org.json.JSONException;
import org.json.JSONObject;

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
        LaunchLog.mark("sync started (attempt " + (getRunAttemptCount() + 1) + ")");
        long started = SystemClock.uptimeMillis();
        SyncClient client = new SyncClient(config, ZettelDatabase.SCHEMA_VERSION);
        SyncEngine engine = new SyncEngine(
                ZettelData.repository(getApplicationContext()),
                client,
                config,
                ZettelDatabase.SCHEMA_VERSION,
                System::currentTimeMillis);
        try {
            SyncEngine.Result r = engine.run();
            Log.i(TAG, "sync ok: pushed " + r.pushed + ", pulled " + r.pulledBoards + " boards");
            LaunchLog.mark("sync ok in " + (SystemClock.uptimeMillis() - started) + " ms: pushed " + r.pushed
                    + ", pulled " + r.pulledBoards + " pages and " + r.pulledNotebooks + " notebooks");
            uploadLaunchLog(client, config);
            return Result.success();
        } catch (SyncClient.SchemaMismatch e) {
            Log.w(TAG, "schema mismatch: app " + ZettelDatabase.SCHEMA_VERSION + ", server " + e.serverVersion);
            LaunchLog.mark("sync stopped: schema mismatch, server " + e.serverVersion);
            return Result.failure();
        } catch (SyncClient.Unauthorized e) {
            Log.w(TAG, "device token rejected");
            LaunchLog.mark("sync stopped: device token rejected");
            return Result.failure();
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "sync failed, will retry", e);
            LaunchLog.mark("sync failed after " + (SystemClock.uptimeMillis() - started) + " ms, will retry: " + e);
            return Result.retry();
        }
    }

    /** Best effort: a server without the endpoint, or any failure, only costs a log line. */
    private static void uploadLaunchLog(SyncClient client, SyncConfig config) {
        if (!LaunchLog.hasUnsent()) {
            return;
        }
        int launch = LaunchLog.launchNumber();
        int lines = LaunchLog.lineCount();
        try {
            client.uploadDeviceLog(new JSONObject()
                    .put("device_id", config.deviceId)
                    .put("app_version", BuildConfig.VERSION_CODE)
                    .put("launch", launch)
                    .put("text", LaunchLog.all()));
            LaunchLog.markSent(launch, lines);
        } catch (IOException | JSONException | RuntimeException e) {
            Log.w(TAG, "launch log upload failed", e);
            LaunchLog.mark("launch log upload failed: " + e.getMessage());
        }
    }
}
