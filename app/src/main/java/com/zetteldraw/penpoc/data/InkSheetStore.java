package com.zetteldraw.penpoc.data;

import android.util.Log;

import com.zetteldraw.penpoc.LaunchLog;
import com.zetteldraw.penpoc.data.db.NotebookEntity;
import com.zetteldraw.penpoc.data.db.NotebookLogEntity;
import com.zetteldraw.penpoc.data.db.OutboxEntry;
import com.zetteldraw.penpoc.data.db.ZettelDao;
import com.zetteldraw.penpoc.data.db.ZettelDatabase;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Device authority for one notebook ink log: on-disk {@code .zdl} + Ready
 * {@link OpenSheet}. Open, remote apply, length policy, and sheet disk write
 * (rewrite / flush / holdFlush / reload) live here so those paths can be
 * reasoned about without walking {@link RoomBoardRepository}.
 *
 * <p>{@link RoomBoardRepository} still owns UI board projection, Move/tear
 * orchestration, and meta sync — it holds the shared lock and implements
 * {@link Deps}. Temporary LOC rise is fine; the win is a single SoT module
 * for sheet length and disk I/O.
 *
 * @see InkLogApply
 * @see docs/ink-sot-rewrite.md
 */
public final class InkSheetStore {
    private static final String TAG = "zd-sheet";

    /** Bridge into Room / outbox / mirror owned by the repository. */
    public interface Deps {
        Object lock();

        ZettelDao dao();

        ZettelDatabase db();

        InkFileStore ink();

        LongSupplier clock();

        boolean pendingLog(String sheetId);

        void queueLogUpsert(String sheetId);

        void mirrorLog(String sheetId, byte[] bytes);

        /** Bump pull-refresh skip counter after a local sheet disk write. */
        void noteLocalSheetEdit(String sheetId);

        /**
         * Legacy page-file sources when no {@code .zdl} exists yet. Empty list is
         * fine (blank notebook). Null means migration must abort.
         */
        List<NotebookPaper.SourcePage> migrationSources(
                String notebookId, int pageHeight, int legacyPageHeight, long shortPagesSince);

        /** After remote/local paper install: point cached boards at the new paper. */
        void bindPaperToBoards(String sheetId, NotebookPaper paper);

        /** False while live writer — push-only ink (no adopt over local). */
        boolean allowRemoteInkAdopt();
    }

    /**
     * Pulled log staged outside the lock (hash-checked + replayed once).
     * Same idea as {@link MetaPullApply.Staged} for legacy board ink — apply
     * under lock must not replay again.
     */
    static final class StagedLog {
        final byte[] bytes;
        final NotebookPaper paper;

        StagedLog(byte[] bytes, NotebookPaper paper) {
            this.bytes = bytes;
            this.paper = paper;
        }
    }

    /**
     * In-memory Ready (or loading) sheet. Package-visible so Move/tear in the
     * repository can call holdFlush / rewrite under the same lock.
     */
    static final class OpenSheet {
        final String sheetId;
        final String notebookId;
        NotebookPaper paper;
        int flushed;
        boolean ready;
        /** Bumped when {@link #paper} is replaced so stale rewrite/flush snapshots abort. */
        int generation;
        /**
         * When true, flush is a no-op. Set around cross-notebook tear so a queued
         * {@code saveInk} cannot persist the source tear before dest commit.
         */
        boolean holdFlush;

        OpenSheet(String sheetId, String notebookId, NotebookPaper paper, int flushed) {
            this.sheetId = sheetId;
            this.notebookId = notebookId;
            this.paper = paper;
            this.flushed = flushed;
        }
    }

    private final Deps deps;
    private final HashMap<String, OpenSheet> sheets = new HashMap<>();

    public InkSheetStore(Deps deps) {
        this.deps = deps;
    }

    OpenSheet get(String sheetId) {
        return sheets.get(sheetId);
    }

    Iterable<Map.Entry<String, OpenSheet>> entrySet() {
        return sheets.entrySet();
    }

    void remove(String sheetId) {
        sheets.remove(sheetId);
    }

