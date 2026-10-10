package com.zetteldraw.penpoc.sync;

import android.app.Activity;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.credentials.Credential;
import androidx.credentials.CredentialManager;
import androidx.credentials.CredentialManagerCallback;
import androidx.credentials.CustomCredential;
import androidx.credentials.GetCredentialRequest;
import androidx.credentials.GetCredentialResponse;
import androidx.credentials.exceptions.GetCredentialException;

import com.google.android.libraries.identity.googleid.GetGoogleIdOption;
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential;
import com.zetteldraw.penpoc.BuildConfig;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Google Sign-In via Credential Manager, then exchange the ID token for a
 * sync-server access token ({@code POST /auth/google}), which also runs the
 * one-shot shared-library claim.
 */
public final class GoogleAuth {
    private static final String TAG = "zd-google";
    private static final Executor IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "zd-google-auth");
        t.setDaemon(true);
        return t;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public interface Callback {
        void onSuccess(String email, String claimStatus);

        void onError(String message);
    }

    private GoogleAuth() {
    }

    public static boolean webClientConfigured() {
        return BuildConfig.GOOGLE_WEB_CLIENT_ID != null && !BuildConfig.GOOGLE_WEB_CLIENT_ID.isEmpty();
    }

    public static void signIn(Activity activity, Callback callback) {
        if (!webClientConfigured()) {
            callback.onError("Google client id is not set in this build. Set -Pzetteldraw.googleWebClientId.");
            return;
        }
        String serverUrl = SyncConfig.load(activity).serverUrl;
        if (serverUrl.isEmpty()) {
            serverUrl = BuildConfig.SYNC_URL;
        }
        if (serverUrl.isEmpty()) {
            callback.onError("Sync server URL is not set.");
            return;
        }
        final String url = serverUrl;

        GetGoogleIdOption googleIdOption = new GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(false)
                .setServerClientId(BuildConfig.GOOGLE_WEB_CLIENT_ID)
                .setAutoSelectEnabled(false)
                .build();
        GetCredentialRequest request = new GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build();

        CredentialManager manager = CredentialManager.create(activity);
        manager.getCredentialAsync(
                activity,
                request,
                new CancellationSignal(),
                IO,
                new CredentialManagerCallback<GetCredentialResponse, GetCredentialException>() {
                    @Override
                    public void onResult(GetCredentialResponse response) {
                        try {
                            String idToken = idTokenFrom(response.getCredential());
                            if (idToken == null || idToken.isEmpty()) {
                                MAIN.post(() -> callback.onError("Google did not return an ID token."));
                                return;
                            }
                            JSONObject session = exchange(url, idToken);
                            String access = session.optString("access_token", "");
                            JSONObject user = session.optJSONObject("user");
                            String email = user != null ? user.optString("email", "") : "";
                            String sub = user != null ? user.optString("id", "") : "";
                            JSONObject claim = session.optJSONObject("claim");
                            String claimStatus = claim != null ? claim.optString("status", "") : "";
                            if (access.isEmpty() || sub.isEmpty()) {
                                MAIN.post(() -> callback.onError("Sync server did not return an access token."));
                                return;
                            }
                            SyncConfig.saveGoogleSession(activity, url, access, email, sub, true);
                            MAIN.post(() -> callback.onSuccess(email, claimStatus));
                        } catch (Exception e) {
                            Log.w(TAG, "exchange failed", e);
                            MAIN.post(() -> callback.onError(
                                    e.getMessage() != null ? e.getMessage() : "Sign-in failed."));
                        }
                    }

                    @Override
                    public void onError(GetCredentialException e) {
                        Log.w(TAG, "credential failed", e);
                        MAIN.post(() -> callback.onError(humanCredentialError(e)));
                    }
                });
    }

    private static String idTokenFrom(Credential credential) {
        if (!(credential instanceof CustomCredential)) {
            return null;
        }
        CustomCredential custom = (CustomCredential) credential;
        if (!GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL.equals(custom.getType())) {
            return null;
        }
        GoogleIdTokenCredential google = GoogleIdTokenCredential.createFrom(custom.getData());
        return google.getIdToken();
    }

    private static JSONObject exchange(String serverUrl, String idToken) throws Exception {
        byte[] body = new JSONObject()
                .put("id_token", idToken)
                .put("claim", true)
                .toString()
                .getBytes(StandardCharsets.UTF_8);
        HttpURLConnection conn = (HttpURLConnection) new URL(serverUrl + "/auth/google").openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(30_000);
            conn.setReadTimeout(90_000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json");
            conn.setFixedLengthStreamingMode(body.length);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(body);
            }
            int code = conn.getResponseCode();
            String text = readAll(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
            if (code < 200 || code >= 300) {
                throw new Exception("auth/google -> " + code + ": " + text);
            }
            return new JSONObject(text);
        } finally {
            conn.disconnect();
        }
    }

    private static String humanCredentialError(GetCredentialException e) {
        String msg = e.getMessage();
        if (msg != null && msg.toLowerCase().contains("cancel")) {
            return "Sign-in cancelled.";
        }
        return "Google Sign-In is unavailable on this device (Play Services required). " +
                (msg != null ? msg : e.getClass().getSimpleName());
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) {
            return "";
        }
        try (InputStream stream = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8 * 1024];
            int n;
            while ((n = stream.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }
}
