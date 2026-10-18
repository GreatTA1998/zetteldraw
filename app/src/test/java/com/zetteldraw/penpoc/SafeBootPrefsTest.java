package com.zetteldraw.penpoc;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import com.zetteldraw.penpoc.data.ZettelData;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

/**
 * Crash-loop breaker: a stuck {@code boot_attempt} promotes to safe_boot and
 * cold-starts to library shell without ensureSheet(Scratchpad).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class, qualifiers = "w600dp-h1000dp",
        shadows = IdleSurfaceViewShadow.class)
public class SafeBootPrefsTest {
    @Before
    public void fresh() {
        ZettelData.resetForTest();
        UiExecutors.useSynchronousForTest();
        Context app = RuntimeEnvironment.getApplication();
        app.getSharedPreferences("canvas", Context.MODE_PRIVATE).edit().clear().commit();
    }

    @Test
    public void priorBootAttemptPromotesToSafeBootAndSkipsCrashLoop() {
        Context app = RuntimeEnvironment.getApplication();
        SharedPreferences prefs = app.getSharedPreferences("canvas", Context.MODE_PRIVATE);
        prefs.edit().putBoolean("boot_attempt", true).commit();

        ActivityController<CanvasActivity> controller =
                Robolectric.buildActivity(CanvasActivity.class).setup();
        CanvasActivity activity = controller.get();
        ShadowLooper.idleMainLooper();

        assertNotNull(activity);
        prefs = app.getSharedPreferences("canvas", Context.MODE_PRIVATE);
        assertTrue("boot_attempt must promote to safe_boot", prefs.getBoolean("safe_boot", false));
        assertFalse("boot_attempt cleared after promote", prefs.getBoolean("boot_attempt", true));
    }
}