    public boolean isSheetReady(String notebookId) {
        synchronized (deps.lock()) {
            OpenSheet sheet = sheets.get(NotebookPaper.sheetId(notebookId));
            return sheet != null && sheet.ready;
        }
    }

    /** Ready paper for projection, or null when not Ready. */
    public NotebookPaper readyPaper(String notebookId) {
        synchronized (deps.lock()) {
            OpenSheet sheet = sheets.get(NotebookPaper.sheetId(notebookId));
            if (sheet != null && sheet.ready && sheet.paper != null) {
                return sheet.paper;
            }
            return null;
        }
    }

    /** Row or on-disk file — either means BoardEntity length is not SoT. */
    public boolean isLogBacked(String sheetId) {
        synchronized (deps.lock()) {
            NotebookLogEntity row = deps.dao().notebookLog(sheetId);
            return (row != null && row.deletedAt == null) || deps.ink().hasLog(sheetId);
        }
    }

    public void invalidate(String notebookId) {
        synchronized (deps.lock()) {
            sheets.remove(NotebookPaper.sheetId(notebookId));
        }
    }

    /**
     * Switch this notebook onto its ink log. Returns true when Ready afterward.
     * Meta heal never queues; migration may queue.
     */
    public boolean ensureSheet(String notebookId, int pageHeight, int legacyPageHeight,
                               long shortPagesSince) {
        if (pageHeight <= 0) {
            return false;
        }
        String sheetId = NotebookPaper.sheetId(notebookId);
        synchronized (deps.lock()) {
            OpenSheet open = sheets.get(sheetId);
            if (open != null && open.ready) {
                return true;
            }
            if (notebookId != null) {
                NotebookEntity notebook = deps.dao().notebook(notebookId);
                if (notebook == null || notebook.deletedAt != null) {
                    return false;
                }
            }
        }
        byte[] existing;
        try {
            existing = deps.ink().readLog(sheetId);
        } catch (IOException e) {
            Log.e(TAG, "ink log unreadable " + sheetId, e);
            return false;
        }
        if (existing != null) {
            try {
                RoomBoardRepository.logReplays.incrementAndGet();
                installSheet(notebookId, sheetId, NotebookPaper.replay(existing), existing.length, false);
                return isSheetReady(notebookId);
            } catch (IOException e) {
                Log.e(TAG, "ink log will not replay " + sheetId, e);
                return false;
            }
        }
        synchronized (deps.lock()) {
            if (deps.dao().notebookLog(sheetId) != null) {
                LaunchLog.mark("ink log missing for " + sheetId + "; page files left as they are");
                return false;
            }
        }
        List<NotebookPaper.SourcePage> sources =
                deps.migrationSources(notebookId, pageHeight, legacyPageHeight, shortPagesSince);
        if (sources == null) {
            return false;
        }
        RoomBoardRepository.logReplays.incrementAndGet();
        NotebookPaper built = NotebookPaper.migrate(sources, pageHeight);
        NotebookPaper readBack;
        try {
            readBack = NotebookPaper.replay(built.bytes());
        } catch (IOException e) {
            Log.e(TAG, "migration log will not replay " + sheetId, e);
            return false;
        }
        if (!NotebookPaper.readsBack(sources, built) || !NotebookPaper.readsBack(sources, readBack)) {
            Log.e(TAG, "migration refused for " + sheetId + "; page files left untouched");
            return false;
        }
        byte[] bytes = built.bytes();
        try {
            deps.ink().writeLog(sheetId, bytes);
            byte[] onDisk = deps.ink().readLog(sheetId);
            if (onDisk == null || !java.util.Arrays.equals(bytes, onDisk)) {
                Log.e(TAG, "migration read-back mismatch " + sheetId);
                return false;
            }
            readBack = NotebookPaper.replay(onDisk);
        } catch (IOException e) {
            Log.e(TAG, "migration write failed " + sheetId, e);
            return false;
        }
        if (!NotebookPaper.readsBack(sources, readBack)) {
            Log.e(TAG, "migration disk read-back refused " + sheetId);
            return false;
        }
        installSheet(notebookId, sheetId, readBack, bytes.length, true);
        return isSheetReady(notebookId);
    }

