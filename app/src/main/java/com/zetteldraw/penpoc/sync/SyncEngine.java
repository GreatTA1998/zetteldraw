package com.zetteldraw.penpoc.sync;

import android.os.Build;
import android.util.Log;

import com.zetteldraw.penpoc.LaunchLog;
import com.zetteldraw.penpoc.data.SyncStore;
import com.zetteldraw.penpoc.data.db.BoardEntity;
import com.zetteldraw.penpoc.data.db.NotebookLogEntity;

import org.json.JSONException;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/**
 * One sync pass: claim lease, push (live only), pull, optional length catch-up.
 *
 * <p>Live writer: ink is push-only — pull must not adopt remote over local.
 * Non-writer / first restore: catch-up adopt via {@link InkLogSync}.
 */
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
    private final InkLogSync inkLogSync;

    public SyncEngine(SyncStore store, SyncClient client, SyncConfig config, int schemaVersion, LongSupplier clock) {
        this.store = store;
        this.client = client;
        this.config = config;
        this.schemaVersion = schemaVersion;
        this.clock = clock;
        this.inkLogSync = new InkLogSync(store, client);
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
            // First sync: cloud seeds the device. Catch-up adopt before claim.
            boolean neverSynced = store.lastSyncedAt() == 0L && store.pullCursor() == 0L;
            if (neverSynced) {
                store.setInkPullPolicy(SyncStore.InkPullPolicy.CATCH_UP);
                pull(result);
                result.restoredFromCloud = true;
            }
            boolean live = claimLiveWriter(result);
            if (live) {
                // Live writer: push-only for ink. Pull may update meta; never adopt
                // remote ink over local (InkLogApply KEEP_LIVE_LOCAL).
                store.setInkPullPolicy(SyncStore.InkPullPolicy.LIVE_PUSH_ONLY);
                if (neverSynced) {
                    store.queuePlaceholderSeedsForUpload();
                }
                store.ensureUnsyncedQueued();
                push(result);
            } else {
                // Non-writer: read catch-up only.
                store.setInkPullPolicy(SyncStore.InkPullPolicy.CATCH_UP);
            }
            // One pull + deferred error: non-writer still runs length sync first.
            IOException pullError = null;
            if (live || !neverSynced) {
                try {
                    pull(result);
                } catch (IOException e) {
                    pullError = e;
                    String when = live ? "after live push" : "before length sync";
                    Log.w(TAG, "pull failed " + when, e);
                    LaunchLog.mark("pull failed " + when + ": " + e.getMessage());
                }
            }
            if (!live) {
                result.pulledNotebooks += inkLogSync.syncLengths();
            }
            if (pullError != null) {
                throw pullError;
            }
            if (live && store.outboxSize() == 0) {
                // Live length work = push + refuse shorter on pull — not log-heads adopt.
                store.markSynced(clock.getAsLong());
            }
        } finally {
            store.setInkPullPolicy(SyncStore.InkPullPolicy.CATCH_UP);
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
            client.prepareTransfer(config.deviceId(), deviceLabel());
            return result;
        } finally {
            GATE.unlock();
        }
    }

    /** Google accounts must hold the live-writer lease before any push. */
    private boolean claimLiveWriter(Result result) throws IOException {
        if (config.accessToken().isEmpty()) {
            // Legacy device-token sync: server skips the lease.
            return true;
        }
        try {
            client.claimWriter(config.deviceId(), deviceLabel());
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
            List<SyncStore.PushResult> results;
            try {
                for (Map.Entry<String, File> e : batch.blobFiles.entrySet()) {
                    client.putBlob(e.getKey(), e.getValue(), config.deviceId());
                }
                results = SyncProtocol.pushResults(
                        client.push(SyncProtocol.pushBody(batch, schemaVersion, config.deviceId())));
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
     * Pull JSON omits large Base64 blobs. Skip getBlob when local hash already
     * matches (applyPull meta-only omit for logs and legacy boards).
     */
    private void fetchMissingBlobs(SyncStore.PullPage page) throws IOException {
        for (NotebookLogEntity log : page.logs) {
            ensureBlob(page.blobs, log.inkHash, log.deletedAt, store.localInkLogHash(log.id));
        }
        for (BoardEntity board : page.boards) {
            ensureBlob(page.blobs, board.inkHash, board.deletedAt, store.localBoardInkHash(board.id));
        }
    }

    private void ensureBlob(Map<String, byte[]> blobs, String hash, Long deletedAt, String localHash)
            throws IOException {
        if (hash == null || deletedAt != null || blobs.containsKey(hash) || hash.equals(localHash)) {
            return;
        }
        blobs.put(hash, client.getBlob(hash));
    }
}
