package com.zetteldraw.penpoc.sync;

import android.os.Build;
import android.util.Log;

import com.zetteldraw.penpoc.LaunchLog;
import com.zetteldraw.penpoc.data.InkFileStore;
import com.zetteldraw.penpoc.data.SyncStore;
import com.zetteldraw.penpoc.data.db.BoardEntity;
import com.zetteldraw.penpoc.data.db.NotebookLogEntity;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/** One sync pass: drain the outbox with pushes, then pull until caught up. */
public final class SyncEngine {
    private static final String TAG = "SyncEngine";
    static final int PUSH_BATCH = 50;
    static final int PULL_LIMIT = 200;
    private static final int MAX_ROUNDS = 1000;
    /**
     * One sync/prepare at a time across WorkManager + Settings Prepare transfer.
     * Concurrent passes raced Room refresh delivery and crashed the activity on retry.
     */
    private static final ReentrantLock GATE = new ReentrantLock();

    private final SyncStore store;
    private final SyncClient client;
    private final SyncConfig config;
    private final int schemaVersion;
    private final LongSupplier clock;

    public SyncEngine(SyncStore store, SyncClient client, SyncConfig config, int schemaVersion, LongSupplier clock) {
        this.store = store;
        this.client = client;
        this.config = config;
        this.schemaVersion = schemaVersion;
        this.clock = clock;
    }

    public static final class Result {
        public int pushed;
        public int pulledBoards;
        public int pulledNotebooks;
        /** True when this pass pulled before pushing (cloud seeded a new device). */
        public boolean restoredFromCloud;
        /**
         * True when the server refused write access because another Boox is the
         * live writer (or Hand off is waiting for a different device).
         */
        public boolean blockedAsNonWriter;
        public String writerBlockMessage;
    }

    public Result run() throws IOException {
        GATE.lock();
        try {
            return runLocked();
        } finally {
            GATE.unlock();
        }
    }

    private Result runLocked() throws IOException {
        Result result = new Result();
        store.beginRemoteBatch();
        try {
            // First sync on a device: cloud is the seed. Pull before push so
            // empty local seed notebooks cannot clobber the server library.
            boolean neverSynced = store.lastSyncedAt() == 0L && store.pullCursor() == 0L;
            if (neverSynced) {
                pull(result);
                result.restoredFromCloud = true;
            }
            if (!claimLiveWriter(result)) {
                IOException pullError = null;
                if (!neverSynced) {
                    try {
                        pull(result);
                    } catch (IOException e) {
                        // Length sync is a separate phase: cursor pull failure must
                        // not skip per-id longer-log adoption.
                        pullError = e;
                        Log.w(TAG, "pull failed; still running ink log length sync", e);
                        LaunchLog.mark("pull failed before length sync: " + e.getMessage());
                    }
                }
                syncInkLogLengths(result);
                if (pullError != null) {
                    throw pullError;
                }
                return result;
            }
            if (neverSynced) {
                store.queuePlaceholderSeedsForUpload();
            }
            // Every pass: re-queue rev==0 rows whose outbox entry was lost.
            // Nested parent_id repair (incl. rev>0 drift) is one-shot in SyncWorker.
            store.ensureUnsyncedQueued();
            push(result);
            IOException pullError = null;
            try {
                pull(result);
            } catch (IOException e) {
                pullError = e;
                Log.w(TAG, "pull failed; still running ink log length sync", e);
                LaunchLog.mark("pull failed before length sync: " + e.getMessage());
            }
            // Cursor pull only delivers rev > cursor. Length authority is per-id
            // log-heads (ink_bytes / hash), not the global cursor.
            syncInkLogLengths(result);
            if (pullError != null) {
                throw pullError;
            }
            // Only stamp last_ok_at when the outbox is empty. A sticky push
            // (forbidden / missing blob) must not look like "sync complete".
            if (store.outboxSize() == 0) {
                store.markSynced(clock.getAsLong());
            }
        } finally {
            store.endRemoteBatch();
        }
        return result;
    }

    /**
     * Drain outbox (sync), then tell the server this live Boox prepared transfer.
     * Web Hand off stays locked until this succeeds (or lost-device path on web).
     */
    public Result prepareTransfer() throws IOException {
        GATE.lock();
        try {
            Result result = runLocked();
            if (result.blockedAsNonWriter) {
                return result;
            }
            if (store.outboxSize() != 0) {
                throw new IOException("Sync still has " + store.outboxSize()
                        + " change(s) waiting. Wait until caught up, then Prepare transfer again.");
            }
            client.prepareTransfer(config.deviceId, deviceLabel());
            return result;
        } finally {
            GATE.unlock();
        }
    }

    /** Google accounts must hold the live-writer lease before any push. */
    private boolean claimLiveWriter(Result result) throws IOException {
        if (config.accessToken == null || config.accessToken.isEmpty()) {
            // Legacy device-token sync: server skips the lease.
            return true;
        }
        try {
            client.claimWriter(config.deviceId, deviceLabel());
            return true;
        } catch (SyncClient.NotLiveWriter e) {
            result.blockedAsNonWriter = true;
            result.writerBlockMessage = e.serverMessage;
            return false;
        }
    }

    static String deviceLabel() {
        String model = Build.MODEL;
        return model == null || model.isEmpty() ? "Boox" : model;
    }