    void installSheet(String notebookId, String sheetId, NotebookPaper paper, int flushed, boolean queue) {
        synchronized (deps.lock()) {
            OpenSheet open = new OpenSheet(sheetId, notebookId, paper, flushed);
            open.ready = true;
            sheets.put(sheetId, open);
            byte[] bytes;
            try {
                bytes = deps.ink().readLog(sheetId);
            } catch (IOException e) {
                return;
            }
            if (bytes == null) {
                bytes = paper.bytes();
            }
            ensureNotebookLogMetaLocked(open, bytes, queue);
            if (queue && bytes != null) {
                deps.mirrorLog(sheetId, bytes);
            }
        }
    }

    /**
     * Apply a pulled notebook log. Caller holds {@link Deps#lock()}. All body
     * installs go through {@link InkLogApply#decide}. {@code stagedLogs} was
     * replayed once outside the lock — do not replay again here.
     */
    void applyRemoteLogLocked(NotebookLogEntity remote, Map<String, StagedLog> stagedLogs,
                              List<Runnable> mirrorOps) throws IOException {
        ZettelDao dao = deps.dao();
        NotebookLogEntity local = dao.notebookLog(remote.id);
        if (local != null && local.deletedAt != null) {
            local = null;
        }
        OpenSheet open = sheets.get(remote.id);
        if (remote.conflictOf == null
                && local == null && open != null && open.ready && open.paper != null) {
            local = ensureNotebookLogMetaLocked(open, open.paper.bytes(), false);
        }

        boolean conflictCopy = remote.conflictOf != null;
        // Same omitted-blob rule as MetaPullApply boards: hash match ⇒ local body is SoT.
        boolean hasBody = !conflictCopy && remote.deletedAt == null && remote.inkHash != null;
        byte[] bytes = null;
        NotebookPaper paper = null;
        int localSlices = -1;
        int remoteSlices = -1;
        long diskBytes = 0L;
        if (conflictCopy) {
            StagedLog staged = stagedLogs.remove(remote.id);
            bytes = staged == null ? null : staged.bytes;
        } else if (hasBody) {
            StagedLog staged = stagedLogs.remove(remote.id);
            if (staged != null && staged.bytes != null && staged.paper != null) {
                bytes = staged.bytes;
                paper = staged.paper;
                localSlices = localReadySliceCountLocked(remote.id, open);
                diskBytes = localInkLogBytes(remote.id);
                remoteSlices = paper.sliceCount();
            } else if (local != null && remote.inkHash.equals(local.inkHash)) {
                hasBody = false;
            } else {
                throw new IOException("bad ink log for " + remote.id);
            }
        }

        boolean holdFlush = open != null && open.ready && open.holdFlush;
        boolean unflushed = open != null && open.ready && open.paper != null
                && open.paper.bytes().length > open.flushed;
        InkLogApply.Decision decision = InkLogApply.decide(new InkLogApply.Facts(
                conflictCopy,
                remote.deletedAt != null,
                hasBody,
                deps.allowRemoteInkAdopt(),
                localSlices,
                remoteSlices,
                diskBytes,
                bytes == null ? 0L : bytes.length,
                local == null ? null : local.rev,
                remote.rev,
                holdFlush,
                unflushed,
                deps.pendingLog(remote.id),
                local == null ? 0L : local.updatedAt,
                remote.updatedAt));

        switch (decision) {
            case SHELVE_CONFLICT_COPY:
                if (bytes != null) {
                    deps.ink().writeLog(remote.id, bytes);
                    byte[] copy = bytes;
                    mirrorOps.add(() -> deps.mirrorLog(remote.id, copy));
                }
                upsertLogClearOutbox(remote);
                return;
            case REFUSE_SHORTER:
                LaunchLog.mark("pull refused shorter log for " + remote.id
                        + " remoteSlices=" + remoteSlices
                        + " localSlices=" + localSlices);
                shelfShorterRemoteLogLocked(remote, bytes, mirrorOps);
                ensureLocalLogQueuedLocked(remote.id, open);
                return;
            case KEEP_LIVE_LOCAL:
                LaunchLog.mark("live writer keeps local log " + remote.id
                        + " remoteSlices=" + remoteSlices
                        + " localSlices=" + localSlices
                        + " remoteBytes=" + (bytes == null ? 0 : bytes.length)
                        + " diskBytes=" + diskBytes);
                ensureLocalLogQueuedLocked(remote.id, open);
                return;
            case ADOPT_LONGER:
                LaunchLog.mark("pull adopts longer log for " + remote.id
                        + " remoteSlices=" + remoteSlices
                        + " localSlices=" + localSlices
                        + " remoteBytes=" + bytes.length
                        + " diskBytes=" + diskBytes);
                // fall through — same primary-body install as LWW_REPLACE
            case LWW_REPLACE:
                installRemotePaperLocked(remote, paper, bytes, open, mirrorOps);
                upsertLogClearOutbox(remote);
                return;
            case SKIP_REV:
            case SKIP_HOLD_FLUSH:
            case SKIP_UNFLUSHED:
            case SKIP_PENDING_NEWER:
                return;
            case UPSERT_META_ONLY:
                upsertLogClearOutbox(remote);
                return;
            default:
                throw new IllegalStateException("unhandled InkLogApply decision " + decision);
        }
    }

