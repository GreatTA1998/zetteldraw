package com.zetteldraw.penpoc.sync;

import android.content.Context;
import android.content.SharedPreferences;

import com.zetteldraw.penpoc.BuildConfig;

import java.util.UUID;

/**
 * Server URL + device token. Empty URL means sync is off (the default).
 * Values in SharedPreferences override the build-time ones from
 * {@code -Pzetteldraw.syncUrl} / {@code -Pzetteldraw.syncToken}.
 */
public final class SyncConfig {
    private static final String PREFS = "zetteldraw_sync";
    private static final String KEY_URL = "server_url";
    private static final String KEY_TOKEN = "device_token";
    private static final String KEY_DEVICE = "device_id";

    public final String serverUrl;
    public final String deviceToken;
    public final String deviceId;

    public SyncConfig(String serverUrl, String deviceToken, String deviceId) {
        this.serverUrl = trimSlash(serverUrl);
        this.deviceToken = deviceToken == null ? "" : deviceToken;
        this.deviceId = deviceId;
    }

    public boolean enabled() {
        return !serverUrl.isEmpty();
    }

    public static SyncConfig load(Context context) {
        SharedPreferences prefs = prefs(context);
        String deviceId = prefs.getString(KEY_DEVICE, null);
        if (deviceId == null) {
            deviceId = UUID.randomUUID().toString();
            prefs.edit().putString(KEY_DEVICE, deviceId).apply();
        }
        return new SyncConfig(
                prefs.getString(KEY_URL, BuildConfig.SYNC_URL),
                prefs.getString(KEY_TOKEN, BuildConfig.SYNC_TOKEN),
                deviceId);
    }

    /** For a future settings screen. An empty URL turns sync off. */
    public static void save(Context context, String serverUrl, String deviceToken) {
        prefs(context).edit()
                .putString(KEY_URL, serverUrl == null ? "" : serverUrl)
                .putString(KEY_TOKEN, deviceToken == null ? "" : deviceToken)
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
