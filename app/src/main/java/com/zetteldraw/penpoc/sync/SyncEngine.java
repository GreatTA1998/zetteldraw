package com.zetteldraw.penpoc.sync;

import com.zetteldraw.penpoc.data.SyncStore;

import org.json.JSONException;

import java.io.IOException;
import java.util.List;
import java.util.function.LongSupplier;

/** One sync pass: drain the outbox with pushes, then pull until caught up. */
public final class SyncEngine {
    static final int PUSH_BATCH = 50;
    static final int PULL_LIMIT = 200;
    private static final int MAX_ROUNDS = 1000;

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
    }

    public Result run() throws IOException {
        Result result = new Result();
        store.beginRemoteBatch();
        try {
            push(result);
            pull(result);
            store.markSynced(clock.getAsLong());
        } finally {
            store.endRemoteBatch();
        }
        return result;
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
                results = SyncProtocol.pushResults(
                        client.push(SyncProtocol.pushBody(batch, schemaVersion, config.deviceId)));
            } catch (JSONException e) {
                throw new IOException("bad push response", e);
            }
            store.applyPushResults(batch, results);
            result.pushed += batch.notebooks.size() + batch.boards.size();
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
            store.applyPull(page);
            result.pulledBoards += page.boards.size();
            result.pulledNotebooks += page.notebooks.size();
            if (!page.hasMore) {
                return;
            }
        }
    }
}
