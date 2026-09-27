package com.zetteldraw.penpoc.data;

import com.zetteldraw.penpoc.data.db.BoardEntity;
import com.zetteldraw.penpoc.data.db.NotebookEntity;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** What the sync engine needs from local storage. Transport lives in {@code sync}. */
public interface SyncStore {
    /** Next outbox rows to push, with the ink blobs they reference. */
    PushBatch pendingPush(int limit);

    void applyPushResults(PushBatch batch, List<PushResult> results);

    long pullCursor();

    /** Apply one pulled page and advance the cursor, atomically. */
    void applyPull(PullPage page) throws IOException;

    void markSynced(long now);

    int outboxSize();

    final class PushBatch {
        public final List<NotebookEntity> notebooks = new ArrayList<>();
        public final List<BoardEntity> boards = new ArrayList<>();
        /** sha256 hex → ink file bytes. */
        public final Map<String, byte[]> blobs = new HashMap<>();
        /** "entity:id" → outbox queued_at snapshot. */
        final Map<String, Long> queuedAt = new HashMap<>();

        public boolean isEmpty() {
            return notebooks.isEmpty() && boards.isEmpty();
        }
    }

    final class PushResult {
        public static final String APPLIED = "applied";
        public static final String CONFLICT_WON = "conflict_won";
        public static final String CONFLICT_LOST = "conflict_lost";
        public static final String MISSING_BLOB = "missing_blob";

        public final String entity;
        public final String id;
        public final String status;
        public final long rev;

        public PushResult(String entity, String id, String status, long rev) {
            this.entity = entity;
            this.id = id;
            this.status = status;
            this.rev = rev;
        }
    }

    final class PullPage {
        public final List<NotebookEntity> notebooks = new ArrayList<>();
        public final List<BoardEntity> boards = new ArrayList<>();
        public final Map<String, byte[]> blobs = new HashMap<>();
        public long cursor;
        public boolean hasMore;
    }
}