    private void upsertLogClearOutbox(NotebookLogEntity remote) {
        deps.dao().upsertNotebookLog(remote);
        deps.dao().deleteOutbox(OutboxEntry.LOG, remote.id);
    }

    private void installRemotePaperLocked(NotebookLogEntity remote, NotebookPaper paper, byte[] bytes,
                                          OpenSheet open, List<Runnable> mirrorOps) throws IOException {
        // Disk guard: never shrink a longer local .zdl (Apply should already refuse).
        long disk = localInkLogBytes(remote.id);
        if (disk > 0 && bytes != null && bytes.length < disk) {
            throw new IOException("refused shorter .zdl write for " + remote.id
                    + " remoteBytes=" + bytes.length + " diskBytes=" + disk);
        }
        deps.ink().writeLog(remote.id, bytes);
        mirrorOps.add(() -> deps.mirrorLog(remote.id, bytes));
        if (open == null) {
            open = new OpenSheet(remote.id, remote.notebookId, paper, bytes.length);
            sheets.put(remote.id, open);
        } else {
            open.paper = paper;
            open.flushed = bytes.length;
            open.generation++;
        }
        open.ready = true;
        deps.bindPaperToBoards(remote.id, paper);
    }

    private int localReadySliceCountLocked(String sheetId, OpenSheet open) {
        if (open != null && open.ready && open.paper != null) {
            return open.paper.sliceCount();
        }
        try {
            byte[] existing = deps.ink().readLog(sheetId);
            if (existing == null || existing.length == 0) {
                return -1;
            }
            return NotebookPaper.replay(existing).sliceCount();
        } catch (IOException e) {
            Log.e(TAG, "local log slice count failed " + sheetId, e);
            return -1;
        }
    }

    /** On-disk {@code .zdl} length without loading the file (Scratchpad can be multi-MB). */
    long localInkLogBytes(String sheetId) {
        File file = sheetId == null ? null : deps.ink().logFile(sheetId);
        return file != null && file.isFile() ? file.length() : 0L;
    }

    /** Disk hash of the primary {@code .zdl}, or empty when missing/unreadable. */
    public String localInkLogHash(String sheetId) {
        File file = sheetId == null ? null : deps.ink().logFile(sheetId);
        if (file == null || !file.isFile() || file.length() == 0) {
            return "";
        }
        try {
            return InkFileStore.sha256File(file);
        } catch (IOException e) {
            Log.e(TAG, "local ink log hash failed " + sheetId, e);
            return "";
        }
    }

