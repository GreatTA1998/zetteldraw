package com.zetteldraw.penpoc.sync;

import android.util.Log;

import com.zetteldraw.penpoc.LaunchLog;
import com.zetteldraw.penpoc.data.SyncStore;
import com.zetteldraw.penpoc.data.db.NotebookLogEntity;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;

/**
 * Per-id ink-log length catch-up via {@code GET /sync/log-heads}.
 *
 * <p><strong>Catch-up only</strong> (non-writer / restore). Live writer does
 * not call this — length work while live is push + refuse shorter on pull.
 * Never shortens; never touches a different notebook's log.
 *
 * @see docs/system-architecture.md
 */
public final class InkLogSync {
    private static final String TAG = "InkLogSync";

    private final SyncStore store;
    private final SyncClient client;

    public InkLogSync(SyncStore store, SyncClient client) {
        this.store = store;
        this.client = client;
    }

    /**
     * Adopt cloud logs longer (by {@code ink_bytes}) than on-disk for the same
     * id. No-op under {@link SyncStore.InkPullPolicy#LIVE_PUSH_ONLY}.
     *
     * @return how many logs were adopted
     */
    public int syncLengths() throws IOException {
        if (!store.allowRemoteInkAdopt()) {
            return 0;
        }
        JSONObject body;
        try {
            body = client.logHeads();
        } catch (SyncClient.Unauthorized | SyncClient.SchemaMismatch e) {
            throw e;
        } catch (IOException e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            if (msg.contains("404") || msg.contains(" -> 404")) {
                Log.w(TAG, "log-heads unavailable; skip ink log length sync", e);
                return 0;
            }
            throw e;
        }
        JSONArray heads;
        try {
            heads = body.getJSONArray("logs");
        } catch (JSONException e) {
            throw new IOException("bad log-heads response", e);
        }
        int adopted = 0;
        for (int i = 0; i < heads.length(); i++) {
            JSONObject head;
            NotebookLogEntity remote;
            try {
                head = heads.getJSONObject(i);
                remote = SyncProtocol.logFrom(head);
            } catch (JSONException e) {
                throw new IOException("bad log-heads row", e);
            }
            if (remote.id == null || remote.id.isEmpty()
                    || remote.inkHash == null || remote.inkHash.isEmpty()
                    || remote.inkBytes < 0) {
                continue;
            }
            long localBytes = store.localInkLogBytes(remote.id);
            String localHash = store.localInkLogHash(remote.id);
            boolean sameHash = remote.inkHash.equals(localHash);
            if (remote.inkBytes < localBytes || (remote.inkBytes == localBytes && sameHash)) {
                continue;
            }
            LaunchLog.mark("length sync longer log " + remote.id
                    + " remoteBytes=" + remote.inkBytes + " localBytes=" + localBytes
                    + " hashMatch=" + sameHash);
            // SyncClient.getBlob already hash-checks; empty body is a mismatch too.
            byte[] bytes = client.getBlob(remote.inkHash);
            // log-heads are live primaries only (SQL filters deleted/conflict).
            remote.deletedAt = null;
            remote.conflictOf = null;
            SyncStore.PullPage page = new SyncStore.PullPage();
            page.logs.add(remote);
            page.blobs.put(remote.inkHash, bytes);
            page.cursor = store.pullCursor();
            page.hasMore = false;
            store.applyPull(page);
            adopted++;
        }
        if (adopted > 0) {
            LaunchLog.mark("length sync adopted " + adopted + " longer log(s)");
        }
        return adopted;
    }
}
