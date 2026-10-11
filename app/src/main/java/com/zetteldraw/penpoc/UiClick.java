package com.zetteldraw.penpoc;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.SoundPool;
import android.util.Log;

/**
 * Short UI feedback on discrete presses (tools, tabs, menus, Confirm /
 * Cancel, page chrome). Not used for stylus ink samples or finger scroll.
 *
 * Experiment (v44): four bundled micro-sounds cycle in order on each
 * {@code ACTION_DOWN} — swish → scratch → droplet → chime → … — so they
 * are less likely to read as an echo of the physical screen/pen tap.
 * Skipped in silent mode or when media volume is zero. Never blocks the
 * main thread on decode after {@link #warm(Context)}.
 */
public final class UiClick {
    private static final String TAG = "zd-click";
    /** Soft; blends under the physical screen contact. */
    private static final float VOLUME = 0.18f;

    private static final int[] RAW_IDS = {
            R.raw.ui_swish,
            R.raw.ui_scratch,
            R.raw.ui_droplet,
            R.raw.ui_chime,
    };

    private static SoundPool pool;
    private static final int[] soundIds = new int[RAW_IDS.length];
    private static int loadedCount;
    private static boolean loading;
    /** Next slot in the 1→2→3→4 cycle (session memory). */
    private static int nextIndex;

    private UiClick() {}

    /** Prefetch all four ticks so the first tap is not a silent load. */
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
            if (am.getStreamVolume(AudioManager.STREAM_MUSIC) <= 0) {
                return;
            }
        } catch (RuntimeException ignored) {
            return;
        }
        ensurePool(app);
        if (pool == null || loadedCount < RAW_IDS.length) {
            return;
        }
        int index;
        synchronized (UiClick.class) {
            index = nextIndex % RAW_IDS.length;
            nextIndex = index + 1;
        }
        int id = soundIds[index];
        if (id == 0) {
            return;
        }
        try {
            pool.play(id, VOLUME, VOLUME, 1, 0, 1f);
        } catch (RuntimeException e) {
            Log.w(TAG, "play slot " + index, e);
        }
    }

    /** Visible for tests: which slot plays next (0..3). */
    static int nextSlotForTest() {
        return nextIndex % RAW_IDS.length;
    }

    private static synchronized void ensurePool(Context app) {
        if (loadedCount == RAW_IDS.length || loading) {
            return;
        }
        loading = true;
        try {
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            SoundPool next = new SoundPool.Builder()
                    .setMaxStreams(2)
                    .setAudioAttributes(attrs)
                    .build();
            loadedCount = 0;
            next.setOnLoadCompleteListener((p, id, status) -> {
                // Real devices finish decode here; Robolectric often never fires.
                if (status == 0) {
                    synchronized (UiClick.class) {
                        if (loadedCount < RAW_IDS.length) {
                            loadedCount = RAW_IDS.length;
                        }
                    }
                }
            });
            int ready = 0;
            for (int i = 0; i < RAW_IDS.length; i++) {
                soundIds[i] = next.load(app, RAW_IDS[i], 1);
                if (soundIds[i] != 0) {
                    ready++;
                }
            }
            // load() returning an id is enough to call play(); a too-early play
            // is a no-op stream, and warm() usually finishes before the first tap.
            loadedCount = ready;
            pool = next;
        } catch (Throwable t) {
            Log.w(TAG, "sound pool", t);
            loading = false;
            pool = null;
            loadedCount = 0;
        }
    }

    /** Test / shutdown hook. */
    public static synchronized void release() {
        if (pool != null) {
            pool.release();
            pool = null;
        }
        loadedCount = 0;
        loading = false;
        nextIndex = 0;
        for (int i = 0; i < soundIds.length; i++) {
            soundIds[i] = 0;
        }
    }
}
