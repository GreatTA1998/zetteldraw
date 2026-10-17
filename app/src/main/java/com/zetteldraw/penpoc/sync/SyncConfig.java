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
public record SyncConfig(
        String serverUrl,
        /** Legacy DEVICE_TOKENS bearer; used only when {@link #accessToken} is empty. */
        String deviceToken,
        /** Bearer from {@code POST /auth/google} (preferred). */
        String accessToken,
        String googleEmail,
        String googleSub,
        /** User toggle: sync when signed in. */
        boolean syncOn,
        String deviceId) {
    private static final String PREFS = "zetteldraw_sync";
    private static final String KEY_URL = "server_url";
    private static final String KEY_TOKEN = "device_token";
    private static final String KEY_ACCESS = "access_token";
    private static final String KEY_EMAIL = "google_email";
    private static final String KEY_SUB = "google_sub";
    private static final String KEY_SYNC_ON = "sync_on";
    private static final String KEY_DEVICE = "device_id";
    private static final String KEY_WRITER_BLOCK = "writer_block_msg";
    /** Last app versionCode that ran {@code queueNestedNotebooksForUpload}. */
    private static final String KEY_NESTING_REPAIR_CODE = "nesting_repair_code";

    public SyncConfig {
        serverUrl = trimSlash(serverUrl);
        deviceToken = deviceToken == null ? "" : deviceToken;
        accessToken = accessToken == null ? "" : accessToken;
        googleEmail = googleEmail == null ? "" : googleEmail;
        googleSub = googleSub == null ? "" : googleSub;
    }

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

    /** Last server message when push was blocked (not live writer). Empty when clear. */
    public static String writerBlockMessage(Context context) {
        return prefs(context).getString(KEY_WRITER_BLOCK, "");
    }

    public static void setWriterBlockMessage(Context context, String message) {
        prefs(context).edit().putString(KEY_WRITER_BLOCK, message == null ? "" : message).apply();
    }

    public static void clearWriterBlockMessage(Context context) {
        prefs(context).edit().remove(KEY_WRITER_BLOCK).apply();
    }

    /** True until {@link #markNestingRepairDone} runs for this {@link BuildConfig#VERSION_CODE}. */
    public static boolean needsNestingRepair(Context context) {
        return prefs(context).getInt(KEY_NESTING_REPAIR_CODE, 0) < BuildConfig.VERSION_CODE;
    }

    /** Call after nested notebooks have been queued into the outbox for this version. */
    public static void markNestingRepairDone(Context context) {
        prefs(context).edit().putInt(KEY_NESTING_REPAIR_CODE, BuildConfig.VERSION_CODE).apply();
    }

    public static void signOut(Context context) {
        prefs(context).edit()
                .putString(KEY_ACCESS, "")
                .putString(KEY_EMAIL, "")
                .putString(KEY_SUB, "")
                .putBoolean(KEY_SYNC_ON, false)
                .remove(KEY_WRITER_BLOCK)
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
