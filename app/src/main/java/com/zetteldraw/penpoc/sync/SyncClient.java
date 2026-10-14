package com.zetteldraw.penpoc.sync;

import com.zetteldraw.penpoc.data.InkFileStore;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.net.URLEncoder;

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

    /**
     * This Google account already has a live Boox writer (or Hand off is open for a
     * different device). Pull may still work; push is blocked until Prepare transfer
     * + web Hand off + claim.
     */
    public static final class NotLiveWriter extends IOException {
        public final String serverMessage;

        NotLiveWriter(String serverMessage) {
            super(serverMessage == null || serverMessage.isEmpty()
                    ? "not the live writer — Prepare transfer on the old Boox, then Hand off on the web"
                    : serverMessage);
            this.serverMessage = getMessage();
        }
    }

    JSONObject push(JSONObject body) throws IOException {
        return request("POST", "/sync/push", body.toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Stream a large ink file as raw octets (no Base64). Server verifies sha256 == {@code hash}.
     * Used so Prepare transfer can push a 14MB+ Scratchpad without a ~75MB heap spike.
     */
    void putBlob(String hash, File file, String deviceId) throws IOException {
        if (file == null || !file.isFile()) {
            throw new IOException("missing blob file for " + hash);
        }
        String q = deviceId == null || deviceId.isEmpty()
                ? ""
                : "?device_id=" + URLEncoder.encode(deviceId, StandardCharsets.UTF_8.name());
        HttpURLConnection conn = (HttpURLConnection) new URL(
                config.serverUrl + "/sync/blob/" + hash + q).openConnection();
        try {
            conn.setRequestMethod("PUT");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setDoOutput(true);
            conn.setRequestProperty("Authorization", "Bearer " + config.bearerToken());
            conn.setRequestProperty(SyncProtocol.SCHEMA_HEADER, String.valueOf(schemaVersion));
            conn.setRequestProperty("Content-Type", "application/octet-stream");
            conn.setRequestProperty("Accept", "application/json");
            long length = file.length();
            if (length > Integer.MAX_VALUE) {
                throw new IOException("blob too large: " + length);
            }
            conn.setFixedLengthStreamingMode((int) length);
            try (OutputStream out = conn.getOutputStream()) {
                String actual = InkFileStore.copyAndSha256(file, out);
                if (!actual.equals(hash)) {
                    throw new IOException("blob hash changed while uploading " + hash);
                }
            }
            int code = conn.getResponseCode();
            String text = readAll(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
            if (code == 401 || code == 403) {
                String err = "";
                String message = "";
                try {
                    JSONObject errBody = new JSONObject(text);
                    err = errBody.optString("error", "");
                    message = errBody.optString("message", "");
                } catch (JSONException ignored) {
                }
                if ("not_live_writer".equals(err)) {
                    throw new NotLiveWriter(message);
                }
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
            if (code == 404) {
                // Prod briefly lacked these routes when v69 shipped ahead of Render deploy.
                throw new IOException("Sync server updating — retry Prepare transfer in a minute.");
            }
            if (code < 200 || code >= 300) {
                throw new IOException("PUT /sync/blob/" + hash + " -> " + code + ": " + text);
            }
        } finally {
            conn.disconnect();
        }
    }

    /** Download a raw ink blob omitted from pull JSON (large notebook logs). */
    byte[] getBlob(String hash) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(
                config.serverUrl + "/sync/blob/" + hash).openConnection();
        try {
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("Authorization", "Bearer " + config.bearerToken());
            conn.setRequestProperty(SyncProtocol.SCHEMA_HEADER, String.valueOf(schemaVersion));
            conn.setRequestProperty("Accept", "application/octet-stream");
            int code = conn.getResponseCode();
            if (code == 401 || code == 403) {
                throw new Unauthorized();
            }
            if (code == 409) {
                throw new SchemaMismatch(-1);
            }
            if (code < 200 || code >= 300) {
                String text = readAll(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
                throw new IOException("GET /sync/blob/" + hash + " -> " + code + ": " + text);
            }
            try (InputStream in = conn.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
                byte[] bytes = out.toByteArray();
                String actual = InkFileStore.sha256(bytes);
                if (!actual.equals(hash)) {
                    throw new IOException("downloaded blob hash mismatch for " + hash);
                }
                return bytes;
            }
        } finally {
            conn.disconnect();
        }
    }

    /** Claim (or refresh) live-writer status before pushing. */
    JSONObject claimWriter(String deviceId, String deviceLabel) throws IOException {
        try {
            JSONObject body = new JSONObject()
                    .put("device_id", deviceId)
                    .put("device_label", deviceLabel == null ? "" : deviceLabel);
            return request("POST", "/sync/claim-writer", body.toString().getBytes(StandardCharsets.UTF_8));
        } catch (JSONException e) {
            throw new IOException("claim-writer body", e);
        }
    }

    /**
     * Live Boox: after outbox is empty, mark transfer prepared so web Hand off unlocks.
     * Server also requires cloud backup-status ready.
     */
    JSONObject prepareTransfer(String deviceId, String deviceLabel) throws IOException {
        try {
            JSONObject body = new JSONObject()
                    .put("device_id", deviceId)
                    .put("device_label", deviceLabel == null ? "" : deviceLabel);
            return request("POST", "/sync/prepare-transfer", body.toString().getBytes(StandardCharsets.UTF_8));
        } catch (JSONException e) {
            throw new IOException("prepare-transfer body", e);
        }
    }

    /** The on-device launch log, so it can be read from the server. */
    public void uploadDeviceLog(JSONObject body) throws IOException {
        request("POST", "/sync/device-log", body.toString().getBytes(StandardCharsets.UTF_8));
    }

    JSONObject pull(long since, int limit) throws IOException {
        return request("GET", "/sync/pull?since=" + since + "&limit=" + limit, null);
    }

    /** Live log heads (id/hash/bytes) for length reconcile after cursor catch-up. */
    JSONObject logHeads() throws IOException {
        return request("GET", "/sync/log-heads", null);
    }

    private JSONObject request(String method, String path, byte[] body) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(config.serverUrl + path).openConnection();
        try {
            conn.setRequestMethod(method);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("Authorization", "Bearer " + config.bearerToken());
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
                String err = "";
                String message = "";
                try {
                    JSONObject errBody = new JSONObject(text);
                    err = errBody.optString("error", "");
                    message = errBody.optString("message", "");
                } catch (JSONException ignored) {
                }
                if ("not_live_writer".equals(err)) {
                    throw new NotLiveWriter(message);
                }
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
                String message = "";
                try {
                    message = new JSONObject(text).optString("message", "");
                } catch (JSONException ignored) {
                }
                if (!message.isEmpty()) {
                    throw new IOException(message);
                }
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
