package com.zetteldraw.penpoc.sync;

import android.content.Context;
import android.content.SharedPreferences;

import com.zetteldraw.penpoc.BuildConfig;

import java.util.UUID;

/**
 * Sync URL, Google access token (or legacy device token), and account prefs.
 * Empty URL or sync-off means no network writes. Values in SharedPreferences
 * override build-time defaults from {@code -Pzetteldraw.syncUrl} / token / Google client id.
 */
public final class SyncConfig {
    private static final String PREFS = "zetteldraw_sync";
    private static final String KEY_URL = "server_url";
    private static final String KEY_TOKEN = "device_token";
    private static final String KEY_ACCESS = "access_token";
    private static final String KEY_EMAIL = "google_email";
    private static final String KEY_SUB = "google_sub";
    private static final String KEY_SYNC_ON = "sync_on";
    private static final String KEY_DEVICE = "device_id";

    public final String serverUrl;
    /** Legacy DEVICE_TOKENS bearer; used only when {@link #accessToken} is empty. */
    public final String deviceToken;
    /** Bearer from {@code POST /auth/google} (preferred). */
    public final String accessToken;
    public final String googleEmail;
    public final String googleSub;
    /** User toggle: sync when signed in. */
    public final boolean syncOn;
    public final String deviceId;

    /** Test / legacy helper: device-token sync with sync on when URL+token are set. */
    public SyncConfig(String serverUrl, String deviceToken, String deviceId) {
        this(
                serverUrl,
                deviceToken,
                "",
                "",
                "",
                serverUrl != null && !serverUrl.isEmpty() && deviceToken != null && !deviceToken.isEmpty(),
                deviceId);
    }

    public SyncConfig(
            String serverUrl,
            String deviceToken,
            String accessToken,
            String googleEmail,
            String googleSub,
            boolean syncOn,
            String deviceId) {
        this.serverUrl = trimSlash(serverUrl);
        this.deviceToken = deviceToken == null ? "" : deviceToken;
        this.accessToken = accessToken == null ? "" : accessToken;
        this.googleEmail = googleEmail == null ? "" : googleEmail;
        this.googleSub = googleSub == null ? "" : googleSub;
        this.syncOn = syncOn;
        this.deviceId = deviceId;
    }

    public boolean signedIn() {
        return !accessToken.isEmpty() || (!googleSub.isEmpty() && !deviceToken.isEmpty());
    }

    public boolean hasGoogleAccount() {
        return !googleSub.isEmpty() || !googleEmail.isEmpty();
    }

    /** Bearer sent on /sync and /auth. Prefer the Google access token. */
    public String bearerToken() {
        if (!accessToken.isEmpty()) {
            return accessToken;
        }
        return deviceToken;
    }

    public boolean enabled() {
        return !serverUrl.isEmpty() && syncOn && !bearerToken().isEmpty();
    }

    public static SyncConfig load(Context context) {
        SharedPreferences prefs = prefs(context);
        String deviceId = prefs.getString(KEY_DEVICE, null);
        if (deviceId == null) {
            deviceId = UUID.randomUUID().toString();
            prefs.edit().putString(KEY_DEVICE, deviceId).apply();
        }
        boolean defaultSyncOn = !BuildConfig.SYNC_URL.isEmpty() && !BuildConfig.SYNC_TOKEN.isEmpty();
        return new SyncConfig(
                prefs.getString(KEY_URL, BuildConfig.SYNC_URL),
                prefs.getString(KEY_TOKEN, BuildConfig.SYNC_TOKEN),
                prefs.getString(KEY_ACCESS, ""),
                prefs.getString(KEY_EMAIL, ""),
                prefs.getString(KEY_SUB, ""),
                prefs.contains(KEY_SYNC_ON) ? prefs.getBoolean(KEY_SYNC_ON, false) : defaultSyncOn,
                deviceId);
    }

    /** Legacy: set URL + device token (turns sync on when both non-empty). */
    public static void save(Context context, String serverUrl, String deviceToken) {
        SharedPreferences.Editor edit = prefs(context).edit()
                .putString(KEY_URL, serverUrl == null ? "" : serverUrl)
                .putString(KEY_TOKEN, deviceToken == null ? "" : deviceToken);
        if (serverUrl != null && !serverUrl.isEmpty() && deviceToken != null && !deviceToken.isEmpty()) {
            edit.putBoolean(KEY_SYNC_ON, true);
        }
        edit.apply();
        SyncScheduler.schedule(context);
    }

    public static void saveGoogleSession(
            Context context,
            String serverUrl,
            String accessToken,
            String email,
            String sub,
            boolean syncOn) {
        SharedPreferences.Editor edit = prefs(context).edit();
        if (serverUrl != null) {
            edit.putString(KEY_URL, serverUrl);
        }
        edit.putString(KEY_ACCESS, accessToken == null ? "" : accessToken)
                .putString(KEY_EMAIL, email == null ? "" : email)
                .putString(KEY_SUB, sub == null ? "" : sub)
                .putBoolean(KEY_SYNC_ON, syncOn)
                // Stop using the baked shared device token once Google is in play.
                .putString(KEY_TOKEN, "");
        edit.apply();
        SyncScheduler.schedule(context);
    }

    public static void setSyncOn(Context context, boolean syncOn) {
        prefs(context).edit().putBoolean(KEY_SYNC_ON, syncOn).apply();
        SyncScheduler.schedule(context);
    }

    public static void signOut(Context context) {
        prefs(context).edit()
                .putString(KEY_ACCESS, "")
                .putString(KEY_EMAIL, "")
                .putString(KEY_SUB, "")
                .putBoolean(KEY_SYNC_ON, false)
                .apply();
        SyncScheduler.schedule(context);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String trimSlash(String url) {
        if (url == null) {
            return "";
        }
        String t = url.trim();
        while (t.endsWith("/")) {
            t = t.substring(0, t.length() - 1);
        }
        return t;
    }
}
