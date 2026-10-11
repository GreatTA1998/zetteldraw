package com.zetteldraw.penpoc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.media.AudioManager;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.util.concurrent.TimeUnit;

/**
 * Tap feedback must stay off the ink path, cycle four bundled sounds, and
 * not throw when audio is silent or when Robolectric has no speaker.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class, shadows = IdleSurfaceViewShadow.class)
public class UiClickTest {
    private Context context;
    private AudioManager audio;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        UiClick.release();
    }

    @After
    public void tearDown() {
        UiClick.release();
    }

    @Test
    public void playDoesNotThrowWhenSilent() {
        audio.setRingerMode(AudioManager.RINGER_MODE_SILENT);
        UiClick.warm(context);
        UiClick.play(context);
        UiClick.play(null);
    }

    @Test
    public void cycleAdvancesThreeScratchSlots() {
        audio.setRingerMode(AudioManager.RINGER_MODE_NORMAL);
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 7, 0);
        UiClick.warm(context);
        ShadowLooper.idleMainLooper(200, TimeUnit.MILLISECONDS);
        assertEquals(0, UiClick.nextSlotForTest());
        UiClick.play(context);
        assertEquals(1, UiClick.nextSlotForTest());
        UiClick.play(context);
        assertEquals(2, UiClick.nextSlotForTest());
        UiClick.play(context);
        assertEquals(0, UiClick.nextSlotForTest());
    }

    @Test
    public void toolButtonStillClicksWithDownFeedbackWired() {
        var controller = Robolectric.buildActivity(CanvasActivity.class).setup();
        CanvasActivity activity = controller.get();
        View eraserButton = findByDescription(activity.getWindow().getDecorView(),
                activity.getString(R.string.eraser));
        assertNotNull(eraserButton);
        long now = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 10f, 10f, 0);
        MotionEvent up = MotionEvent.obtain(now, now + 20, MotionEvent.ACTION_UP, 10f, 10f, 0);
        assertTrue(eraserButton.dispatchTouchEvent(down));
        assertTrue(eraserButton.dispatchTouchEvent(up));
        down.recycle();
        up.recycle();
        controller.pause().stop().destroy();
    }

    private static View findByDescription(View root, String description) {
        CharSequence own = root.getContentDescription();
        if (own != null && description.contentEquals(own)) {
            return root;
        }
        if (root instanceof android.view.ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findByDescription(group.getChildAt(i), description);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
