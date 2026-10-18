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

        /**
         * Legacy page-file sources when no {@code .zdl} exists yet. Empty list is
         * fine (blank notebook). Null means migration must abort.
         */
        List<NotebookPaper.SourcePage> migrationSources(
                String notebookId, int pageHeight, int legacyPageHeight, long shortPagesSince);

        /**
         * After Ready {@code .zdl} install: clear BoardEntity {@code .zdi} ink for
         * this notebook so page files are migration residue only — not a second SoT.
         */
        void retireBoardEntityInk(String notebookId);

        /** After remote/local paper install: point cached boards at the new paper. */
        void bindPaperToBoards(String sheetId, NotebookPaper paper);

        /** False while live writer — push-only ink (no adopt over local). */
        boolean allowRemoteInkAdopt();
    }

    /**
     * Pulled log staged outside the lock (hash-checked + replayed once).
     * Apply under lock must not replay again.
     */
    record StagedLog(byte[] bytes, NotebookPaper paper) {
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
    /** One in-flight ensure per sheet — joins wait; never parallel full replays. */
    private final HashMap<String, EnsureFlight> ensuring = new HashMap<>();

    public InkSheetStore(Deps deps) {
        this.deps = deps;
    }

    /** True while this sheet's ensure is running (including joiners). */
    public boolean isEnsuring(String notebookId) {
        synchronized (deps.lock()) {
            return ensuring.containsKey(NotebookPaper.sheetId(notebookId));
        }
    }

    private static final class EnsureFlight {
        private boolean finished;
        private boolean result;

        synchronized void complete(boolean ok) {
            finished = true;
            result = ok;
            notifyAll();
        }

        synchronized boolean await() {
            while (!finished) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return result;
        }
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
     * Meta heal never queues; migration may queue. Single-flight per sheet:
     * concurrent callers join the same work (no parallel full replays).
     * Full {@link NotebookPaper#replay} only — no slices-before-strokes dual.
     */
    public boolean ensureSheet(String notebookId, int pageHeight, int legacyPageHeight,
                               long shortPagesSince) {
        if (pageHeight <= 0) {
            return false;
        }
        String sheetId = NotebookPaper.sheetId(notebookId);
        EnsureFlight mine = null;
        EnsureFlight join;
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
            join = ensuring.get(sheetId);
            if (join == null) {
                mine = new EnsureFlight();
                ensuring.put(sheetId, mine);
            }
        }
        if (join != null) {
            return join.await();
        }
        boolean ok = false;
        try {
            ok = ensureSheetBody(notebookId, sheetId, pageHeight, legacyPageHeight, shortPagesSince);
        } finally {
            mine.complete(ok);
            synchronized (deps.lock()) {
                if (ensuring.get(sheetId) == mine) {
                    ensuring.remove(sheetId);
                }
            }
        }
        return ok;
    }

    private boolean ensureSheetBody(String notebookId, String sheetId, int pageHeight,
                                    int legacyPageHeight, long shortPagesSince) {
        byte[] existing;
        try {
            existing = deps.ink().readLog(sheetId);
        } catch (IOException e) {
            Log.e(TAG, "ink log unreadable " + sheetId, e);
            existing = null;
        }
        if (existing != null) {
            try {
                RoomBoardRepository.logReplays.incrementAndGet();
                NotebookPaper paper = NotebookPaper.replay(existing);
                byte[] healed = paper.bytes();
                // Prefix heal (bad tail skipped): rewrite in place so the next open
                // is clean. Never queue — do not push a truncated body over cloud.
                if (healed.length < existing.length) {
                    deps.ink().writeLog(sheetId, healed);
                    LaunchLog.mark("healed ink log " + sheetId + " "
                            + existing.length + "→" + healed.length + " bytes");
                }
                installSheet(notebookId, sheetId, paper, healed.length, false, healed);
                return isSheetReady(notebookId);
            } catch (Error e) {
                // Legitimate huge replay / OOM: leave .zdl intact (no quarantine).
                // Caller escapes to empty chrome — never wipe the library.
                Log.e(TAG, "ink log replay Error; leaving file intact " + sheetId, e);
                LaunchLog.mark("ink log Error leave intact " + sheetId + ": " + e);
                return false;
            } catch (IOException | RuntimeException e) {
                // Header-level corruption: quarantine the file (keep bytes on disk),
                // then try page-file migration or empty Ready so *this* sheet opens
                // and other notebooks stay usable. Never wipe the library.
                Log.e(TAG, "ink log will not replay " + sheetId + "; quarantining", e);
                File quarantined = deps.ink().quarantineLog(sheetId);
                LaunchLog.mark("quarantined ink log " + sheetId
                        + (quarantined == null ? "" : (" → " + quarantined.getName())));
                List<NotebookPaper.SourcePage> recovered =
                        deps.migrationSources(notebookId, pageHeight, legacyPageHeight, shortPagesSince);
                if (recovered != null && !recovered.isEmpty()) {
                    if (installFromSources(notebookId, recovered, pageHeight, false)) {
                        return true;
                    }
                }
                return installEmptyReady(notebookId, pageHeight);
            }
        }
        synchronized (deps.lock()) {
            if (deps.dao().notebookLog(sheetId) != null) {
                // Meta says log-backed but file gone — open blank locally; cloud
                // blob untouched (queue=false). Hand-off only if user chooses later.
                LaunchLog.mark("ink log missing for " + sheetId + "; empty Ready heal");
                return installEmptyReady(notebookId, pageHeight);
            }
        }
        List<NotebookPaper.SourcePage> sources =
                deps.migrationSources(notebookId, pageHeight, legacyPageHeight, shortPagesSince);
        return installFromSources(notebookId, sources, pageHeight, true);
    }

    /**
     * Last-resort local open: blank Ready sheet so UI paints. Does not delete
     * quarantined {@code .zdl.corrupt.*} files and does not queue cloud push.
     */
    private boolean installEmptyReady(String notebookId, int pageHeight) {
        String sheetId = NotebookPaper.sheetId(notebookId);
        NotebookPaper empty = NotebookPaper.empty(pageHeight);
        byte[] bytes = empty.bytes();
        try {
            deps.ink().writeLog(sheetId, bytes);
        } catch (IOException e) {
            Log.e(TAG, "empty Ready heal write failed " + sheetId, e);
            return false;
        }
        installSheet(notebookId, sheetId, empty, bytes.length, false, bytes);
        return isSheetReady(notebookId);
    }

    /**
     * Write {@code .zdl} from {@link NotebookPaper.SourcePage}s and install Ready.
     * Shared by page-file migration ({@link #ensureSheet}) and Ready-direct
     * {@link RoomBoardRepository#importLegacy} — never writes {@code .zdi}.
     */
    boolean installFromSources(String notebookId, List<NotebookPaper.SourcePage> sources,
                               int pageHeight, boolean queue) {
        if (sources == null) {
            return false;
        }
        String sheetId = NotebookPaper.sheetId(notebookId);
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
        installSheet(notebookId, sheetId, readBack, bytes.length, queue, bytes);
        return isSheetReady(notebookId);
    }

    void installSheet(String notebookId, String sheetId, NotebookPaper paper, int flushed,
                      boolean queue) {
        installSheet(notebookId, sheetId, paper, flushed, queue, null);
    }

    /**
     * @param knownBytes when non-null (caller just read/replayed), skip a second
     *     multi-MB {@code readLog} of the same file
     */
    void installSheet(String notebookId, String sheetId, NotebookPaper paper, int flushed,
                      boolean queue, byte[] knownBytes) {
        byte[] bytes = knownBytes;
        if (bytes == null) {
            try {
                bytes = deps.ink().readLog(sheetId);
            } catch (IOException e) {
                return;
            }
        }
        if (bytes == null) {
            bytes = paper.bytes();
        }
        synchronized (deps.lock()) {
            OpenSheet open = new OpenSheet(sheetId, notebookId, paper, flushed);
            open.ready = true;
            sheets.put(sheetId, open);
            ensureNotebookLogMetaLocked(open, bytes, queue);
            if (queue && bytes != null) {
                deps.mirrorLog(sheetId, bytes);
            }
            // Ready owns ink — drop page-file dual left by importer / old tips.
            deps.retireBoardEntityInk(notebookId);
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
            bytes = staged == null ? null : staged.bytes();
        } else if (hasBody) {
            StagedLog staged = stagedLogs.remove(remote.id);
            if (staged != null && staged.bytes() != null && staged.paper() != null) {
                bytes = staged.bytes();
                paper = staged.paper();
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
                // Ready conflict shelf: pending local loser → hidden conflict_of
                // copy before primary replace (MetaPullApply.conflictCopy for .zdl).
                if (InkLogApply.shelvesLosingLocal(
                        decision,
                        deps.pendingLog(remote.id),
                        localInkHashForShelfLocked(local, open),
                        local == null ? null : local.deletedAt,
                        remote.inkHash,
                        remote.deletedAt)) {
                    shelfLosingLocalLogLocked(remote.id, local, open, mirrorOps);
                }
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
        // Catch-up log install: same retire as local ensure (boards may have
        // landed in an earlier pull before this tip reordered logs-first).
        deps.retireBoardEntityInk(remote.notebookId);
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
            // One post-write spine: meta via ensureNotebookLogMetaLocked, then mirror.
            ensureNotebookLogMetaLocked(open, all, true);
            deps.mirrorLog(sheetId, all);
        }
        return true;
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

    /** Writer thread. Appends the new tail of the log. False when append was needed and failed. */
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

    /**
     * Exception shelf for Ready: keep the losing local primary {@code .zdl} as a
     * hidden {@code conflict_of} copy before remote replaces the primary. Mirrors
     * {@link MetaPullApply}'s board {@code conflictCopy} so Hand-off / history
     * races do not drop ink once writers are Ready-only.
     */
    private void shelfLosingLocalLogLocked(String primaryId, NotebookLogEntity local,
                                           OpenSheet open, List<Runnable> mirrorOps)
            throws IOException {
        byte[] localBytes = null;
        if (open != null && open.ready && open.paper != null) {
            localBytes = open.paper.bytes();
        }
        if (localBytes == null || localBytes.length == 0) {
            localBytes = deps.ink().readLog(primaryId);
        }
        if (localBytes == null || localBytes.length == 0) {
            return;
        }
        NotebookLogEntity copy = local != null ? local.copy() : new NotebookLogEntity();
        copy.id = UUID.randomUUID().toString();
        copy.notebookId = local != null ? local.notebookId
                : (open != null ? open.notebookId : null);
        copy.inkHash = InkFileStore.sha256(localBytes);
        copy.inkBytes = localBytes.length;
        if (open != null && open.paper != null) {
            copy.sliceHeight = open.paper.sliceHeight;
        }
        copy.conflictOf = primaryId;
        copy.rev = 0;
        copy.deletedAt = null;
        if (copy.createdAt == 0L) {
            copy.createdAt = deps.clock().getAsLong();
        }
        if (copy.updatedAt == 0L) {
            copy.updatedAt = deps.clock().getAsLong();
        }
        deps.ink().writeLog(copy.id, localBytes);
        String id = copy.id;
        byte[] bytes = localBytes;
        mirrorOps.add(() -> deps.mirrorLog(id, bytes));
        deps.dao().upsertNotebookLog(copy);
        deps.queueLogUpsert(copy.id);
        LaunchLog.mark("shelved losing local log " + primaryId + " as " + copy.id);
    }

    /** Hash of the local primary body for {@link InkLogApply#shelvesLosingLocal}. */
    private String localInkHashForShelfLocked(NotebookLogEntity local, OpenSheet open) {
        if (local != null && local.inkHash != null) {
            return local.inkHash;
        }
        if (open != null && open.ready && open.paper != null) {
            byte[] bytes = open.paper.bytes();
            if (bytes != null && bytes.length > 0) {
                return InkFileStore.sha256(bytes);
            }
        }
        return null;
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
