package com.zetteldraw.penpoc.data;

import android.util.Log;

import com.zetteldraw.penpoc.Board;
import com.zetteldraw.penpoc.InkRenderer;
import com.zetteldraw.penpoc.LaunchLog;
import com.zetteldraw.penpoc.Notebook;
import com.zetteldraw.penpoc.data.db.BoardEntity;
import com.zetteldraw.penpoc.data.db.NotebookEntity;
import com.zetteldraw.penpoc.data.db.NotebookLogEntity;
import com.zetteldraw.penpoc.data.db.OutboxEntry;
import com.zetteldraw.penpoc.data.db.PageLinkEntity;
import com.zetteldraw.penpoc.data.db.SyncState;
import com.zetteldraw.penpoc.data.db.ZettelDao;
import com.zetteldraw.penpoc.data.db.ZettelDatabase;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * Room + ink files. One lock guards the database rows, the ink directory's
 * committed files and the shared {@link Board} cache, and it is only ever
 * held for short metadata work: no ink encoding, no fsync'd data write, no
 * mirror I/O and no network happens while it is held.
 *
 * <p>Ink-log <em>open / remote apply / length / disk write</em> live in
 * {@link InkSheetStore} (+ {@link InkLogApply}). Notebook / link / legacy
 * board pull LWW lives in {@link MetaPullApply}. This class keeps board
 * projection, <em>one</em> Move/Reorder spine ({@link #placePageBefore}), and pull
 * orchestration.
 *
 * <p>Ink saves are Ready-only write-behind: {@code page.paper != null} queues
 * {@link InkSheetStore#flushSheetOrFalse}. There is no BoardEntity {@code .zdi}
 * write-behind. Unmigrated page files feed {@link #migrationSourcesForSheet}
 * once; after Ready install {@link #retireBoardEntityInkLocked} clears them.
 * {@link MetaPullApply} board LWW applies only while not log-backed.
 */
public final class RoomBoardRepository implements BoardRepository, SyncStore {
    /**
     * Calls that read a notebook's pages or open its ink log.
     * Pressing Link must not move this: that work is what froze the screen.
     */
    public static final AtomicInteger notebookReads = new AtomicInteger();

    /**
     * Ink logs actually replayed. A cache hit does not count: the first Link
     * press used to pay this, and every press after it was cheap.
     */
    public static final AtomicInteger logReplays = new AtomicInteger();
    private static final String TAG = "zd-repo";
    private static final byte[] MIRROR_DELETE = new byte[0];

    private final ZettelDatabase db;
    private final ZettelDao dao;
    private final InkFileStore ink;
    private final InkMirror mirror;
    private final Executor writer;
    private final Executor mirrorExecutor;
    private final Executor ui;
    private final LongSupplier clock;

    private final Object lock = new Object();
    private final HashMap<String, Board> cache = new HashMap<>();
    private final AtomicBoolean indexPending = new AtomicBoolean();
    /**
     * Unsaved trailing blank per list ({@link #listKey}), owned by Ready
     * {@link #projectLocked} — never seeded from BoardEntity inkHash walks.
     */
    private final HashMap<String, Board> trailingBlanks = new HashMap<>();
    /** Count of local saves per board, never reset; a pull refresh skips boards edited since it decided. */
    private final ConcurrentHashMap<String, Long> localEdits = new ConcurrentHashMap<>();
    private long lastQueuedAt;
    private final CopyOnWriteArrayList<Runnable> remoteListeners = new CopyOnWriteArrayList<>();
    private final Object refreshLock = new Object();
    /** Pulled strokes not yet put on screen, newest per board; delivered in one UI turn. */
    private final LinkedHashMap<String, Refresh> pendingRefresh = new LinkedHashMap<>();
    private int remoteBatchDepth;
    private boolean refreshChanged;
    private boolean refreshPosted;

    private final Object mirrorLock = new Object();
    /** Latest bytes per board still to mirror ({@link #MIRROR_DELETE} = delete); a slow mirror coalesces. */
    private final LinkedHashMap<String, byte[]> mirrorQueue = new LinkedHashMap<>();
    private boolean mirrorDraining;

    /**
     * Ink-log sheet SoT (Ready + remote apply + length + disk write). Move/tear
     * orchestration still calls into it under {@link #lock}.
     */
    private final InkSheetStore sheetStore;
    /** Notebook / link / legacy board meta LWW during pull (exception shelf). */
    private final MetaPullApply metaPull;
    private int knownPageHeight;
    private int knownLegacyHeight;
    private long knownShortPagesSince;
    /** Sync-pass ink policy; default catch-up so unit tests can adopt. */
    private volatile SyncStore.InkPullPolicy inkPullPolicy = SyncStore.InkPullPolicy.CATCH_UP;

    public RoomBoardRepository(ZettelDatabase db, InkFileStore ink, InkMirror mirror,
                               Executor background, Executor ui, LongSupplier clock) {
        this(db, ink, mirror, background, background, ui, clock);
    }

    /**
     * {@code writer} must run tasks one at a time in order (a single thread);
     * it owns ink writes. {@code mirrorExecutor} runs the Documents mirror and
     * index, which may be much slower than app-private storage.
     */
    public RoomBoardRepository(ZettelDatabase db, InkFileStore ink, InkMirror mirror,
                               Executor writer, Executor mirrorExecutor, Executor ui, LongSupplier clock) {
        this.db = db;
        this.dao = db.dao();
        this.ink = ink;
        this.mirror = mirror;
        this.writer = writer;
        this.mirrorExecutor = mirrorExecutor;
        this.ui = ui;
        this.clock = clock;
        synchronized (lock) {
            lastQueuedAt = dao.maxQueuedAt();
            db.runInTransaction(this::seedNotebooks);
            // Trailing blank is Ready/projectLocked-owned — no BoardEntity seed blank.
            int inked = 0;
            List<BoardEntity> live = dao.liveBoardsForIndex();
            for (BoardEntity row : live) {
                if (row.inkHash != null) {
                    inked++;
                }
            }
            SyncState state = dao.syncState();
            LaunchLog.mark("storage ready: " + dao.liveNotebooks().size() + " notebooks, " + live.size()
                    + " pages (" + inked + " inked), outbox " + dao.outboxCount() + ", sync cursor "
                    + (state == null ? "none" : state.cursor));
        }
        this.sheetStore = new InkSheetStore(new SheetDeps());
        this.metaPull = new MetaPullApply(new MetaDeps());
    }

    /** Bridge for {@link MetaPullApply} — Room/outbox/mirror/cache stay here. */
    private final class MetaDeps implements MetaPullApply.Deps {
        @Override public ZettelDao dao() { return RoomBoardRepository.this.dao; }
        @Override public InkFileStore ink() { return RoomBoardRepository.this.ink; }
        @Override public boolean pendingOutbox(String entity, String id) {
            return pendingLocked(entity, id);
        }
        @Override public boolean hasPendingInk(String boardId) {
            // BoardEntity write-behind is gone; in-flight .zdi snapshots no longer exist.
            return false;
        }
        @Override public void queueBoardUpsert(String boardId) {
            queueLocked(OutboxEntry.BOARD, boardId, OutboxEntry.UPSERT);
        }
        @Override public void mirrorInk(String boardId, byte[] bytes) {
            RoomBoardRepository.this.mirrorInk(boardId, bytes);
        }
        @Override public void removeCachedBoard(String boardId) {
            cache.remove(boardId);
        }
        @Override public boolean notebookLogBacked(String notebookId) {
            String sheetId = NotebookPaper.sheetId(notebookId);
            return dao.notebookLog(sheetId) != null || ink.hasLog(sheetId);
        }
    }

    /** Bridge for {@link InkSheetStore} — keeps Room/outbox/mirror in this class. */
    private final class SheetDeps implements InkSheetStore.Deps {
        @Override public Object lock() { return RoomBoardRepository.this.lock; }
        @Override public ZettelDao dao() { return RoomBoardRepository.this.dao; }
        @Override public ZettelDatabase db() { return RoomBoardRepository.this.db; }
        @Override public InkFileStore ink() { return RoomBoardRepository.this.ink; }
        @Override public LongSupplier clock() { return RoomBoardRepository.this.clock; }
        @Override public boolean pendingLog(String sheetId) {
            return pendingLocked(OutboxEntry.LOG, sheetId);
        }
        @Override public void queueLogUpsert(String sheetId) {
            queueLocked(OutboxEntry.LOG, sheetId, OutboxEntry.UPSERT);
        }
        @Override public void mirrorLog(String sheetId, byte[] bytes) {
            RoomBoardRepository.this.mirrorLog(sheetId, bytes);
        }
        @Override public void noteLocalSheetEdit(String sheetId) {
            localEdits.merge(sheetId, 1L, Long::sum);
        }
        @Override public boolean allowRemoteInkAdopt() {
            return RoomBoardRepository.this.allowRemoteInkAdopt();
        }
        @Override
        public List<NotebookPaper.SourcePage> migrationSources(
                String notebookId, int pageHeight, int legacyPageHeight, long shortPagesSince) {
            return migrationSourcesForSheet(notebookId, pageHeight, legacyPageHeight, shortPagesSince);
        }
        @Override
        public void retireBoardEntityInk(String notebookId) {
            retireBoardEntityInkLocked(notebookId);
        }
        @Override
        public void bindPaperToBoards(String sheetId, NotebookPaper paper) {
            for (Board board : cache.values()) {
                if (sheetId.equals(board.sheetId)) {
                    board.paper = paper;
                }
            }
            for (Board blank : trailingBlanks.values()) {
                if (sheetId.equals(blank.sheetId)) {
                    blank.paper = paper;
                }
            }
        }
    }

    // region BoardRepository

    @Override
    public List<NotebookInfo> notebooks() {
        synchronized (lock) {
            ArrayList<NotebookInfo> result = new ArrayList<>();
            for (NotebookEntity row : dao.liveNotebooks()) {
                result.add(new NotebookInfo(row.id, row.title, row.parentId));
            }
            return result;
        }
    }

    /**
     * Ready projection only (greenfield L4). Empty until {@link #ensureSheet}
     * installs a Ready sheet. Legacy BoardEntity rows are migration sources for
     * first open — never a paint/list SoT.
     */
    @Override
    public List<Board> pages(String notebookId) {
        notebookReads.incrementAndGet();
        synchronized (lock) {
            if (notebookId != null) {
                NotebookEntity notebook = dao.notebook(notebookId);
                if (notebook == null || notebook.deletedAt != null) {
                    return new ArrayList<>();
                }
            }
            InkSheetStore.OpenSheet sheet = sheetStore.get(NotebookPaper.sheetId(notebookId));
            if (sheet != null && sheet.ready) {
                return projectLocked(notebookId, sheet.paper);
            }
            return new ArrayList<>();
        }
    }

    @Override
    public boolean isSheetReady(String notebookId) {
        return sheetStore.isSheetReady(notebookId);
    }

    /**
     * Ready-only: queue {@link InkSheetStore#flushSheetOrFalse} when
     * {@code page.paper != null}. Paper-null calls are a no-op (no BoardEntity
     * {@code .zdi} write-behind) — UI + Move/tear already hold paper on open.
     */
    @Override
    public void saveInk(Board page) {
        if (page == null) {
            return;
        }
        if (page.paper == null) {
            LaunchLog.mark("saveInk refused; not Ready (no BoardEntity write-behind)");
            return;
        }
        writer.execute(() -> sheetStore.flushSheetOrFalse(page.sheetId));
    }

    /**
     * Synchronous BoardEntity {@code .zdi} persist for importer-style seeds and
     * conflict-shelf tests. Not a production writer — {@link #saveInk} is Ready
     * flush only.
     */
    void persistBoardEntityInk(Board page) throws IOException {
        if (page == null) {
            return;
        }
        synchronized (lock) {
            List<InkRenderer.InkStroke> snapshot =
                    Collections.unmodifiableList(new ArrayList<>(page.strokes));
            BoardEntity row = dao.board(page.id);
            if (row == null) {
                if (snapshot.isEmpty()) {
                    return;
                }
                String key = listOfBlankLocked(page.id);
                if (key != null) {
                    trailingBlanks.remove(key);
                }
                row = new BoardEntity();
                row.id = page.id;
                row.notebookId = key == null ? null : notebookOfKey(key);
                row.position = Positions.after(lastPositionLocked(row.notebookId));
                row.createdAt = page.createdAt;
                cache.put(page.id, page);
            } else if (row.deletedAt != null) {
                return;
            }
            byte[] bytes = snapshot.isEmpty() ? null : InkCodec.encode(snapshot);
            String hash = bytes == null ? null : InkFileStore.sha256(bytes);
            if (Objects.equals(row.inkHash, hash)) {
                return;
            }
            writeInkLocked(page.id, bytes);
            row.inkHash = hash;
            row.inkBytes = bytes == null ? 0 : bytes.length;
            row.updatedAt = clock.getAsLong();
            localEdits.merge(page.id, 1L, Long::sum);
            BoardEntity toSave = row;
            db.runInTransaction(() -> {
                dao.upsertBoard(toSave);
                queueLocked(OutboxEntry.BOARD, toSave.id, OutboxEntry.UPSERT);
            });
            // No BoardEntity trailing-blank bookkeeping — Ready projection owns blanks.
            scheduleIndex();
        }
    }

    /** Always 0 — BoardEntity write-behind queue is gone; Ready flushes are fire-and-forget. */
    int pendingWrites() {
        return 0;
    }

    @Override
    public boolean placePageBefore(String boardId, String beforeBoardId) {
        Board before;
        synchronized (lock) {
            before = cache.get(beforeBoardId);
        }
        String dest = before == null ? null : notebookIdOfSheet(before.sheetId);
        return placePageBefore(boardId, beforeBoardId, dest);
    }

    @Override
    public boolean placePageBefore(String boardId, String beforeBoardId, String destNotebookId) {
        if (boardId == null || beforeBoardId == null || boardId.equals(beforeBoardId)) {
            return false;
        }
        Board moving;
        Board before;
        synchronized (lock) {
            moving = cache.get(boardId);
            before = cache.get(beforeBoardId);
        }
        if (moving == null || before == null || moving.paper == null) {
            LaunchLog.mark("place-before refused: missing source/target board or paper");
            return false;
        }
        String destSheetId = NotebookPaper.sheetId(destNotebookId);
        if (knownPageHeight > 0) {
            ensureSheet(destNotebookId, knownPageHeight, knownLegacyHeight, knownShortPagesSince);
        }
        synchronized (lock) {
            InkSheetStore.OpenSheet dest = sheetStore.get(destSheetId);
            int sourceIndex = moving.paper.indexOfSliceId(boardId);
            if (dest == null || !dest.ready || sourceIndex < 0
                    || !moving.paper.containsInk(sourceIndex)) {
                LaunchLog.mark("place-before refused: dest not ready or source has no ink"
                        + " destReady=" + (dest != null && dest.ready)
                        + " sourceIndex=" + sourceIndex);
                return false;
            }
            // Same notebook only when the source already lives on the *destination*
            // sheet — never when a stale on-screen target shares the source paper.
            boolean sameNotebook = moving.paper == dest.paper
                    && java.util.Objects.equals(moving.sheetId, destSheetId);
            // Stale cross-notebook target (wrong paper) → treat as trailing append.
            boolean beforeIsTrailing = (!sameNotebook && before.paper != dest.paper)
                    || isTrailingBlankBoard(before, dest.paper);
            int beforeIndex = beforeIsTrailing ? -1 : dest.paper.indexOfSliceId(beforeBoardId);
            if (!beforeIsTrailing && beforeIndex < 0) {
                return false;
            }
            if (beforeIsTrailing && sameNotebook) {
                int last = dest.paper.sliceCount() - 1;
                if (last < 0 || sourceIndex == last) {
                    return false;
                }
            }
            String sourceSheetId = moving.sheetId;
            int destStrokes = dest.paper.strokes().size();
            try {
                // One paper spine: same-paper tearMove* reorders; cross tears.
                if (beforeIsTrailing) {
                    moving.paper.tearMoveAfter(sourceIndex, dest.paper, dest.paper.sliceCount() - 1);
                } else {
                    moving.paper.tearMoveBefore(sourceIndex, dest.paper, beforeIndex);
                }
            } catch (RuntimeException e) {
                Log.e(TAG, "place page failed; left sheets unchanged", e);
                LaunchLog.mark("place-before failed; left unchanged: " + e.getMessage());
                return false;
            }
            boolean lost = dest.paper.indexOfSliceId(boardId) < 0;
            boolean crossBroken = !sameNotebook && (dest.paper.strokes().size() < destStrokes
                    || moving.paper.indexOfSliceId(boardId) >= 0);
            if (lost || crossBroken) {
                LaunchLog.mark("place-before postflight failed"
                        + " lost=" + lost
                        + " sourceStillHas=" + (moving.paper.indexOfSliceId(boardId) >= 0));
                if (!sameNotebook) {
                    // Memory may be torn; hold flushes and reload from disk.
                    sheetStore.setPairFlushHeld(sourceSheetId, destSheetId, true);
                    writer.execute(() -> reloadPairFromDiskAndRelease(sourceSheetId, destSheetId));
                }
                return false;
            }
            syncSliceIndex(dest.paper, boardId);
            if (!sameNotebook) {
                // Reseat source boards so Move chrome / isBlank agree mid-reload.
                syncAllSliceIndices(moving.paper);
                // Adopted trailing blank keeps its id as the slice; drop it from
                // the source list so Move-back cannot placePageBefore(id, id).
                String sourceKey = listKey(notebookIdOfSheet(sourceSheetId));
                Board sourceBlank = trailingBlanks.get(sourceKey);
                if (sourceBlank != null && boardId.equals(sourceBlank.id)) {
                    trailingBlanks.remove(sourceKey);
                }
            }
            // One persist spine: hold → dest-first rewrite → release → (cross) flush.
            sheetStore.setPairFlushHeld(sourceSheetId, destSheetId, true);
            final String movedId = boardId;
            final String destNb = destNotebookId;
            final String sourceNb = notebookIdOfSheet(sourceSheetId);
            final boolean cross = !sameNotebook;
            writer.execute(() -> {
                persistPlaceSheets(destSheetId, sourceSheetId);
                if (cross) {
                    assertMoveLandedOrLog(movedId, destNb, sourceNb);
                }
            });
            return true;
        }
    }

    /**
     * After dest-first persist: dest log must still name the page; source must not.
     * Logs loudly if invariants break (does not throw on the writer thread).
     */
    private void assertMoveLandedOrLog(String pageId, String destNotebookId, String sourceNotebookId) {
        synchronized (lock) {
            InkSheetStore.OpenSheet dest = sheetStore.get(NotebookPaper.sheetId(destNotebookId));
            InkSheetStore.OpenSheet source = sheetStore.get(NotebookPaper.sheetId(sourceNotebookId));
            boolean onDest = dest != null && dest.ready && dest.paper.indexOfSliceId(pageId) >= 0;
            boolean onSource = source != null && source.ready && source.paper.indexOfSliceId(pageId) >= 0;
            if (!onDest || onSource) {
                LaunchLog.mark("place-before INVARIANT BROKEN page=" + pageId
                        + " onDest=" + onDest + " onSource=" + onSource);
                Log.e(TAG, "place-before invariant broken page=" + pageId
                        + " onDest=" + onDest + " onSource=" + onSource);
            }
        }
    }

    /**
     * Trailing blank is not a paper slice yet ({@code sliceIndex == sliceCount}),
     * or its id is absent from the sheet. Never trust a cached sliceIndex alone.
     */
    private static boolean isTrailingBlankBoard(Board before, NotebookPaper paper) {
        if (before == null || paper == null) {
            return true;
        }
        if (paper.indexOfSliceId(before.id) < 0) {
            return before.sliceIndex < 0 || before.sliceIndex >= paper.sliceCount() || before.isBlank();
        }
        return false;
    }

    /** Refresh the Board.sliceIndex for this id after a tear/reorder. */
    private void syncSliceIndex(NotebookPaper paper, String boardId) {
        if (paper == null || boardId == null) {
            return;
        }
        Board board = cache.get(boardId);
        if (board == null) {
            return;
        }
        int index = paper.indexOfSliceId(boardId);
        board.paper = paper;
        board.sliceIndex = index;
        if (index >= 0) {
            board.paperOrigin = paper.origin(index);
            board.slicePx = paper.heightAt(index);
        }
    }

    /** Reseat every cached board that still lives on this paper (by slice id). */
    private void syncAllSliceIndices(NotebookPaper paper) {
        if (paper == null) {
            return;
        }
        for (int i = 0; i < paper.sliceCount(); i++) {
            syncSliceIndex(paper, paper.sliceId(i));
        }
    }

    /**
     * Writer thread: dest-first rewrite for same-nb reorder and cross-nb Move.
     * Same-nb passes {@code source == dest} (rewrite is enough; skip flush).
     * Holds must already be set. Dest failure → reload pair from disk.
     */
    private void persistPlaceSheets(String destSheetId, String sourceSheetId) {
        if (!sheetStore.rewriteSheetOrFalse(destSheetId)) {
            LaunchLog.mark("place-before dest rewrite failed; source not torn on disk");
            Log.e(TAG, "place-before dest rewrite failed; source not torn on disk");
            reloadPairFromDiskAndRelease(sourceSheetId, destSheetId);
            return;
        }
        sheetStore.setPairFlushHeld(sourceSheetId, destSheetId, false);
        if (!Objects.equals(sourceSheetId, destSheetId)) {
            sheetStore.flushSheetOrFalse(sourceSheetId);
        }
    }

    /** Pre-tear disk wins; restore memory and drop holdFlush. */
    private void reloadPairFromDiskAndRelease(String sourceSheetId, String destSheetId) {
        sheetStore.reloadSheetFromDisk(sourceSheetId, knownPageHeight);
        sheetStore.reloadSheetFromDisk(destSheetId, knownPageHeight);
        sheetStore.setPairFlushHeld(sourceSheetId, destSheetId, false);
    }

    /** Notebook id for a sheet id ({@code null} = Scratchpad). */
    private String notebookIdOfSheet(String sheetId) {
        if (sheetId == null || NotebookPaper.SCRATCHPAD_ID.equals(sheetId)) {
            return null;
        }
        return sheetId;
    }

    /**
     * Cross-notebook Move for callers that only know the dest notebook id.
     * Log-backed pages go through {@link #placePageBefore} (same spine as the
     * UI Move menu) so slice id + dest-first persist stay intact — not the
     * old append-{@code tearMove} path that dropped the id and tombstoned links.
     */
    @Override
    public boolean movePageToNotebook(String boardId, String notebookId) {
        Board moving;
        synchronized (lock) {
            moving = cache.get(boardId);
        }
        if (moving != null && moving.paper != null) {
            if (knownPageHeight > 0) {
                ensureSheet(notebookId, knownPageHeight, knownLegacyHeight, knownShortPagesSince);
            }
            List<Board> destPages = pages(notebookId);
            if (destPages.isEmpty()) {
                LaunchLog.mark("movePageToNotebook refused: dest empty after ensure " + notebookId);
                return false;
            }
            Board trailing = destPages.get(destPages.size() - 1);
            return placePageBefore(boardId, trailing.id, notebookId);
        }
        synchronized (lock) {
            NotebookEntity target = dao.notebook(notebookId);
            if (target == null || target.deletedAt != null) {
                return false;
            }
            BoardEntity row = dao.board(boardId);
            String source;
            if (row == null) {
                String key = listOfBlankLocked(boardId);
                if (key == null) {
                    return false;
                }
                Board blank = trailingBlanks.remove(key);
                source = notebookOfKey(key);
                row = new BoardEntity();
                row.id = blank.id;
                row.createdAt = blank.createdAt;
                cache.put(row.id, blank);
            } else {
                source = row.notebookId;
            }
            row.position = Positions.after(dao.lastNotebookBoardPosition(notebookId));
            row.notebookId = notebookId;
            row.updatedAt = clock.getAsLong();
            BoardEntity toSave = row;
            db.runInTransaction(() -> {
                dao.upsertBoard(toSave);
                queueLocked(OutboxEntry.BOARD, toSave.id, OutboxEntry.UPSERT);
            });
            scheduleIndex();
            return true;
        }
    }

    @Override
    public void wipePage(String boardId) {
        mutateLegacyOrPaperPage(boardId, false);
    }

    @Override
    public void deletePage(String boardId) {
        mutateLegacyOrPaperPage(boardId, true);
    }

    /**
     * One wipe/delete spine: paper slice by id (Move-safe), else legacy board row.
     * Paper-backed non-slices (trailing blank) are no-ops.
     */
    private void mutateLegacyOrPaperPage(String boardId, boolean delete) {
        synchronized (lock) {
            if (paperSliceActionLocked(boardId, delete)) {
                return;
            }
            Board board = cache.get(boardId);
            if (board != null && board.paper != null) {
                return;
            }
            if (delete) {
                BoardEntity row = dao.board(boardId);
                if (row == null || row.deletedAt != null || row.conflictOf != null) {
                    return;
                }
                // Refuse deleting the sole stored trailing blank of an unmigrated
                // list (migrationSources strips one inkHash-null tail). Ready
                // deletes never reach here — paperSliceActionLocked returns first.
                if (row.inkHash == null) {
                    List<BoardEntity> rows = rowsLocked(row.notebookId);
                    if (!rows.isEmpty() && rows.get(rows.size() - 1).id.equals(row.id)
                            && !trailingBlanks.containsKey(listKey(row.notebookId))) {
                        return;
                    }
                }
                tombstoneLocked(row);
                tombstoneLinksTouchingLocked(row.id);
                return;
            }
            BoardEntity row = dao.board(boardId);
            if (board == null && row != null) {
                board = load(row);
            }
            if (board == null || row == null) {
                return;
            }
            board.strokes.clear();
            try {
                persistBoardEntityInk(board);
            } catch (IOException e) {
                Log.e(TAG, "legacy wipe failed " + boardId, e);
            }
        }
    }

    @Override
    public PageLink createLink(String sourceId, String targetId) {
        if (sourceId == null || targetId == null || sourceId.equals(targetId)) {
            return null;
        }
        synchronized (lock) {
            if (dao.liveLink(sourceId, targetId) != null) {
                return null;
            }
            long now = clock.getAsLong();
            PageLinkEntity row = new PageLinkEntity();
            row.id = UUID.randomUUID().toString();
            row.sourceId = sourceId;
            row.targetId = targetId;
            row.createdAt = now;
            row.updatedAt = now;
            db.runInTransaction(() -> {
                dao.upsertPageLink(row);
                queueLocked(OutboxEntry.LINK, row.id, OutboxEntry.UPSERT);
            });
            return new PageLink(row.id, row.sourceId, row.targetId);
        }
    }

    @Override
    public List<PageLink> linksTouching(String pageId) {
        synchronized (lock) {
            ArrayList<PageLink> out = new ArrayList<>();
            if (pageId == null) {
                return out;
            }
            for (PageLinkEntity row : dao.liveLinksTouching(pageId)) {
                out.add(new PageLink(row.id, row.sourceId, row.targetId));
            }
            return out;
        }
    }

    @Override
    public PagePlace placeOf(String pageId) {
        notebookReads.incrementAndGet();
        if (pageId == null) {
            return null;
        }
        PagePlace found = placeIn(null, pageId);
        if (found != null) {
            return found;
        }
        for (NotebookInfo info : notebooks()) {
            found = placeIn(info.id(), pageId);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** Opens the sheet if this screen already knows a page height, then looks the id up. */
    private PagePlace placeIn(String notebookId, String pageId) {
        if (knownPageHeight > 0) {
            ensureSheet(notebookId, knownPageHeight, knownLegacyHeight, knownShortPagesSince);
        }
        List<Board> list = pages(notebookId);
        for (int i = 0; i < list.size(); i++) {
            if (pageId.equals(list.get(i).id)) {
                return new PagePlace(notebookId, i, list.size());
            }
        }
        return null;
    }

    @Override
    public NotebookInfo createNotebook(String title) {
        return createNotebook(title, null);
    }

    @Override
    public NotebookInfo createNotebook(String title, String parentId) {
        String clean = cleanTitle(title);
        if (clean == null) {
            return null;
        }
        synchronized (lock) {
            if (parentId != null) {
                NotebookEntity parent = dao.notebook(parentId);
                if (parent == null || parent.deletedAt != null) {
                    return null;
                }
            }
            long now = clock.getAsLong();
            NotebookEntity row = new NotebookEntity();
            row.id = UUID.randomUUID().toString();
            row.title = clean;
            row.parentId = parentId;
            row.position = Positions.after(dao.lastNotebookPosition());
            row.createdAt = now;
            row.updatedAt = now;
            saveNotebookQueued(row);
            return new NotebookInfo(row.id, row.title, row.parentId);
        }
    }

    @Override
    public void renameNotebook(String notebookId, String title) {
        String clean = cleanTitle(title);
        if (clean == null) {
            return;
        }
        synchronized (lock) {
            NotebookEntity row = dao.notebook(notebookId);
            if (row == null || row.deletedAt != null || row.title.equals(clean)) {
                return;
            }
            row.title = clean;
            row.updatedAt = clock.getAsLong();
            saveNotebookQueued(row);
        }
    }

    @Override
    public void deleteNotebook(String notebookId) {
        // One return spine with pull-delete: Ready migrate + Move only.
        // BoardEntity reseat is gone — Ready-only Scratchpad ignores it anyway.
        if (!returnReadyPagesToScratchpad(notebookId)) {
            LaunchLog.mark("deleteNotebook refused: pages not returned " + notebookId);
            return;
        }
        synchronized (lock) {
            NotebookEntity row = dao.notebook(notebookId);
            if (row == null || row.deletedAt != null) {
                return;
            }
            long now = clock.getAsLong();
            row.deletedAt = now;
            row.updatedAt = now;
            String promoteTo = row.parentId;
            db.runInTransaction(() -> {
                dao.upsertNotebook(row);
                queueLocked(OutboxEntry.NOTEBOOK, row.id, OutboxEntry.DELETE);
                promoteChildrenLocked(notebookId, promoteTo);
            });
            forgetDeletedNotebookUiLocked(notebookId);
            // Scratchpad trailing blank refreshed on next Ready pages(null).
            scheduleIndex();
        }
    }

    /**
     * Move Ready paper slices onto Scratchpad (same spine as the Move menu)
     * before a notebook tombstone — local delete or winning remote pull-delete.
     * Ensures first so unmigrated {@code .zdi} become Ready slices via
     * {@link #migrationSourcesForSheet}. Returns false when ensure fails while
     * inked BoardEntity rows remain (local delete refuses; pull still tombstones).
     */
    private boolean returnReadyPagesToScratchpad(String notebookId) {
        if (notebookId == null) {
            return true;
        }
        int height = knownPageHeight > 0 ? knownPageHeight : 800;
        int legacy = knownLegacyHeight > 0 ? knownLegacyHeight : height;
        if (!ensureSheet(notebookId, height, legacy, knownShortPagesSince)) {
            // Put-back (~10%): blank-only / empty may delete; inked BoardEntity
            // left behind would be invisible on Ready-only Scratchpad.
            synchronized (lock) {
                for (BoardEntity row : dao.notebookBoards(notebookId)) {
                    if (row.inkHash != null && row.deletedAt == null) {
                        LaunchLog.mark("returnReady refused: ensure failed with inked BoardEntity "
                                + notebookId);
                        return false;
                    }
                }
            }
            return true;
        }
        ensureSheet(null, height, legacy, knownShortPagesSince);
        // Refresh Scratchpad trailing blank before Moves (adopted blank may still
        // sit in trailingBlanks after the page was filed elsewhere).
        pages(null);
        ArrayList<String> inked = new ArrayList<>();
        for (Board page : pages(notebookId)) {
            if (!page.isBlank()) {
                inked.add(page.id);
            }
        }
        for (String id : inked) {
            if (!movePageToNotebook(id, null)) {
                LaunchLog.mark("returnReady Move failed " + id + " from " + notebookId);
                return false;
            }
        }
        return true;
    }

    /**
     * Whether a pulled notebook tombstone would win LWW (same gate as
     * {@link MetaPullApply#applyRemoteNotebook}). Ready-return runs before the
     * locked apply — {@link #pages} refuses deleted notebooks.
     */
    private boolean remoteNotebookDeleteWins(NotebookEntity remote) {
        if (remote == null || remote.id == null || remote.deletedAt == null) {
            return false;
        }
        synchronized (lock) {
            NotebookEntity local = dao.notebook(remote.id);
            if (local != null && local.deletedAt != null) {
                return false;
            }
            if (local != null && (remote.rev <= local.rev
                    || (pendingLocked(OutboxEntry.NOTEBOOK, remote.id)
                    && !Lww.remoteWins(local.updatedAt, remote.updatedAt)))) {
                return false;
            }
            return true;
        }
    }

    /** Drop trailing-blank cache + Ready sheet after a notebook is tombstoned. */
    private void forgetDeletedNotebookUiLocked(String notebookId) {
        Board blank = trailingBlanks.remove(listKey(notebookId));
        if (blank != null) {
            cache.remove(blank.id);
        }
        sheetStore.invalidate(notebookId);
    }

    // endregion

    // region SyncStore

    /**
     * Three steps so ink files are read without the lock: pick rows (locked),
     * read their files (unlocked), then keep only boards whose row did not
     * change in between (locked). A board that changed stays queued for the
     * next round.
     */
    @Override
    public PushBatch pendingPush(int limit) {
        PushBatch batch = new PushBatch();
        ArrayList<BoardEntity> boards = new ArrayList<>();
        ArrayList<NotebookLogEntity> logs = new ArrayList<>();
        synchronized (lock) {
            for (OutboxEntry entry : dao.outboxBatch(limit)) {
                // One pick spine: load row or drop stale outbox; remember queuedAt early
                // (attach may still drop log/board after disk stage).
                if (OutboxEntry.NOTEBOOK.equals(entry.entity)) {
                    takeQueued(entry, dao::notebook, batch.notebooks::add, batch);
                } else if (OutboxEntry.LOG.equals(entry.entity)) {
                    takeQueued(entry, dao::notebookLog, logs::add, batch);
                } else if (OutboxEntry.LINK.equals(entry.entity)) {
                    takeQueued(entry, dao::pageLink, batch.links::add, batch);
                } else if (OutboxEntry.BOARD.equals(entry.entity)) {
                    takeQueued(entry, dao::board, boards::add, batch);
                } else {
                    dao.deleteOutbox(entry.entity, entry.id);
                }
            }
        }
        // At most one large on-disk blob per batch (Boox heap). Log + board share stage/attach.
        boolean[] haveLarge = {false};
        HashMap<String, byte[]> smallBlobs = new HashMap<>();
        HashMap<String, File> largeBlobs = new HashMap<>();
        ArrayList<BoardEntity> boardsKept = new ArrayList<>();
        ArrayList<NotebookLogEntity> logsKept = new ArrayList<>();
        for (NotebookLogEntity row : logs) {
            if (stagePushBlob(row.id, row.inkHash, row.deletedAt, ink.logFile(row.id),
                    haveLarge, smallBlobs, largeBlobs, id -> readQuietly(id, true))) {
                logsKept.add(row);
            }
        }
        for (BoardEntity row : boards) {
            if (stagePushBlob(row.id, row.inkHash, row.deletedAt, ink.fileFor(row.id),
                    haveLarge, smallBlobs, largeBlobs, id -> readQuietly(id, false))) {
                boardsKept.add(row);
            }
        }
        synchronized (lock) {
            for (NotebookLogEntity seen : logsKept) {
                NotebookLogEntity row = dao.notebookLog(seen.id);
                if (row == null || !sameInkOutbox(seen.inkHash, seen.deletedAt, seen.updatedAt, seen.rev,
                        row.inkHash, row.deletedAt, row.updatedAt, row.rev)) {
                    continue;
                }
                if (!attachPushBlob(row.id, row.inkHash, smallBlobs, largeBlobs, batch, (hash, bytes) -> {
                    row.inkHash = hash;
                    row.inkBytes = bytes;
                    dao.upsertNotebookLog(row);
                })) {
                    continue;
                }
                batch.logs.add(row);
            }
            for (BoardEntity seen : boardsKept) {
                BoardEntity row = dao.board(seen.id);
                if (row == null || !sameInkOutbox(seen.inkHash, seen.deletedAt, seen.updatedAt, seen.rev,
                        row.inkHash, row.deletedAt, row.updatedAt, row.rev)) {
                    continue;
                }
                if (!attachPushBlob(row.id, row.inkHash, smallBlobs, largeBlobs, batch, (hash, bytes) -> {
                    // Crash between file commit and row update: file is newer.
                    row.inkHash = hash;
                    row.inkBytes = bytes;
                    dao.upsertBoard(row);
                })) {
                    continue;
                }
                batch.boards.add(row);
            }
        }
        return batch;
    }

    /**
     * Outbox pick: load entity or delete the stale row. Remembers {@code queuedAt}
     * so push results can drop the outbox only when unchanged mid-flight.
     */
    private <T> void takeQueued(OutboxEntry entry, Function<String, T> load,
                                Consumer<T> keep, PushBatch batch) {
        T row = load.apply(entry.id);
        if (row == null) {
            dao.deleteOutbox(entry.entity, entry.id);
            return;
        }
        keep.accept(row);
        batch.queuedAt.put(entry.entity + ":" + entry.id, entry.queuedAt);
    }

    /** Hash-checked pull blob for board or log; null = no body / not in page. */
    private static byte[] verifiedPullBytes(PullPage page, String inkHash, Long deletedAt,
                                            String id, String kind) throws IOException {
        if (inkHash == null || deletedAt != null) {
            return null;
        }
        byte[] bytes = page.blobs.get(inkHash);
        if (bytes == null) {
            return null;
        }
        if (!InkFileStore.sha256(bytes).equals(inkHash)) {
            throw new IOException("bad " + kind + " for " + id);
        }
        return bytes;
    }

    /** False = defer: another large blob already claimed this batch's single large slot. */
    private static boolean stagePushBlob(
            String id, String inkHash, Long deletedAt, File file, boolean[] haveLarge,
            HashMap<String, byte[]> small, HashMap<String, File> large,
            Function<String, byte[]> reader) {
        if (inkHash == null || deletedAt != null || !file.exists()) {
            return true;
        }
        if (file.length() > SyncStore.INLINE_BLOB_MAX_BYTES) {
            if (haveLarge[0]) {
                return false;
            }
            haveLarge[0] = true;
            large.put(id, file);
            return true;
        }
        byte[] bytes = reader.apply(id);
        if (bytes != null) {
            small.put(id, bytes);
        }
        return true;
    }

    /** Attach staged bytes; repair hash when file is newer. False = large hash failed. */
    private static boolean attachPushBlob(
            String id, String inkHash, HashMap<String, byte[]> small, HashMap<String, File> large,
            PushBatch batch, BiConsumer<String, Long> repairHash) {
        byte[] bytes = small.get(id);
        if (bytes != null) {
            String actual = InkFileStore.sha256(bytes);
            if (!actual.equals(inkHash)) {
                repairHash.accept(actual, (long) bytes.length);
            }
            batch.blobs.put(actual, bytes);
            return true;
        }
        File largeFile = large.get(id);
        if (largeFile == null) {
            return true;
        }
        try {
            String actual = InkFileStore.sha256File(largeFile);
            if (!actual.equals(inkHash)) {
                repairHash.accept(actual, largeFile.length());
            }
            batch.blobFiles.put(actual, largeFile);
            return true;
        } catch (IOException e) {
            LaunchLog.mark("pendingPush hash failed " + id + ": " + e.getMessage());
            return false;
        }
    }

    /** Outbox re-read unchanged: same ink meta fields for board or log. */
    private static boolean sameInkOutbox(String aHash, Long aDeleted, long aUpdated, long aRev,
                                         String bHash, Long bDeleted, long bUpdated, long bRev) {
        return Objects.equals(aHash, bHash) && Objects.equals(aDeleted, bDeleted)
                && aUpdated == bUpdated && aRev == bRev;
    }

    @Override
    public void applyPushResults(PushBatch batch, List<PushResult> results) {
        synchronized (lock) {
            db.runInTransaction(() -> {
                for (PushResult result : results) {
                    Long queuedAt = batch.queuedAt.get(result.entity() + ":" + result.id());
                    if (queuedAt == null) {
                        continue;
                    }
                    switch (result.status()) {
                        case PushResult.APPLIED:
                        case PushResult.CONFLICT_WON:
                            setRevLocked(result.entity(), result.id(), result.rev());
                            // fall through — drop outbox when unchanged (won or lost)
                        case PushResult.CONFLICT_LOST:
                            // CONFLICT_LOST: server kept its row and shelved ours;
                            // next pull brings both down.
                            dao.deleteOutboxIfUnchanged(result.entity(), result.id(), queuedAt);
                            break;
                        default:
                            break;
                    }
                }
            });
        }
    }

    @Override
    public long pullCursor() {
        synchronized (lock) {
            SyncState state = dao.syncState();
            return state == null ? 0L : state.cursor;
        }
    }

    @Override
    public long localInkLogBytes(String sheetId) {
        return sheetStore.localInkLogBytes(sheetId);
    }

    @Override
    public String localInkLogHash(String sheetId) {
        return sheetStore.localInkLogHash(sheetId);
    }

    @Override
    public String localBoardInkHash(String boardId) {
        if (boardId == null) {
            return "";
        }
        synchronized (lock) {
            BoardEntity row = dao.board(boardId);
            return row == null || row.inkHash == null ? "" : row.inkHash;
        }
    }

    /**
     * Blobs are verified, decoded and staged (fsync'd) before the lock is
     * taken; under the lock each applied board only renames its staged file.
     */
    @Override
    public void applyPull(PullPage page) throws IOException {
        HashMap<String, List<InkRenderer.InkStroke>> refreshed = new HashMap<>();
        HashMap<String, Long> editsSeen = new HashMap<>();
        HashMap<String, Board> boardsSeen = new HashMap<>();
        HashMap<String, MetaPullApply.Staged> staged = new HashMap<>();
        HashMap<String, InkSheetStore.StagedLog> stagedLogs = new HashMap<>();
        try {
            for (NotebookLogEntity remote : page.logs) {
                byte[] bytes = verifiedPullBytes(page, remote.inkHash, remote.deletedAt, remote.id, "ink log");
                if (bytes == null) {
                    continue;
                }
                // Replay once before the lock so a torn log never becomes the notebook.
                stagedLogs.put(remote.id, new InkSheetStore.StagedLog(bytes, NotebookPaper.replay(bytes)));
            }
            for (BoardEntity remote : page.boards) {
                byte[] bytes = verifiedPullBytes(page, remote.inkHash, remote.deletedAt, remote.id, "ink blob");
                if (bytes == null) {
                    continue;
                }
                List<InkRenderer.InkStroke> strokes;
                try {
                    strokes = InkCodec.decode(bytes);
                } catch (IOException e) {
                    // Kept byte-for-byte (e.g. a newer ink format); it must not block the rest of the pull.
                    Log.w(TAG, "undecodable ink for " + remote.id, e);
                    strokes = new ArrayList<>();
                }
                staged.put(remote.id, new MetaPullApply.Staged(
                        ink.stage(remote.id, "pull", bytes), bytes, strokes));
            }
            // Same Ready return as local deleteNotebook — before tombstone so
            // pages() still sees the sheet. Ensure-fail with inked BoardEntity
            // is logged; remote LWW tombstone still applies (no BoardEntity reseat).
            for (NotebookEntity remote : page.notebooks) {
                if (remoteNotebookDeleteWins(remote)) {
                    returnReadyPagesToScratchpad(remote.id);
                }
            }
            applyPullLocked(page, staged, stagedLogs, refreshed, editsSeen, boardsSeen);
        } finally {
            for (MetaPullApply.Staged s : staged.values()) {
                ink.discard(s.file());
            }
        }
        if (!page.isEmpty()) {
            boolean post;
            synchronized (refreshLock) {
                for (Map.Entry<String, List<InkRenderer.InkStroke>> e : refreshed.entrySet()) {
                    Board board = boardsSeen.get(e.getKey());
                    if (board != null) {
                        pendingRefresh.remove(e.getKey());
                        pendingRefresh.put(e.getKey(), new Refresh(board, e.getValue(), editsSeen.get(e.getKey())));
                    }
                }
                refreshChanged = true;
                post = remoteBatchDepth == 0 && !refreshPosted;
                refreshPosted |= post;
            }
            if (post) {
                ui.execute(this::deliverRemoteChanges);
            }
        }
    }

    @Override
    public void setInkPullPolicy(InkPullPolicy policy) {
        inkPullPolicy = policy == null ? InkPullPolicy.CATCH_UP : policy;
    }

    @Override
    public InkPullPolicy inkPullPolicy() {
        return inkPullPolicy;
    }

    /**
     * Sync calls this around a whole pass so its pulled pages reach the
     * screen as one refresh at the end, not one per page.
     */
    @Override
    public void beginRemoteBatch() {
        synchronized (refreshLock) {
            remoteBatchDepth++;
        }
    }

    @Override
    public void endRemoteBatch() {
        boolean post;
        synchronized (refreshLock) {
            remoteBatchDepth = Math.max(0, remoteBatchDepth - 1);
            post = remoteBatchDepth == 0 && refreshChanged && !refreshPosted;
            refreshPosted |= post;
        }
        if (post) {
            ui.execute(this::deliverRemoteChanges);
        }
    }

    /**
     * UI thread, at most one queued at a time: swaps pulled strokes into the
     * shared boards and tells each screen once. Takes no repository lock.
     */
    private void deliverRemoteChanges() {
        ArrayList<Refresh> batch;
        synchronized (refreshLock) {
            refreshPosted = false;
            refreshChanged = false;
            batch = new ArrayList<>(pendingRefresh.values());
            pendingRefresh.clear();
        }
        int swapped = 0;
        for (Refresh r : batch) {
            // A page drawn on since the pull decided keeps the local strokes; that save wins on push.
            if (Objects.equals(localEdits.get(r.board().id), r.editsSeen())) {
                r.board().strokes.clear();
                r.board().strokes.addAll(r.strokes());
                swapped++;
            }
        }
        LaunchLog.mark("sync result on screen: " + swapped + " pages' ink replaced, " + remoteListeners.size()
                + " screens refreshed");
        for (Runnable listener : remoteListeners) {
            listener.run();
        }
    }

    @Override
    public void addRemoteChangeListener(Runnable listener) {
        if (listener != null) {
            remoteListeners.addIfAbsent(listener);
        }
    }

    @Override
    public void removeRemoteChangeListener(Runnable listener) {
        remoteListeners.remove(listener);
    }

    private record Refresh(Board board, List<InkRenderer.InkStroke> strokes, Long editsSeen) {
    }

    private void applyPullLocked(PullPage page, Map<String, MetaPullApply.Staged> staged,
                                 Map<String, InkSheetStore.StagedLog> stagedLogs,
                                 Map<String, List<InkRenderer.InkStroke>> refreshed,
                                 Map<String, Long> editsSeen, Map<String, Board> boardsSeen) throws IOException {
        synchronized (lock) {
            // Omitted-blob policy lives in MetaPullApply / InkSheetStore (hash match ⇒ keep local).
            ArrayList<Runnable> mirrorOps = new ArrayList<>();
            try {
                db.runInTransaction(() -> {
                    try {
                        for (NotebookEntity remote : page.notebooks) {
                            metaPull.applyRemoteNotebook(remote);
                            NotebookEntity now = dao.notebook(remote.id);
                            if (now != null && now.deletedAt != null) {
                                // Ready slices already Moved in applyPull; children +
                                // UI forget match local deleteNotebook.
                                promoteChildrenLocked(remote.id, now.parentId);
                                forgetDeletedNotebookUiLocked(remote.id);
                            }
                        }
                        // Logs before boards: Ready .zdl SoT installs first so
                        // MetaPullApply can no-op board ink on log-backed notebooks
                        // in the same pull page (no transient .zdi dual).
                        for (NotebookLogEntity remote : page.logs) {
                            sheetStore.applyRemoteLogLocked(remote, stagedLogs, mirrorOps);
                        }
                        for (BoardEntity remote : page.boards) {
                            metaPull.applyRemoteBoard(remote, staged, refreshed, mirrorOps);
                        }
                        for (PageLinkEntity remote : page.links) {
                            metaPull.applyRemoteLink(remote);
                        }
                        SyncState state = dao.syncState();
                        if (state == null) {
                            state = new SyncState();
                        }
                        state.cursor = Math.max(state.cursor, page.cursor);
                        dao.upsertSyncState(state);
                    } catch (IOException e) {
                        throw new PullFailed(e);
                    }
                });
            } catch (PullFailed e) {
                throw (IOException) e.getCause();
            }
            for (Runnable op : mirrorOps) {
                op.run();
            }
            for (String id : refreshed.keySet()) {
                editsSeen.put(id, localEdits.get(id));
                Board board = cache.get(id);
                if (board != null) {
                    boardsSeen.put(id, board);
                }
            }
            if (!page.isEmpty()) {
                scheduleIndex();
            }
        }
    }

    @Override
    public void markSynced(long now) {
        synchronized (lock) {
            SyncState state = dao.syncState();
            if (state == null) {
                state = new SyncState();
            }
            state.lastOkAt = now;
            dao.upsertSyncState(state);
        }
    }

    @Override
    public int outboxSize() {
        synchronized (lock) {
            return dao.outboxCount();
        }
    }

    @Override
    public long lastSyncedAt() {
        synchronized (lock) {
            SyncState state = dao.syncState();
            return state == null || state.lastOkAt == null ? 0L : state.lastOkAt;
        }
    }

    @Override
    public void queuePlaceholderSeedsForUpload() {
        synchronized (lock) {
            db.runInTransaction(() -> {
                for (Notebook notebook : Notebook.values()) {
                    NotebookEntity row = dao.notebook(notebook.uuid);
                    if (row == null || !isUntouchedSeed(row)) {
                        continue;
                    }
                    queueIfAbsent(OutboxEntry.NOTEBOOK, row.id);
                }
            });
        }
    }

    @Override
    public int ensureUnsyncedQueued() {
        synchronized (lock) {
            int[] added = {0};
            db.runInTransaction(() -> {
                for (NotebookEntity row : dao.liveNotebooks()) {
                    // Skip untouched install seeds — restore pull must win first.
                    if (isUntouchedSeed(row)) {
                        continue;
                    }
                    if (row.rev == 0 && queueIfAbsent(OutboxEntry.NOTEBOOK, row.id)) {
                        added[0]++;
                    }
                }
                for (BoardEntity row : dao.liveBoardsForIndex()) {
                    // inkHash-null rows are blanks / migration tails — not push bodies.
                    if (row.rev != 0 || row.conflictOf != null || row.inkHash == null) {
                        continue;
                    }
                    if (queueIfAbsent(OutboxEntry.BOARD, row.id)) {
                        added[0]++;
                    }
                }
                for (NotebookLogEntity row : dao.liveNotebookLogs()) {
                    if (row.rev == 0 && row.inkHash != null
                            && queueIfAbsent(OutboxEntry.LOG, row.id)) {
                        added[0]++;
                    }
                }
                for (PageLinkEntity row : dao.livePageLinks()) {
                    if (row.rev == 0 && queueIfAbsent(OutboxEntry.LINK, row.id)) {
                        added[0]++;
                    }
                }
            });
            if (added[0] > 0) {
                LaunchLog.mark("ensureUnsyncedQueued added " + added[0] + " outbox rows");
            }
            return added[0];
        }
    }

    @Override
    public int queueNestedNotebooksForUpload() {
        synchronized (lock) {
            int[] added = {0};
            db.runInTransaction(() -> {
                for (NotebookEntity row : dao.liveNotebooks()) {
                    if (row.parentId == null || row.deletedAt != null) {
                        continue;
                    }
                    if (queueIfAbsent(OutboxEntry.NOTEBOOK, row.id)) {
                        added[0]++;
                    }
                }
            });
            if (added[0] > 0) {
                LaunchLog.mark("queueNestedNotebooksForUpload added " + added[0] + " outbox rows");
            }
            return added[0];
        }
    }

    /** Queue upsert when no outbox row yet — one gate for seed / unsynced / nested backfill. */
    private boolean queueIfAbsent(String entity, String id) {
        if (dao.outbox(entity, id) != null) {
            return false;
        }
        queueLocked(entity, id, OutboxEntry.UPSERT);
        return true;
    }

    // endregion

    // region legacy import

    /** Inserts BoardStore boards in their old order. Returns the count imported. */
    int importLegacy(LegacyBoardImporter.Legacy legacy) throws IOException {
        synchronized (lock) {
            ArrayList<BoardEntity> rows = new ArrayList<>();
            ArrayList<Board> boards = new ArrayList<>();
            String last = dao.lastScratchpadPosition();
            ArrayList<Board> scratch = new ArrayList<>(legacy.scratchpad);
            scratch.addAll(legacy.orphans);
            for (Board board : scratch) {
                if (board.isBlank() || dao.board(board.id) != null) {
                    continue;
                }
                last = Positions.after(last);
                rows.add(legacyRow(board, null, last));
                boards.add(board);
            }
            for (Map.Entry<Notebook, List<Board>> entry : legacy.notebooks.entrySet()) {
                String notebookId = entry.getKey().uuid;
                String pos = dao.lastNotebookBoardPosition(notebookId);
                for (Board board : entry.getValue()) {
                    if (dao.board(board.id) != null) {
                        continue;
                    }
                    pos = Positions.after(pos);
                    rows.add(legacyRow(board, notebookId, pos));
                    boards.add(board);
                }
            }
            for (int i = 0; i < rows.size(); i++) {
                BoardEntity row = rows.get(i);
                Board board = boards.get(i);
                if (!board.isBlank()) {
                    byte[] bytes = InkCodec.encode(board.strokes);
                    row.inkHash = InkFileStore.sha256(bytes);
                    row.inkBytes = bytes.length;
                    writeInkLocked(row.id, bytes);
                }
            }
            db.runInTransaction(() -> {
                for (BoardEntity row : rows) {
                    dao.upsertBoard(row);
                    queueLocked(OutboxEntry.BOARD, row.id, OutboxEntry.UPSERT);
                }
            });
            for (Board board : boards) {
                cache.put(board.id, board);
            }
            for (Board blank : trailingBlanks.values()) {
                cache.remove(blank.id);
            }
            trailingBlanks.clear();
            // Ready projectLocked mints the Scratchpad blank on next pages().
            scheduleIndex();
            return rows.size();
        }
    }

    private BoardEntity legacyRow(Board board, String notebookId, String position) {
        BoardEntity row = new BoardEntity();
        row.id = board.id;
        row.notebookId = notebookId;
        row.position = position;
        row.createdAt = board.createdAt;
        row.updatedAt = clock.getAsLong();
        return row;
    }

    // endregion

    // region internals

    private static String cleanTitle(String title) {
        if (title == null) {
            return null;
        }
        String t = title.trim().replaceAll("\\s+", " ");
        return t.isEmpty() ? null : t;
    }

    /**
     * Live notebooks whose parent is {@code notebookId} take {@code newParent}
     * (null is top-level). Their position and pages stay. Each repair is queued.
     */
    private void promoteChildrenLocked(String notebookId, String newParent) {
        long now = clock.getAsLong();
        for (NotebookEntity child : dao.liveNotebooks()) {
            if (!notebookId.equals(child.parentId)) {
                continue;
            }
            child.parentId = newParent;
            child.updatedAt = now;
            dao.upsertNotebook(child);
            queueLocked(OutboxEntry.NOTEBOOK, child.id, OutboxEntry.UPSERT);
        }
    }

    /** False when {@code parentId} is this notebook or a descendant, or the chain is broken. */
    private boolean wouldCycle(String notebookId, String parentId) {
        HashSet<String> seen = new HashSet<>();
        String cursor = parentId;
        while (cursor != null) {
            if (!seen.add(cursor) || cursor.equals(notebookId)) {
                return true;
            }
            NotebookEntity row = dao.notebook(cursor);
            if (row == null || row.deletedAt != null) {
                return true;
            }
            cursor = row.parentId;
        }
        return false;
    }

    @Override
    public boolean placeNotebook(String notebookId, String parentId) {
        synchronized (lock) {
            NotebookEntity row = dao.notebook(notebookId);
            if (row == null || row.deletedAt != null) {
                return false;
            }
            if (parentId != null) {
                NotebookEntity parent = dao.notebook(parentId);
                if (parent == null || parent.deletedAt != null || wouldCycle(notebookId, parentId)) {
                    return false;
                }
            }
            if (Objects.equals(row.parentId, parentId)) {
                return true;
            }
            row.parentId = parentId;
            row.position = Positions.after(dao.lastNotebookPosition());
            row.updatedAt = clock.getAsLong();
            saveNotebookQueued(row);
            return true;
        }
    }

    private void seedNotebooks() {
        long now = clock.getAsLong();
        for (Notebook notebook : Notebook.values()) {
            if (dao.notebook(notebook.uuid) != null) {
                continue;
            }
            NotebookEntity row = new NotebookEntity();
            row.id = notebook.uuid;
            row.title = notebook.label;
            row.position = Positions.after(dao.lastNotebookPosition());
            row.createdAt = now;
            // Seeds lose every LWW comparison, so a rename or delete from another
            // device is never undone by a fresh install re-seeding the same id.
            row.updatedAt = 0;
            dao.upsertNotebook(row);
            // Do not queue seeds into the outbox. A fresh install used to push
            // these before pull and could overwrite never-edited server seeds
            // when base_rev matched. Cloud restore pulls first; see
            // {@link #queuePlaceholderSeedsForUpload()}.
        }
    }

    private static boolean isUntouchedSeed(NotebookEntity row) {
        if (row == null || row.updatedAt != 0L || row.rev != 0L || row.deletedAt != null) {
            return false;
        }
        for (Notebook notebook : Notebook.values()) {
            if (notebook.uuid.equals(row.id)) {
                return true;
            }
        }
        return false;
    }

    private static String listKey(String notebookId) {
        return notebookId == null ? "" : notebookId;
    }

    private static String notebookOfKey(String key) {
        return key.isEmpty() ? null : key;
    }

    private List<BoardEntity> rowsLocked(String notebookId) {
        return notebookId == null ? dao.scratchpadBoards() : dao.notebookBoards(notebookId);
    }

    private String lastPositionLocked(String notebookId) {
        return notebookId == null ? dao.lastScratchpadPosition() : dao.lastNotebookBoardPosition(notebookId);
    }

    /** Key of the list whose unsaved trailing blank is {@code boardId}, or null. */
    private String listOfBlankLocked(String boardId) {
        for (Map.Entry<String, Board> entry : trailingBlanks.entrySet()) {
            if (entry.getValue().id.equals(boardId)) {
                return entry.getKey();
            }
        }
        return null;
    }

    private void tombstoneLocked(BoardEntity row) {
        long now = clock.getAsLong();
        row.deletedAt = now;
        row.updatedAt = now;
        row.inkHash = null;
        row.inkBytes = 0;
        db.runInTransaction(() -> {
            dao.upsertBoard(row);
            queueLocked(OutboxEntry.BOARD, row.id, OutboxEntry.DELETE);
        });
        ink.delete(row.id);
        cache.remove(row.id);
        mirrorInk(row.id, null);
        scheduleIndex();
    }

    /** Paper wipe/delete by page id on a Ready sheet — never trusts cached sliceIndex. */
    private boolean paperSliceActionLocked(String boardId, boolean delete) {
        if (boardId == null) {
            return false;
        }
        for (Map.Entry<String, InkSheetStore.OpenSheet> entry : sheetStore.entrySet()) {
            InkSheetStore.OpenSheet sheet = entry.getValue();
            if (!sheet.ready || sheet.paper == null) {
                continue;
            }
            int index = sheet.paper.indexOfSliceId(boardId);
            if (index < 0) {
                continue;
            }
            if (delete) {
                sheet.paper.tearDelete(index);
                tombstoneLinksTouchingLocked(boardId);
            } else {
                sheet.paper.tearWipe(index);
            }
            String sheetId = entry.getKey();
            writer.execute(() -> sheetStore.flushSheetOrFalse(sheetId));
            return true;
        }
        return false;
    }

    /** A deleted page takes its links with it. The user never sees a missing-page row. */
    private void tombstoneLinksTouchingLocked(String pageId) {
        if (pageId == null) {
            return;
        }
        long now = clock.getAsLong();
        for (PageLinkEntity row : dao.liveLinksTouching(pageId)) {
            row.deletedAt = now;
            row.updatedAt = now;
            PageLinkEntity stored = row;
            db.runInTransaction(() -> {
                dao.upsertPageLink(stored);
                queueLocked(OutboxEntry.LINK, stored.id, OutboxEntry.DELETE);
            });
        }
    }

    private void queueLocked(String entity, String id, String op) {
        OutboxEntry entry = new OutboxEntry();
        entry.entity = entity;
        entry.id = id;
        entry.op = op;
        lastQueuedAt = Math.max(clock.getAsLong(), lastQueuedAt + 1);
        entry.queuedAt = lastQueuedAt;
        dao.upsertOutbox(entry);
    }

    /** Upsert notebook row + outbox UPSERT + Documents index. Caller set fields. */
    private void saveNotebookQueued(NotebookEntity row) {
        db.runInTransaction(() -> {
            dao.upsertNotebook(row);
            queueLocked(OutboxEntry.NOTEBOOK, row.id, OutboxEntry.UPSERT);
        });
        scheduleIndex();
    }

    private void setRevLocked(String entity, String id, long rev) {
        if (OutboxEntry.NOTEBOOK.equals(entity)) {
            NotebookEntity row = dao.notebook(id);
            if (row != null && rev > row.rev) {
                row.rev = rev;
                dao.upsertNotebook(row);
            }
        } else if (OutboxEntry.LOG.equals(entity)) {
            NotebookLogEntity row = dao.notebookLog(id);
            if (row != null && rev > row.rev) {
                row.rev = rev;
                dao.upsertNotebookLog(row);
            }
        } else if (OutboxEntry.LINK.equals(entity)) {
            PageLinkEntity row = dao.pageLink(id);
            if (row != null && rev > row.rev) {
                row.rev = rev;
                dao.upsertPageLink(row);
            }
        } else {
            BoardEntity row = dao.board(id);
            if (row != null && rev > row.rev) {
                row.rev = rev;
                dao.upsertBoard(row);
            }
        }
    }

    private boolean pendingLocked(String entity, String id) {
        return dao.outbox(entity, id) != null;
    }

    private void writeInkLocked(String boardId, byte[] bytes) throws IOException {
        if (bytes == null) {
            ink.delete(boardId);
        } else {
            ink.write(boardId, bytes);
        }
        mirrorInk(boardId, bytes);
    }

    /** Queues the Documents copy of one board; a newer call for the same board replaces an unsent one. */
    private void mirrorInk(String boardId, byte[] bytes) {
        boolean start;
        synchronized (mirrorLock) {
            mirrorQueue.remove(boardId);
            mirrorQueue.put(boardId, bytes == null ? MIRROR_DELETE : bytes);
            start = !mirrorDraining;
            mirrorDraining = true;
        }
        if (start) {
            mirrorExecutor.execute(this::drainMirror);
        }
    }

    private void drainMirror() {
        while (true) {
            String id;
            byte[] bytes;
            synchronized (mirrorLock) {
                Iterator<Map.Entry<String, byte[]>> it = mirrorQueue.entrySet().iterator();
                if (!it.hasNext()) {
                    mirrorDraining = false;
                    return;
                }
                Map.Entry<String, byte[]> next = it.next();
                id = next.getKey();
                bytes = next.getValue();
                it.remove();
            }
            if (bytes == MIRROR_DELETE) {
                mirror.deleteInk(id);
            } else {
                mirror.writeInk(id, bytes);
            }
        }
    }

    private Board load(BoardEntity row) {
        Board cached = cache.get(row.id);
        if (cached != null) {
            return cached;
        }
        Board board = new Board(row.id, row.createdAt);
        if (row.inkHash != null) {
            byte[] bytes = readQuietly(row.id, false);
            if (bytes == null) {
                LaunchLog.mark("ink file missing for page " + row.id + "; shown blank");
            } else {
                try {
                    board.strokes.addAll(InkCodec.decode(bytes));
                } catch (IOException e) {
                    // Shown blank, so the next stroke would overwrite it: keep the bytes aside first.
                    Log.e(TAG, "unreadable ink " + row.id, e);
                    LaunchLog.mark("unreadable ink for page " + row.id + " (" + e.getMessage() + "); copy kept as "
                            + ink.preserve(row.id, bytes));
                }
            }
        }
        cache.put(row.id, board);
        return board;
    }

    /** Null on IO failure — one quiet read for {@code .zdl} / {@code .zdi}. */
    private byte[] readQuietly(String id, boolean log) {
        try {
            return log ? ink.readLog(id) : ink.read(id);
        } catch (IOException e) {
            Log.e(TAG, (log ? "ink log" : "ink") + " read failed " + id, e);
            if (!log) {
                LaunchLog.mark("ink read failed for page " + id + ": " + e);
            }
            return null;
        }
    }

    private void scheduleIndex() {
        if (!indexPending.compareAndSet(false, true)) {
            return;
        }
        mirrorExecutor.execute(() -> {
            indexPending.set(false);
            // Room reads are thread-safe; the index is a backup and is rebuilt after every write.
            String json = buildIndex();
            if (json != null) {
                mirror.writeIndex(json);
            }
        });
    }

    private String buildIndex() {
        try {
            JSONObject root = new JSONObject();
            root.put("schema_version", ZettelDatabase.SCHEMA_VERSION);
            root.put("ink_format", "zdi/" + InkCodec.VERSION);
            root.put("generated_at", clock.getAsLong());
            JSONArray notebooks = new JSONArray();
            for (NotebookEntity row : dao.liveNotebooks()) {
                notebooks.put(new JSONObject()
                        .put("id", row.id)
                        .put("title", row.title)
                        .put("position", row.position)
                        .put("parent_id", row.parentId == null ? JSONObject.NULL : row.parentId));
            }
            root.put("notebooks", notebooks);
            JSONArray boards = new JSONArray();
            for (BoardEntity row : dao.liveBoardsForIndex()) {
                JSONObject b = new JSONObject()
                        .put("id", row.id)
                        .put("notebook_id", row.notebookId == null ? JSONObject.NULL : row.notebookId)
                        .put("position", row.position)
                        .put("ink_hash", row.inkHash == null ? JSONObject.NULL : row.inkHash)
                        .put("ink_bytes", row.inkBytes)
                        .put("file", row.inkHash == null ? JSONObject.NULL : "ink/" + row.id + ".zdi")
                        .put("created_at", row.createdAt)
                        .put("updated_at", row.updatedAt);
                if (row.conflictOf != null) {
                    b.put("conflict_of", row.conflictOf);
                }
                boards.put(b);
            }
            root.put("boards", boards);
            JSONArray links = new JSONArray();
            for (PageLinkEntity row : dao.livePageLinks()) {
                links.put(new JSONObject()
                        .put("id", row.id)
                        .put("source_id", row.sourceId)
                        .put("target_id", row.targetId));
            }
            root.put("links", links);
            return root.toString(2);
        } catch (JSONException e) {
            Log.e(TAG, "index build failed", e);
            return null;
        }
    }

    /**
     * Drops the in-memory Ready sheet so the next {@link #ensureSheet} re-reads
     * disk. Test / reload helper — not a length SoT.
     */
    public void invalidateOpenSheet(String notebookId) {
        sheetStore.invalidate(notebookId);
    }

    /**
     * Test hook: pretend the open sheet is fully flushed even when memory was
     * rewritten shorter (as after tearMoveInsert). Used to exercise the
     * holdFlush pull guard.
     */
    public void markSheetFlushedForTest(String notebookId) {
        synchronized (lock) {
            InkSheetStore.OpenSheet open = sheetStore.get(NotebookPaper.sheetId(notebookId));
            if (open != null && open.ready && open.paper != null) {
                open.flushed = open.paper.bytes().length;
            }
        }
    }

    @Override
    public boolean ensureSheet(String notebookId, int pageHeight, int legacyPageHeight, long shortPagesSince) {
        notebookReads.incrementAndGet();
        if (pageHeight <= 0) {
            return false;
        }
        knownPageHeight = pageHeight;
        knownLegacyHeight = legacyPageHeight;
        knownShortPagesSince = shortPagesSince;
        return sheetStore.ensureSheet(notebookId, pageHeight, legacyPageHeight, shortPagesSince);
    }

    /**
     * Ready {@code .zdl} is SoT — clear BoardEntity page-file ink so first-time
     * migrate residue cannot remain a second ink SoT / MetaPullApply shelf seed.
     * Keeps BoardEntity rows (identity / blank slots) but drops {@code .zdi},
     * inkHash, and BOARD outbox. Conflict copies are left for Hand-off history.
     * Caller holds {@link #lock}.
     */
    private void retireBoardEntityInkLocked(String notebookId) {
        List<BoardEntity> rows = rowsLocked(notebookId);
        if (rows.isEmpty()) {
            return;
        }
        boolean touched = false;
        for (BoardEntity row : rows) {
            if (row.conflictOf != null) {
                continue;
            }
            boolean hadFile = ink.fileFor(row.id).exists();
            boolean hadHash = row.inkHash != null;
            boolean hadOutbox = dao.outbox(OutboxEntry.BOARD, row.id) != null;
            if (!hadFile && !hadHash && !hadOutbox) {
                continue;
            }
            if (hadFile) {
                ink.delete(row.id);
                mirrorInk(row.id, null);
            }
            row.inkHash = null;
            row.inkBytes = 0;
            if (hadHash) {
                row.updatedAt = clock.getAsLong();
            }
            BoardEntity toSave = row;
            db.runInTransaction(() -> {
                if (hadHash || hadFile) {
                    dao.upsertBoard(toSave);
                }
                dao.deleteOutbox(OutboxEntry.BOARD, toSave.id);
            });
            touched = true;
        }
        if (touched) {
            LaunchLog.mark("retired BoardEntity ink for "
                    + (notebookId == null ? "scratchpad" : notebookId));
            scheduleIndex();
        }
    }

    /**
     * Legacy page-file sources for first-time {@code .zdl} migration. Null aborts
     * migration (decode failure). Empty list is a blank notebook. After Ready
     * install, {@link #retireBoardEntityInkLocked} clears these page files.
     */
    private List<NotebookPaper.SourcePage> migrationSourcesForSheet(
            String notebookId, int pageHeight, int legacyPageHeight, long shortPagesSince) {
        List<BoardEntity> rows;
        synchronized (lock) {
            rows = new ArrayList<>(rowsLocked(notebookId));
            // Strip one stored trailing blank (inkHash-null tail) so migration
            // does not materialize an empty last slice — Ready owns the blank.
            if (!rows.isEmpty() && rows.get(rows.size() - 1).inkHash == null
                    && !trailingBlanks.containsKey(listKey(notebookId))) {
                rows.remove(rows.size() - 1);
            }
        }
        ArrayList<NotebookPaper.SourcePage> sources = new ArrayList<>();
        for (BoardEntity row : rows) {
            if (row.conflictOf != null) {
                continue;
            }
            List<InkRenderer.InkStroke> strokes = Collections.emptyList();
            if (row.inkHash != null) {
                byte[] bytes = readQuietly(row.id, false);
                if (bytes != null) {
                    try {
                        strokes = InkCodec.decode(bytes);
                    } catch (IOException e) {
                        Log.e(TAG, "page ink skipped in migration " + row.id, e);
                        return null;
                    }
                }
            }
            int height = NotebookPaper.measuredHeight(
                    row.createdAt, strokes, pageHeight, legacyPageHeight, shortPagesSince);
            sources.add(new NotebookPaper.SourcePage(row.id, height, strokes));
        }
        return sources;
    }

    private List<Board> projectLocked(String notebookId, NotebookPaper paper) {
        String sheetId = NotebookPaper.sheetId(notebookId);
        ArrayList<Board> pages = new ArrayList<>();
        HashSet<String> sliceIds = new HashSet<>();
        for (int i = 0; i < paper.sliceCount(); i++) {
            String id = paper.sliceId(i);
            sliceIds.add(id);
            Board board = cache.get(id);
            if (board == null) {
                BoardEntity row = dao.board(id);
                board = new Board(id, row == null ? clock.getAsLong() : row.createdAt);
                cache.put(id, board);
            }
            board.paper = paper;
            board.sheetId = sheetId;
            board.sliceIndex = i;
            board.paperOrigin = paper.origin(i);
            board.slicePx = paper.heightAt(i);
            board.strokes.clear();
            pages.add(board);
        }
        boolean needBlank = pages.isEmpty() || !pages.get(pages.size() - 1).isBlank();
        String key = listKey(notebookId);
        if (needBlank) {
            Board blank = trailingBlanks.get(key);
            // Trailing blank must not be a live slice (here or after Move onto
            // another sheet). First ink adopts its id; Move must not leave that
            // id as this list's blank or placePageBefore(id, id) no-ops.
            if (blank != null && (sliceIds.contains(blank.id)
                    || (blank.paper != null && blank.paper != paper)
                    || (blank.paper != null && blank.paper.indexOfSliceId(blank.id) >= 0)
                    || !blank.isBlank())) {
                trailingBlanks.remove(key);
                blank = null;
            }
            if (blank == null) {
                blank = Board.blank();
                trailingBlanks.put(key, blank);
                cache.put(blank.id, blank);
            }
            blank.paper = paper;
            blank.sheetId = sheetId;
            blank.sliceIndex = paper.sliceCount();
            blank.paperOrigin = paper.origin(paper.sliceCount());
            blank.slicePx = paper.sliceHeight;
            blank.strokes.clear();
            pages.add(blank);
        }
        return pages;
    }

    private void mirrorLog(String sheetId, byte[] bytes) {
        mirrorExecutor.execute(() -> {
            if (bytes == null) {
                mirror.deleteLog(sheetId);
            } else {
                mirror.writeLog(sheetId, bytes);
            }
        });
    }

    private static final class PullFailed extends RuntimeException {
        PullFailed(IOException cause) {
            super(cause);
        }
    }

    // endregion
}