    /**
     * Writer thread. Replaces the whole on-disk log (reorder / Move dest-first
     * rewrite). Resets {@code flushed} so a later append starts from the new end.
     *
     * @return false when the sheet could not be written; caller must not tear
     *     the source on disk (and should reload memory from disk).
     */
    boolean rewriteSheetOrFalse(String sheetId) {
        if (sheetId == null) {
            return false;
        }
        byte[] all;
        int generation;
        synchronized (deps.lock()) {
            OpenSheet open = sheets.get(sheetId);
            if (open == null || !open.ready) {
                return false;
            }
            all = open.paper.bytes();
            generation = open.generation;
        }
        try {
            deps.ink().writeLog(sheetId, all);
        } catch (IOException e) {
            Log.e(TAG, "ink log rewrite failed " + sheetId, e);
            LaunchLog.mark("ink log rewrite failed " + sheetId + ": " + e.getMessage());
            return false;
        }
        synchronized (deps.lock()) {
            OpenSheet open = sheets.get(sheetId);
            if (open == null) {
                return false;
            }
            // A pull/reconcile replaced paper while we held a stale snapshot —
            // do not stamp short meta or leave the long disk overwritten without repair.
            if (open.generation != generation) {
                LaunchLog.mark("rewrite aborted; sheet generation advanced " + sheetId);
                try {
                    deps.ink().writeLog(sheetId, open.paper.bytes());
                    open.flushed = open.paper.bytes().length;
                } catch (IOException e) {
                    Log.e(TAG, "rewrite repair failed " + sheetId, e);
                    return false;
                }
                return true;
            }
            open.flushed = all.length;
            // One post-write spine: meta via ensureNotebookLogMetaLocked, then mirror + local-edit.
            ensureNotebookLogMetaLocked(open, all, true);
            deps.mirrorLog(sheetId, all);
            deps.noteLocalSheetEdit(sheetId);
        }
        return true;
    }

    /** Writer thread. Appends the new tail of the log; it does not rewrite the prefix. */
    void flushSheet(String sheetId) {
        flushSheetOrFalse(sheetId);
    }

    /**
     * Hold or release flush for one sheet. Place/Move holds both sheets until
     * dest-first persist finishes (or reloads from disk on failure).
     */
    void setSheetFlushHeld(String sheetId, boolean held) {
        if (sheetId == null) {
            return;
        }
        synchronized (deps.lock()) {
            OpenSheet open = sheets.get(sheetId);
            if (open != null) {
                open.holdFlush = held;
            }
        }
    }

    /** Same hold bit for source + dest (place/Move pair). */
    void setPairFlushHeld(String sourceSheetId, String destSheetId, boolean held) {
        setSheetFlushHeld(sourceSheetId, held);
        setSheetFlushHeld(destSheetId, held);
    }

    /** @return false when an append was needed and failed. */
    boolean flushSheetOrFalse(String sheetId) {
        if (sheetId == null) {
            return false;
        }
        byte[] suffix;
        int flushed;
        synchronized (deps.lock()) {
            OpenSheet open = sheets.get(sheetId);
            if (open == null || !open.ready) {
                return false;
            }
            if (open.holdFlush) {
                // Cross-notebook place/move is in flight: do not append a torn
                // source (or a rewritten dest suffix) ahead of dest-first persist.
                return true;
            }
            byte[] all = open.paper.bytes();
            if (all.length <= open.flushed) {
                return true;
            }
            suffix = java.util.Arrays.copyOfRange(all, open.flushed, all.length);
            flushed = all.length;
        }
        try {
            deps.ink().appendLog(sheetId, suffix);
        } catch (IOException e) {
            Log.e(TAG, "ink log append failed " + sheetId, e);
            LaunchLog.mark("ink log append failed " + sheetId + ": " + e.getMessage());
            return false;
        }
        byte[] all;
        try {
            all = deps.ink().readLog(sheetId);
        } catch (IOException e) {
            Log.e(TAG, "ink log read failed " + sheetId, e);
            return false;
        }
        if (all == null) {
            return false;
        }
        String hash = InkFileStore.sha256(all);
        synchronized (deps.lock()) {
            OpenSheet open = sheets.get(sheetId);
            if (open == null) {
                return false;
            }
            open.flushed = Math.max(open.flushed, flushed);
            NotebookLogEntity row = deps.dao().notebookLog(sheetId);
            // Append already on disk; skip Room/outbox/mirror when meta already matches.
            if (row != null && java.util.Objects.equals(row.inkHash, hash)) {
                return true;
            }
            ensureNotebookLogMetaLocked(open, all, true);
            deps.mirrorLog(sheetId, all);
            deps.noteLocalSheetEdit(sheetId);
        }
        return true;
    }

