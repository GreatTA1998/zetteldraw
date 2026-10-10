package com.zetteldraw.penpoc;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.media.AudioManager;
import android.provider.Settings;
import android.view.View;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Tap feedback must stay off the ink path and must not throw when audio is
 * silent or when Robolectric has no speaker.
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
    public void playDoesNotThrowWhenTouchSoundsOn() {
        audio.setRingerMode(AudioManager.RINGER_MODE_NORMAL);
        Settings.System.putInt(
                context.getContentResolver(),
                Settings.System.SOUND_EFFECTS_ENABLED,
                1);
        UiClick.play(context);
    }

    @Test
    public void playDoesNotThrowWhenTouchSoundsOff() {
        audio.setRingerMode(AudioManager.RINGER_MODE_NORMAL);
        Settings.System.putInt(
                context.getContentResolver(),
                Settings.System.SOUND_EFFECTS_ENABLED,
                0);
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 7, 0);
        UiClick.warm(context);
        UiClick.play(context);
    }

    @Test
    public void toolButtonStillClicksWithFeedbackWired() {
        var controller = Robolectric.buildActivity(CanvasActivity.class).setup();
        CanvasActivity activity = controller.get();
        View eraserButton = findByDescription(activity.getWindow().getDecorView(),
                activity.getString(R.string.eraser));
        assertNotNull(eraserButton);
        assertTrue(eraserButton.performClick());
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
