package com.zetteldraw.penpoc.sync;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;

import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

/** Every 15 minutes on any network, plus once each time the app goes to the background. */
public final class SyncScheduler {
    private static final String PERIODIC = "zetteldraw-sync-periodic";
    private static final String ON_BACKGROUND = "zetteldraw-sync-now";

    private SyncScheduler() {
    }

    public static void install(Application app) {
        schedule(app);
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            private int started;

            @Override
            public void onActivityStarted(Activity activity) {
                started++;
            }

            @Override
            public void onActivityStopped(Activity activity) {
                started--;
                if (started == 0 && !activity.isChangingConfigurations()) {
                    syncNow(app);
                }
            }

            @Override
            public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
            }

            @Override
            public void onActivityResumed(Activity activity) {
            }

            @Override
            public void onActivityPaused(Activity activity) {
            }

            @Override
            public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
            }

            @Override
            public void onActivityDestroyed(Activity activity) {
            }
        });
    }

    public static void schedule(Context context) {
        WorkManager work = WorkManager.getInstance(context.getApplicationContext());
        if (!SyncConfig.load(context).enabled()) {
            work.cancelUniqueWork(PERIODIC);
            return;
        }
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(SyncWorker.class, 15, TimeUnit.MINUTES)
                .setConstraints(networkConstraint())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build();
        work.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request);
    }

    public static void syncNow(Context context) {
        if (!SyncConfig.load(context).enabled()) {
            return;
        }
        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(SyncWorker.class)
                .setConstraints(networkConstraint())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build();
        WorkManager.getInstance(context.getApplicationContext())
                .enqueueUniqueWork(ON_BACKGROUND, ExistingWorkPolicy.APPEND_OR_REPLACE, request);
    }

    private static Constraints networkConstraint() {
        return new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
    }
}
