package com.zetteldraw.penpoc.data;

import com.zetteldraw.penpoc.data.db.BoardEntity;
import com.zetteldraw.penpoc.data.db.NotebookEntity;
import com.zetteldraw.penpoc.data.db.NotebookLogEntity;
import com.zetteldraw.penpoc.data.db.PageLinkEntity;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** What the sync engine needs from local storage. Transport lives in {@code sync}. */
public interface SyncStore {
    /**
     * Blobs larger than this leave the JSON Base64 path and use raw {@code /sync/blob/:hash}.
     * Keep in sync with server {@code INLINE_BLOB_MAX_BYTES} and {@code SyncProtocol}.
     */
    int INLINE_BLOB_MAX_BYTES = 256 * 1024;

    /** Next outbox rows to push, with the ink blobs they reference. */
    PushBatch pendingPush(int limit);

    void applyPushResults(PushBatch batch, List<PushResult> results);

    long pullCursor();

    /** Apply one pulled page and advance the cursor, atomically. */
    void applyPull(PullPage page) throws IOException;

    /**
     * On-disk ink-log byte length for {@code sheetId}, or {@code 0} if the
     * {@code .zdl} file is missing. Must not fall back to Room meta — a stale
     * long {@code ink_bytes} row with a short/missing file would skip reconcile.
     */
    default long localInkLogBytes(String sheetId) {
        return 0L;
    }

    /**
     * Sha256 of the on-disk ink log, or empty when the file is missing.
     * Reconcile adopts when the cloud hash differs and remote bytes are not shorter.
     */
    default String localInkLogHash(String sheetId) {
        return "";
    }

    /** Room board {@code ink_hash}, or empty — omit-fetch when it matches remote. */
    default String localBoardInkHash(String boardId) {
        return "";
    }

    void markSynced(long now);

    int outboxSize();

    /** Epoch millis of the last sync pass that drained the outbox; 0 if never. */
    default long lastSyncedAt() {
        return 0L;
    }

    /**
     * Defensive: queue every live local row that has never been pushed ({@code rev == 0})
     * and is not already in the outbox. Used on first Google Sign-In so local-only notes
     * upload even if an earlier path skipped {@code queueLocked}.
     *
     * @return how many new outbox rows were added
     */
    default int ensureUnsyncedQueued() {
        return 0;
    }

    /**
     * Re-queue every live nested notebook ({@code parent_id != null}) that is not
     * already in the outbox. Heals cases where a child exists only on the device
     * (or parent_id was placed locally) but never landed in Postgres — the web
     * companion builds sub-bars from {@code parent_id} on {@code /web/notebooks}.
     *
     * @return how many new outbox rows were added
     */
    default int queueNestedNotebooksForUpload() {
        return 0;
    }

    /**
     * After a restore pull on a never-synced device, queue untouched seed notebooks
     * that still have {@code rev == 0} (not present on the server) so a brand-new
     * account gets starter notebooks. No-op when seeds were already adopted from pull.
     */
    default void queuePlaceholderSeedsForUpload() {
    }

    /** Pulls between begin and end reach the UI as one refresh when the outermost batch ends. */
    default void beginRemoteBatch() {
    }

    default void endRemoteBatch() {
    }

    /**
     * Ink pull policy for the current sync pass.
     * {@link InkPullPolicy#LIVE_PUSH_ONLY}: refuse shorter; keep local over remote body.
     * {@link InkPullPolicy#CATCH_UP}: may {@code ADOPT_LONGER} (non-writer / restore).
     */
    enum InkPullPolicy {
        CATCH_UP,
        LIVE_PUSH_ONLY,
    }

    default void setInkPullPolicy(InkPullPolicy policy) {
    }

    default InkPullPolicy inkPullPolicy() {
        return InkPullPolicy.CATCH_UP;
    }

    default boolean allowRemoteInkAdopt() {
        return inkPullPolicy() == InkPullPolicy.CATCH_UP;
    }

    /**
     * Shared notebook/board/log/link + small-blob bags for push and pull.
     * Push adds large-file maps; pull adds cursor paging.
     */
    class EntityBag {
        public final List<NotebookEntity> notebooks = new ArrayList<>();
        public final List<BoardEntity> boards = new ArrayList<>();
        /** One ink log per notebook. A push sends the whole log, never a torn stroke. */
        public final List<NotebookLogEntity> logs = new ArrayList<>();
        /** Page links. Metadata only: a link copies no ink. */
        public final List<PageLinkEntity> links = new ArrayList<>();
        /**
         * sha256 hex → ink file bytes for small blobs inlined as Base64 in push JSON.
         * Large logs go in {@link PushBatch#blobFiles} instead so Prepare transfer never
         * Base64s a multi-megabyte Scratchpad into the heap.
         */
        public final Map<String, byte[]> blobs = new HashMap<>();

        public boolean isEmpty() {
            return notebooks.isEmpty() && boards.isEmpty() && logs.isEmpty() && links.isEmpty();
        }
    }

    final class PushBatch extends EntityBag {
        /** sha256 hex → on-disk ink file for raw PUT /sync/blob/:hash (no Base64). */
        public final Map<String, File> blobFiles = new HashMap<>();
        /** "entity:id" → outbox queued_at snapshot. */
        final Map<String, Long> queuedAt = new HashMap<>();
    }

    record PushResult(String entity, String id, String status, long rev) {
        public static final String APPLIED = "applied";
        public static final String CONFLICT_WON = "conflict_won";
        public static final String CONFLICT_LOST = "conflict_lost";
        public static final String MISSING_BLOB = "missing_blob";
    }

    final class PullPage extends EntityBag {
        public long cursor;
        public boolean hasMore;
    }
}
