package com.zetteldraw.penpoc;

import android.app.Application;
import android.content.Context;
import android.os.Build;

import org.lsposed.hiddenapibypass.HiddenApiBypass;

/**
 * Onyx TouchHelper talks to e-ink via hidden platform APIs on Android 11+.
 * The Go 7 Color II is Android 13, so this exemption must run before SDK use.
 */
public final class PenApp extends Application {
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            HiddenApiBypass.addHiddenApiExemptions("");
        }
    }
}
