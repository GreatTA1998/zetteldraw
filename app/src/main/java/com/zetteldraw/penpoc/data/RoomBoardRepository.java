package com.zetteldraw.penpoc.data;

import android.util.Log;

import com.zetteldraw.penpoc.Board;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
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
 * {@link InkSheetStore#flushSheetOrFalse}. Ready {@code .zdl} / LOG is the only
 * ink SoT — no BoardStore JSON import, no {@code .zdi} page files, no BOARD
 * outbox. Live push-only never adopts remote ink; catch-up is Ready
 * {@code notebook_logs} only. Documents index catalogs Ready {@code .zdl} only.
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
    private long lastQueuedAt;
    private final CopyOnWriteArrayList<Runnable> remoteListeners = new CopyOnWriteArrayList<>();
    private final Object refreshLock = new Object();
    private int remoteBatchDepth;
    private boolean refreshChanged;
    private boolean refreshPosted;

    /**
     * Ink-log sheet SoT (Ready + remote apply + length + disk write). Move/tear
     * orchestration still calls into it under {@link #lock}.
     */
    private final InkSheetStore sheetStore;
    /** Notebook / link meta LWW during pull (exception shelf). */
    private final MetaPullApply metaPull;
    private int knownPageHeight;
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

    /** Bridge for {@link MetaPullApply} — Room/outbox stay here. */
    private final class MetaDeps implements MetaPullApply.Deps {
        @Override public ZettelDao dao() { return RoomBoardRepository.this.dao; }
        @Override public boolean pendingOutbox(String entity, String id) {
            return pendingLocked(entity, id);
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
        @Override public boolean allowRemoteInkAdopt() {
            return RoomBoardRepository.this.allowRemoteInkAdopt();
        }
        @Override
        public void bindPaperToBoards(String sheetId, NotebookPaper paper) {
            for (Board board : cache.values()) {
                if (sheetId.equals(board.sheetId)) {
                    board.paper = paper;
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
     * installs a Ready sheet. BoardEntity rows are identity / blank slots —
     * never a paint/list SoT.
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

    /** Always 0 — BoardEntity write-behind queue is gone; Ready flushes are fire-and-forget. */
    int pendingWrites() {
        return 0;
    }

    @Override
    public boolean placePageBefore(String boardId, String beforeBoardId) {
        if (beforeBoardId == null) {
            return false;
        }
        Board before;
        synchronized (lock) {
            before = cache.get(beforeBoardId);
        }
        String dest = before == null ? null : notebookIdOfSheet(before.sheetId);
        return placePageBefore(boardId, beforeBoardId, dest);
    }

    @Override
    public boolean placePageBefore(String boardId, String beforeBoardId, String destNotebookId) {
        if (boardId == null || boardId.equals(beforeBoardId)) {
            return false;
        }
        Board moving;
        Board before;
        synchronized (lock) {
            moving = cache.get(boardId);
            before = beforeBoardId == null ? null : cache.get(beforeBoardId);
        }
        if (moving == null || moving.paper == null) {
            LaunchLog.mark("place-before refused: missing source board or paper");
            return false;
        }
        String destSheetId = NotebookPaper.sheetId(destNotebookId);
        if (knownPageHeight > 0) {
            ensureSheet(destNotebookId, knownPageHeight);
        }
        synchronized (lock) {
            InkSheetStore.OpenSheet dest = sheetStore.get(destSheetId);
            int sourceIndex = moving.paper.indexOfSliceId(boardId);
            if (dest == null || !dest.ready || sourceIndex < 0) {
                LaunchLog.mark("place-before refused: dest not ready or source missing"
                        + " destReady=" + (dest != null && dest.ready)
                        + " sourceIndex=" + sourceIndex);
                return false;
            }
            // Same notebook only when the source already lives on the *destination*
            // sheet — never when a stale on-screen target shares the source paper.
            boolean sameNotebook = moving.paper == dest.paper
                    && java.util.Objects.equals(moving.sheetId, destSheetId);
            // null before, or stale cross-notebook target → append.
            boolean append = beforeBoardId == null
                    || (!sameNotebook && (before == null || before.paper != dest.paper))
                    || (before != null && dest.paper.indexOfSliceId(beforeBoardId) < 0);
            int beforeIndex = append ? -1 : dest.paper.indexOfSliceId(beforeBoardId);
            if (!append && beforeIndex < 0) {
                return false;
            }
            if (append && sameNotebook) {
                int last = dest.paper.sliceCount() - 1;
                if (last < 0 || sourceIndex == last) {
                    return false;
                }
            }
            String sourceSheetId = moving.sheetId;
            int destStrokes = dest.paper.strokes().size();
            try {
                // One paper spine: same-paper tearMove* reorders; cross tears.
                if (append) {
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
                syncAllSliceIndices(moving.paper);
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
     * Ready pages only — {@link #placePageBefore} (same spine as the UI Move
     * menu). Unmigrated / paper-null ids refuse; open via {@link #ensureSheet}
     * first so Move never reseats BoardEntity rows beside Ready.
     */
    @Override
    public boolean movePageToNotebook(String boardId, String notebookId) {
        Board moving;
        synchronized (lock) {
            moving = cache.get(boardId);
        }
        if (moving == null || moving.paper == null) {
            LaunchLog.mark("movePageToNotebook refused: not Ready " + boardId);
            return false;
        }
        if (knownPageHeight > 0) {
            ensureSheet(notebookId, knownPageHeight);
        }
        if (!isSheetReady(notebookId)) {
            LaunchLog.mark("movePageToNotebook refused: dest not Ready " + notebookId);
            return false;
        }
        return placePageBefore(boardId, null, notebookId);
    }

    @Override
    public Board createPage(String notebookId) {
        if (knownPageHeight > 0) {
            ensureSheet(notebookId, knownPageHeight);
        }
        synchronized (lock) {
            String sheetId = NotebookPaper.sheetId(notebookId);
            InkSheetStore.OpenSheet sheet = sheetStore.get(sheetId);
            if (sheet == null || !sheet.ready || sheet.paper == null) {
                LaunchLog.mark("createPage refused: not Ready "
                        + (notebookId == null ? "scratchpad" : notebookId));
                return null;
            }
            String id = sheet.paper.appendBlankPage();
            Board board = cache.get(id);
            if (board == null) {
                board = new Board(id, clock.getAsLong());
                cache.put(id, board);
            }
            board.paper = sheet.paper;
            board.sheetId = sheetId;
            board.sliceIndex = sheet.paper.sliceCount() - 1;
            board.paperOrigin = sheet.paper.origin(board.sliceIndex);
            board.slicePx = sheet.paper.heightAt(board.sliceIndex);
            board.strokes.clear();
            writer.execute(() -> sheetStore.flushSheetOrFalse(sheetId));
            return board;
        }
    }

    @Override
    public void wipePage(String boardId) {
        mutateReadyPage(boardId, false);
    }

    @Override
    public void deletePage(String boardId) {
        mutateReadyPage(boardId, true);
    }

    /**
     * Ready wipe/delete only — paper slice tear (same spine as UI). Unmigrated /
     * paper-null ids refuse; open via {@link #ensureSheet} first so delete never
     * BoardEntity-tombstones beside Ready. Remote page deletes land via Ready
     * {@code notebook_logs} pull (Hand-off requires Ready-cover).
     */
    private void mutateReadyPage(String boardId, boolean delete) {
        synchronized (lock) {
            if (paperSliceActionLocked(boardId, delete)) {
                return;
            }
            LaunchLog.mark((delete ? "deletePage" : "wipePage") + " refused: not Ready " + boardId);
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
            ensureSheet(notebookId, knownPageHeight);
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
            scheduleIndex();
        }
    }

    /**
     * Move Ready paper slices onto Scratchpad (same spine as the Move menu)
     * before a notebook tombstone — local delete or winning remote pull-delete.
     * Ensures first. Returns false when ensure fails while inked BoardEntity
     * rows remain (local delete refuses; pull still tombstones).
     */
    private boolean returnReadyPagesToScratchpad(String notebookId) {
        if (notebookId == null) {
            return true;
        }
        int height = knownPageHeight > 0 ? knownPageHeight : 800;
        if (!ensureSheet(notebookId, height)) {
            // Ensure failed (corrupt leave-intact, etc.) — do not tombstone.
            LaunchLog.mark("returnReady refused: ensure failed " + notebookId);
            return false;
        }
        ensureSheet(null, height);
        // Every Ready slice is real — blank Create-new-page slices return too.
        ArrayList<String> ids = new ArrayList<>();
        for (Board page : pages(notebookId)) {
            ids.add(page.id);
        }
        for (String id : ids) {
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

    /** Drop Ready sheet after a notebook is tombstoned. */
    private void forgetDeletedNotebookUiLocked(String notebookId) {
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
        ArrayList<NotebookLogEntity> logs = new ArrayList<>();
        synchronized (lock) {
            for (OutboxEntry entry : dao.outboxBatch(limit)) {
                // One pick spine: load row or drop stale outbox; remember queuedAt early
                // (attach may still drop log after disk stage).
                if (OutboxEntry.NOTEBOOK.equals(entry.entity)) {
                    takeQueued(entry, dao::notebook, batch.notebooks::add, batch);
                } else if (OutboxEntry.LOG.equals(entry.entity)) {
                    takeQueued(entry, dao::notebookLog, logs::add, batch);
                } else if (OutboxEntry.LINK.equals(entry.entity)) {
                    takeQueued(entry, dao::pageLink, batch.links::add, batch);
                } else {
                    // Unknown / historical entity rows (incl. old BOARD) — drop.
                    dao.deleteOutbox(entry.entity, entry.id);
                }
            }
        }
        // At most one large on-disk blob per batch (Boox heap).
        boolean[] haveLarge = {false};
        HashMap<String, byte[]> smallBlobs = new HashMap<>();
        HashMap<String, File> largeBlobs = new HashMap<>();
        ArrayList<NotebookLogEntity> logsKept = new ArrayList<>();
        for (NotebookLogEntity row : logs) {
            if (stagePushBlob(row.id, row.inkHash, row.deletedAt, ink.logFile(row.id),
                    haveLarge, smallBlobs, largeBlobs, this::readLogQuietly)) {
                logsKept.add(row);
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

    /** Hash-checked pull blob; null = no body / not in page. */
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

    /** Outbox re-read unchanged: same ink meta fields for log (or legacy board attach). */
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

    /**
     * Log blobs are verified and staged (fsync'd) before the lock is taken.
     * Remote BOARD ink is ignored — Hand-off requires Ready-cover; catch-up
     * restores via {@code notebook_logs} only.
     */
    @Override
    public void applyPull(PullPage page) throws IOException {
        HashMap<String, InkSheetStore.StagedLog> stagedLogs = new HashMap<>();
        for (NotebookLogEntity remote : page.logs) {
            byte[] bytes = verifiedPullBytes(page, remote.inkHash, remote.deletedAt, remote.id, "ink log");
            if (bytes == null) {
                continue;
            }
            // Replay once before the lock so a torn log never becomes the notebook.
            stagedLogs.put(remote.id, new InkSheetStore.StagedLog(bytes, NotebookPaper.replay(bytes)));
        }
        // Same Ready return as local deleteNotebook — before tombstone so
        // pages() still sees the sheet.
        for (NotebookEntity remote : page.notebooks) {
            if (remoteNotebookDeleteWins(remote)) {
                returnReadyPagesToScratchpad(remote.id);
            }
        }
        applyPullLocked(page, stagedLogs);
        if (!page.isEmpty()) {
            boolean post;
            synchronized (refreshLock) {
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
     * UI thread, at most one queued at a time: tells each screen to reload.
     * Ready ink lands via {@link InkSheetStore} under the pull lock; screens
     * re-project with {@link #pages} — no BoardEntity stroke swap.
     */
    private void deliverRemoteChanges() {
        synchronized (refreshLock) {
            refreshPosted = false;
            refreshChanged = false;
        }
        LaunchLog.mark("sync result on screen: " + remoteListeners.size() + " screens refreshed");
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

    private void applyPullLocked(PullPage page, Map<String, InkSheetStore.StagedLog> stagedLogs)
            throws IOException {
        synchronized (lock) {
            // Omitted-blob policy lives in InkSheetStore (hash match ⇒ keep local).
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
                        // Ready .zdl is the only remote ink SoT on catch-up / Hand-off.
                        for (NotebookLogEntity remote : page.logs) {
                            sheetStore.applyRemoteLogLocked(remote, stagedLogs, mirrorOps);
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

    @Override
    public boolean reorderNotebook(String notebookId, String beforeSiblingId) {
        if (notebookId == null || notebookId.equals(beforeSiblingId)) {
            return false;
        }
        synchronized (lock) {
            NotebookEntity row = dao.notebook(notebookId);
            if (row == null || row.deletedAt != null) {
                return false;
            }
            ArrayList<NotebookEntity> siblings = new ArrayList<>();
            for (NotebookEntity each : dao.liveNotebooks()) {
                if (Objects.equals(each.parentId, row.parentId)) {
                    siblings.add(each);
                }
            }
            int from = indexOfNotebook(siblings, notebookId);
            if (from < 0) {
                return false;
            }
            int insertAt = siblings.size();
            if (beforeSiblingId != null) {
                NotebookEntity before = dao.notebook(beforeSiblingId);
                if (before == null || before.deletedAt != null
                        || !Objects.equals(before.parentId, row.parentId)) {
                    return false;
                }
                insertAt = indexOfNotebook(siblings, beforeSiblingId);
                if (insertAt < 0) {
                    return false;
                }
            }
            // Destination index in the list with {@code row} removed.
            int dest = insertAt > from ? insertAt - 1 : insertAt;
            if (dest == from) {
                return true;
            }
            siblings.remove(from);
            String left = dest > 0 ? siblings.get(dest - 1).position : null;
            String right = dest < siblings.size() ? siblings.get(dest).position : null;
            String next = Positions.between(left, right);
            if (next.equals(row.position)) {
                return true;
            }
            row.position = next;
            row.updatedAt = clock.getAsLong();
            saveNotebookQueued(row);
            return true;
        }
    }

    private static int indexOfNotebook(List<NotebookEntity> rows, String id) {
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).id.equals(id)) {
                return i;
            }
        }
        return -1;
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

    private List<BoardEntity> rowsLocked(String notebookId) {
        return notebookId == null ? dao.scratchpadBoards() : dao.notebookBoards(notebookId);
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

    /** Null on IO failure — quiet {@code .zdl} read for push staging. */
    private byte[] readLogQuietly(String sheetId) {
        try {
            return ink.readLog(sheetId);
        } catch (IOException e) {
            Log.e(TAG, "ink log read failed " + sheetId, e);
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
            // Documents mirrors Ready .zdl only (v137+); catalog matches that SoT.
            root.put("ink_format", "zdl");
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
            JSONArray logs = new JSONArray();
            for (NotebookLogEntity row : dao.liveNotebookLogs()) {
                JSONObject log = new JSONObject()
                        .put("id", row.id)
                        .put("notebook_id", row.notebookId == null ? JSONObject.NULL : row.notebookId)
                        .put("ink_hash", row.inkHash == null ? JSONObject.NULL : row.inkHash)
                        .put("ink_bytes", row.inkBytes)
                        .put("slice_height", row.sliceHeight)
                        .put("file", row.inkHash == null ? JSONObject.NULL : "ink/" + row.id + ".zdl")
                        .put("created_at", row.createdAt)
                        .put("updated_at", row.updatedAt);
                if (row.conflictOf != null) {
                    log.put("conflict_of", row.conflictOf);
                }
                logs.put(log);
            }
            root.put("logs", logs);
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
    public boolean ensureSheet(String notebookId, int pageHeight) {
        notebookReads.incrementAndGet();
        if (pageHeight <= 0) {
            return false;
        }
        knownPageHeight = pageHeight;
        // One path: full Ready replay only — no slices-before-strokes structure dual.
        return sheetStore.ensureSheet(notebookId, pageHeight);
    }

    @Override
    public boolean isSheetEnsuring(String notebookId) {
        return sheetStore.isEnsuring(notebookId);
    }

    /** Ready slices only — no ephemeral trailing blank. */
    private List<Board> projectLocked(String notebookId, NotebookPaper paper) {
        String sheetId = NotebookPaper.sheetId(notebookId);
        ArrayList<Board> pages = new ArrayList<>();
        for (int i = 0; i < paper.sliceCount(); i++) {
            String id = paper.sliceId(i);
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