    private void push(Result result) throws IOException {
        for (int round = 0; round < MAX_ROUNDS; round++) {
            int before = store.outboxSize();
            SyncStore.PushBatch batch = store.pendingPush(PUSH_BATCH);
            if (batch.isEmpty()) {
                return;
            }
            // Large files: raw PUT first so push JSON never Base64-encodes them.
            try {
                for (Map.Entry<String, File> e : batch.blobFiles.entrySet()) {
                    client.putBlob(e.getKey(), e.getValue(), config.deviceId);
                }
            } catch (SyncClient.NotLiveWriter e) {
                result.blockedAsNonWriter = true;
                result.writerBlockMessage = e.serverMessage;
                return;
            }
            List<SyncStore.PushResult> results;
            try {
                results = SyncProtocol.pushResults(
                        client.push(SyncProtocol.pushBody(batch, schemaVersion, config.deviceId)));
            } catch (JSONException e) {
                throw new IOException("bad push response", e);
            } catch (SyncClient.NotLiveWriter e) {
                result.blockedAsNonWriter = true;
                result.writerBlockMessage = e.serverMessage;
                return;
            }
            store.applyPushResults(batch, results);
            result.pushed += batch.notebooks.size() + batch.boards.size() + batch.logs.size();
            if (store.outboxSize() >= before) {
                // Nothing drained (e.g. missing blobs or rows edited mid-flight); try next pass.
                return;
            }
        }
    }

    private void pull(Result result) throws IOException {
        for (int round = 0; round < MAX_ROUNDS; round++) {
            SyncStore.PullPage page;
            try {
                page = SyncProtocol.pullPage(client.pull(store.pullCursor(), PULL_LIMIT));
            } catch (JSONException e) {
                throw new IOException("bad pull response", e);
            }
            fetchMissingBlobs(page);
            store.applyPull(page);
            result.pulledBoards += page.boards.size();
            result.pulledNotebooks += page.notebooks.size();
            if (!page.hasMore) {
                return;
            }
        }
    }

    /**
     * Length-sync phase: adopt cloud logs that are longer (by ink_bytes) than the
     * on-disk file for the same log id. Not gated on cursor catch-up. Never
     * shortens; never touches a different notebook's log. Cursor is left unchanged
     * (synthetic page uses the current cursor).
     */
    private void syncInkLogLengths(Result result) throws IOException {
        JSONObject body;
        try {
            body = client.logHeads();
        } catch (SyncClient.Unauthorized e) {
            throw e;
        } catch (SyncClient.SchemaMismatch e) {
            throw e;
        } catch (IOException e) {
            // Older servers without the route must not fail the whole sync pass.
            String msg = e.getMessage() == null ? "" : e.getMessage();
            if (msg.contains("404") || msg.contains(" -> 404")) {
                Log.w(TAG, "log-heads unavailable; skip ink log length sync", e);
                return;
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
            try {
                head = heads.getJSONObject(i);
            } catch (JSONException e) {
                throw new IOException("bad log-heads row", e);
            }
            String id = head.optString("id", "");
            String hash = head.optString("ink_hash", "");
            long remoteBytes = head.optLong("ink_bytes", -1L);
            if (id.isEmpty() || hash.isEmpty() || remoteBytes < 0) {
                continue;
            }
            long localBytes = store.localInkLogBytes(id);
            String localHash = store.localInkLogHash(id);
            boolean sameHash = hash.equals(localHash);
            // Never shorten via reconcile. Adopt when cloud is longer, or when
            // byte length matches but the on-disk hash drifted from the primary.
            if (remoteBytes < localBytes) {
                continue;
            }
            if (remoteBytes == localBytes && sameHash) {
                continue;
            }
            LaunchLog.mark("reconcile longer log " + id
                    + " remoteBytes=" + remoteBytes + " localBytes=" + localBytes
                    + " hashMatch=" + sameHash);
            byte[] bytes = client.getBlob(hash);
            if (bytes == null || bytes.length == 0) {
                throw new IOException("missing blob for longer log " + id);
            }
            if (!InkFileStore.sha256(bytes).equals(hash)) {
                throw new IOException("bad blob hash for longer log " + id);
            }
            NotebookLogEntity remote = new NotebookLogEntity();
            remote.id = id;
            remote.notebookId = optNullString(head, "notebook_id");
            remote.inkHash = hash;
            remote.inkBytes = remoteBytes;
            remote.sliceHeight = head.optInt("slice_height", 0);
            remote.createdAt = head.optLong("created_at", 0L);
            remote.updatedAt = head.optLong("updated_at", 0L);
            remote.rev = head.optLong("rev", 0L);
            remote.deletedAt = null;
            remote.conflictOf = null;
            SyncStore.PullPage page = new SyncStore.PullPage();
            page.logs.add(remote);
            page.blobs.put(hash, bytes);
            // Keep cursor: this is not a rev-stream advance.
            page.cursor = store.pullCursor();
            page.hasMore = false;
            store.applyPull(page);
            adopted++;
        }
        if (adopted > 0) {
            result.pulledNotebooks += adopted;
            LaunchLog.mark("reconcile adopted " + adopted + " longer log(s)");
        }
    }

    private static String optNullString(JSONObject o, String key) {
        if (o.isNull(key)) {
            return null;
        }
        String v = o.optString(key, "");
        return v.isEmpty() ? null : v;
    }

    /** Pull JSON omits large Base64 blobs; fetch them raw before applyPull. */
    private void fetchMissingBlobs(SyncStore.PullPage page) throws IOException {
        for (NotebookLogEntity log : page.logs) {
            if (log.inkHash == null || log.deletedAt != null) {
                continue;
            }
            if (!page.blobs.containsKey(log.inkHash)) {
                page.blobs.put(log.inkHash, client.getBlob(log.inkHash));
            }
        }
        for (BoardEntity board : page.boards) {
            if (board.inkHash == null || board.deletedAt != null) {
                continue;
            }
            if (!page.blobs.containsKey(board.inkHash)) {
                page.blobs.put(board.inkHash, client.getBlob(board.inkHash));
            }
        }
    }
}
