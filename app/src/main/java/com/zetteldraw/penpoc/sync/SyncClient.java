package com.zetteldraw.penpoc.sync;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Plain HttpURLConnection transport with the device token as a bearer token. */
public final class SyncClient {
    private static final int CONNECT_TIMEOUT_MS = 30_000;
    /** Long enough for a Render free instance to wake from sleep (~30-60 s) on the first request. */
    private static final int READ_TIMEOUT_MS = 90_000;

    private final SyncConfig config;
    private final int schemaVersion;

    public SyncClient(SyncConfig config, int schemaVersion) {
        this.config = config;
        this.schemaVersion = schemaVersion;
    }

    /** Server and client disagree on schema_version; retrying will not help. */
    public static final class SchemaMismatch extends IOException {
        public final int serverVersion;

        SchemaMismatch(int serverVersion) {
            super("server schema " + serverVersion);
            this.serverVersion = serverVersion;
        }
    }

    /** 401/403: wrong device token; retrying will not help. */
    public static final class Unauthorized extends IOException {
        Unauthorized() {
            super("device token rejected");
        }
    }

    JSONObject push(JSONObject body) throws IOException {
        return request("POST", "/sync/push", body.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** The on-device launch log, so it can be read from the server. */
    public void uploadDeviceLog(JSONObject body) throws IOException {
        request("POST", "/sync/device-log", body.toString().getBytes(StandardCharsets.UTF_8));
    }

    JSONObject pull(long since, int limit) throws IOException {
        return request("GET", "/sync/pull?since=" + since + "&limit=" + limit, null);
    }

    private JSONObject request(String method, String path, byte[] body) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(config.serverUrl + path).openConnection();
        try {
            conn.setRequestMethod(method);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("Authorization", "Bearer " + config.deviceToken);
            conn.setRequestProperty(SyncProtocol.SCHEMA_HEADER, String.valueOf(schemaVersion));
            conn.setRequestProperty("Accept", "application/json");
            if (body != null) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setFixedLengthStreamingMode(body.length);
                try (OutputStream out = conn.getOutputStream()) {
                    out.write(body);
                }
            }
            int code = conn.getResponseCode();
            String text = readAll(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
            if (code == 401 || code == 403) {
                throw new Unauthorized();
            }
            if (code == 409) {
                int server = -1;
                try {
                    server = new JSONObject(text).optInt("server_schema", -1);
                } catch (JSONException ignored) {
                }
                throw new SchemaMismatch(server);
            }
            if (code < 200 || code >= 300) {
                throw new IOException(method + " " + path + " -> " + code + ": " + text);
            }
            try {
                return new JSONObject(text);
            } catch (JSONException e) {
                throw new IOException("bad JSON from server", e);
            }
        } finally {
            conn.disconnect();
        }
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) {
            return "";
        }
        try (InputStream stream = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = stream.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }
}
