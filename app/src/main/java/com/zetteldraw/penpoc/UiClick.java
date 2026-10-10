package com.zetteldraw.penpoc;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.SoundPool;
import android.provider.Settings;
import android.util.Log;

/**
 * One soft click confirming a discrete UI tap (tools, tabs, menus, Confirm /
 * Cancel, page chrome). Not used for stylus ink samples or finger scroll.
 *
 * Prefers {@link AudioManager#FX_KEY_CLICK} when system touch sounds are on
 * (respects mute / that setting). Boox often leaves touch sounds off, so a
 * tiny bundled tick plays at low volume via {@link SoundPool} instead —
 * still skipped in silent mode or when media volume is zero. Never blocks
 * the main thread on decode after {@link #warm(Context)}.
 */
public final class UiClick {
    private static final String TAG = "zd-click";
    private static final float FALLBACK_VOLUME = 0.28f;

    private static SoundPool pool;
    private static int soundId;
    private static volatile boolean loaded;
    private static boolean loading;

    private UiClick() {}

    /** Prefetch the fallback tick so the first tap is not a silent load. */
    public static void warm(Context context) {
        if (context == null) {
            return;
        }
        ensurePool(context.getApplicationContext());
    }

    public static void play(Context context) {
        if (context == null) {
            return;
        }
        Context app = context.getApplicationContext();
        AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) {
            return;
        }
        try {
            if (am.getRingerMode() == AudioManager.RINGER_MODE_SILENT) {
                return;
            }
        } catch (RuntimeException ignored) {
            return;
        }

        boolean touchSounds = true;
        try {
            touchSounds = Settings.System.getInt(
                    app.getContentResolver(),
                    Settings.System.SOUND_EFFECTS_ENABLED,
                    1) != 0;
        } catch (RuntimeException ignored) {
            // Keep the system-click path when settings are unavailable.
        }

        if (touchSounds) {
            try {
                am.playSoundEffect(AudioManager.FX_KEY_CLICK);
            } catch (RuntimeException e) {
                Log.w(TAG, "system click", e);
            }
            return;
        }

        try {
            if (am.getStreamVolume(AudioManager.STREAM_MUSIC) <= 0) {
                return;
            }
        } catch (RuntimeException ignored) {
            return;
        }
        ensurePool(app);
        if (loaded && pool != null && soundId != 0) {
            try {
                pool.play(soundId, FALLBACK_VOLUME, FALLBACK_VOLUME, 1, 0, 1f);
            } catch (RuntimeException e) {
                Log.w(TAG, "fallback click", e);
            }
        }
    }

    private static synchronized void ensurePool(Context app) {
        if (loaded || loading) {
            return;
        }
        loading = true;
        try {
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            SoundPool next = new SoundPool.Builder()
                    .setMaxStreams(1)
                    .setAudioAttributes(attrs)
                    .build();
            next.setOnLoadCompleteListener((p, id, status) -> loaded = status == 0);
            int id = next.load(app, R.raw.ui_click, 1);
            pool = next;
            soundId = id;
        } catch (Throwable t) {
            Log.w(TAG, "sound pool", t);
            loading = false;
            pool = null;
            soundId = 0;
        }
    }

    /** Test / shutdown hook. */
    public static synchronized void release() {
        if (pool != null) {
            pool.release();
            pool = null;
        }
        loaded = false;
        loading = false;
        soundId = 0;
    }
}