    /**
     * Replace an open sheet's in-memory paper with the on-disk log. Used when a
     * cross-notebook dest write fails after an in-memory tear: disk still has
     * the page on the source, so prefer that over a stuck holdFlush.
     */
    void reloadSheetFromDisk(String sheetId, int fallbackSliceHeight) {
        if (sheetId == null) {
            return;
        }
        byte[] existing;
        try {
            existing = deps.ink().readLog(sheetId);
        } catch (IOException e) {
            Log.e(TAG, "reload sheet failed " + sheetId, e);
            return;
        }
        synchronized (deps.lock()) {
            OpenSheet open = sheets.get(sheetId);
            if (open == null) {
                return;
            }
            try {
                if (existing == null || existing.length == 0) {
                    open.paper = NotebookPaper.empty(
                            open.paper != null && open.paper.sliceHeight > 0
                                    ? open.paper.sliceHeight
                                    : Math.max(1, fallbackSliceHeight));
                    open.flushed = open.paper.bytes().length;
                } else {
                    open.paper = NotebookPaper.replay(existing);
                    open.flushed = existing.length;
                }
            } catch (IOException e) {
                Log.e(TAG, "reload sheet replay failed " + sheetId, e);
            }
        }
    }

    private void shelfShorterRemoteLogLocked(NotebookLogEntity remote, byte[] remoteBytes,
                                            List<Runnable> mirrorOps) throws IOException {
        NotebookLogEntity copy = remote.copy();
        copy.id = UUID.randomUUID().toString();
        copy.conflictOf = remote.id;
        copy.rev = 0;
        copy.deletedAt = null;
        deps.ink().writeLog(copy.id, remoteBytes);
        String id = copy.id;
        byte[] bytes = remoteBytes;
        mirrorOps.add(() -> deps.mirrorLog(id, bytes));
        deps.dao().upsertNotebookLog(copy);
        deps.queueLogUpsert(copy.id);
    }

    private void ensureLocalLogQueuedLocked(String sheetId, OpenSheet open) {
        if (open != null && open.ready && open.paper != null) {
            ensureNotebookLogMetaLocked(open, open.paper.bytes(), true);
            return;
        }
        NotebookLogEntity row = deps.dao().notebookLog(sheetId);
        if (row != null && row.conflictOf == null && row.deletedAt == null) {
            deps.queueLogUpsert(sheetId);
        }
    }

    /**
     * Upserts live {@code notebook_logs} metadata. Queues only when {@code queue}
     * is true — never merely because the row is new.
     */
    NotebookLogEntity ensureNotebookLogMetaLocked(OpenSheet open, byte[] bytes, boolean queue) {
        if (open == null || open.paper == null) {
            return null;
        }
        long now = deps.clock().getAsLong();
        NotebookLogEntity row = deps.dao().notebookLog(open.sheetId);
        if (row == null) {
            row = new NotebookLogEntity();
            row.id = open.sheetId;
            row.notebookId = open.notebookId;
            row.createdAt = now;
            row.rev = 0;
        }
        row.inkHash = bytes == null ? null : InkFileStore.sha256(bytes);
        row.inkBytes = bytes == null ? 0 : bytes.length;
        row.sliceHeight = open.paper.sliceHeight;
        if (queue) {
            row.updatedAt = Math.max(row.updatedAt, now);
        } else if (row.updatedAt == 0L) {
            row.updatedAt = now;
        }
        row.deletedAt = null;
        row.conflictOf = null;
        NotebookLogEntity stored = row;
        deps.db().runInTransaction(() -> {
            deps.dao().upsertNotebookLog(stored);
            if (queue) {
                deps.queueLogUpsert(stored.id);
            }
        });
        return stored;
    }
}
