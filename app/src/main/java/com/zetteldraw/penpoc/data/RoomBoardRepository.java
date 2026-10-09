package com.zetteldraw.penpoc.data;

import android.os.SystemClock;
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
import java.util.function.LongSupplier;

/**
 * Room + ink files. One lock guards the database rows, the ink directory's
 * committed files and the shared {@link Board} cache, and it is only ever
 * held for short metadata work: no ink encoding, no fsync'd data write, no
 * mirror I/O and no network happens while it is held.
 *
 * <p>Ink saves are write-behind. {@link #saveInk} takes an immutable snapshot
 * of the page's strokes and returns; one serialized writer thread encodes it,
 * writes and fsyncs a staged file without the lock, then commits (rename +
 * one small transaction) under the lock. Saves of the same page coalesce, so
 * a slow disk costs one write per page, not one per stroke.
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
    private static final long SLOW_WRITE_MS = 250;
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
    private final AtomicBoolean warmScheduled = new AtomicBoolean();
    /** Unsaved blank page at the end of each page list, keyed by {@link #listKey}. */
    private final HashMap<String, Board> trailingBlanks = new HashMap<>();
    /** Newest unwritten snapshot per board; present from {@link #saveInk} until the writer commits it. */
    private final HashMap<String, PendingInk> pendingInk = new HashMap<>();
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

    /** Open notebook logs, keyed by sheet id. The pen appends; it does not rewrite them. */
    private final HashMap<String, OpenSheet> sheets = new HashMap<>();
    private int knownPageHeight;
    private int knownLegacyHeight;
    private long knownShortPagesSince;

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
            ensureTrailingBlankLocked(null);
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
     * Ink of pages not yet in memory is read and decoded without the lock,
     * so a large list never blocks the notebook list, saves or other loads.
     * Only pages that keep changing under it (two rounds) decode under the lock.
     */
    @Override
    public List<Board> pages(String notebookId) {
        notebookReads.incrementAndGet();
        synchronized (lock) {
            OpenSheet sheet = sheets.get(NotebookPaper.sheetId(notebookId));
            if (sheet != null && sheet.ready) {
                return projectLocked(notebookId, sheet.paper);
            }
        }
        List<Board> pages = null;
        for (int round = 0; pages == null; round++) {
            List<BoardEntity> uncached;
            synchronized (lock) {
                if (notebookId != null) {
                    NotebookEntity notebook = dao.notebook(notebookId);
                    if (notebook == null || notebook.deletedAt != null) {
                        return new ArrayList<>();
                    }
                }
                ensureTrailingBlankLocked(notebookId);
                List<BoardEntity> rows = rowsLocked(notebookId);
                uncached = uncachedInkLocked(rows);
                if (uncached.isEmpty() || round >= 2) {
                    pages = toBoards(rows);
                    Board blank = trailingBlanks.get(listKey(notebookId));
                    if (blank != null) {
                        pages.add(blank);
                    }
                    continue;
                }
            }
            decodeIntoCache(uncached);
        }
        scheduleWarmCache();
        return pages;
    }

    private List<BoardEntity> uncachedInkLocked(List<BoardEntity> rows) {
        ArrayList<BoardEntity> uncached = new ArrayList<>();
        for (BoardEntity row : rows) {
            if (row.inkHash != null && !cache.containsKey(row.id)) {
                uncached.add(row);
            }
        }
        return uncached;
    }

    /**
     * Reads and decodes each row's ink without the lock, then caches it only
     * if the row still has that ink and nothing cached the board meanwhile.
     * Returns how many boards it cached.
     */
    private int decodeIntoCache(List<BoardEntity> rows) {
        int cached = 0;
        for (BoardEntity row : rows) {
            synchronized (lock) {
                if (cache.containsKey(row.id)) {
                    continue;
                }
            }
            byte[] bytes = readInkQuietly(row.id);
            if (bytes == null) {
                continue;
            }
            String hash = InkFileStore.sha256(bytes);
            List<InkRenderer.InkStroke> strokes;
            try {
                strokes = InkCodec.decode(bytes);
            } catch (IOException e) {
                continue;
            }
            synchronized (lock) {
                BoardEntity now = dao.board(row.id);
                if (cache.containsKey(row.id) || now == null || now.deletedAt != null
                        || !hash.equals(now.inkHash)) {
                    continue;
                }
                Board board = new Board(row.id, now.createdAt);
                board.strokes.addAll(strokes);
                cache.put(row.id, board);
                cached++;
            }
        }
        return cached;
    }

    /**
     * Snapshots the strokes and returns; the writer thread persists the
     * snapshot. Blankness, the trailing blank page and sync decisions all see
     * the snapshot immediately through {@link #isBlankLocked}.
     */
    @Override
    public void saveInk(Board page) {
        if (page == null) {
            return;
        }
        if (page.paper != null) {
            writer.execute(() -> flushSheet(page.sheetId));
            return;
        }
        List<InkRenderer.InkStroke> snapshot = Collections.unmodifiableList(new ArrayList<>(page.strokes));
        synchronized (lock) {
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
                row.updatedAt = clock.getAsLong();
                cache.put(page.id, page);
                BoardEntity toInsert = row;
                db.runInTransaction(() -> dao.upsertBoard(toInsert));
            } else if (row.deletedAt != null) {
                return;
            }
            localEdits.merge(page.id, 1L, Long::sum);
            PendingInk previous = pendingInk.put(page.id, new PendingInk(snapshot, clock.getAsLong()));
            if (previous == null || previous.failed) {
                String id = page.id;
                writer.execute(() -> flushInk(id));
            }
            // Ink on the last page grows a new blank; emptying it (erase, undo) collapses the tail again.
            if (snapshot.isEmpty()) {
                trimTrailingBlanksLocked(row.notebookId);
            }
            ensureTrailingBlankLocked(row.notebookId);
        }
    }

    /** Boards with a snapshot not yet on disk. */
    int pendingWrites() {
        synchronized (lock) {
            return pendingInk.size();
        }
    }

    /**
     * Writer thread: persists the newest snapshot of one board. Encoding and
     * the fsync'd staged write run without the lock; the commit re-checks that
     * the snapshot is still the newest and the board still live.
     */
    private void flushInk(String id) {
        PendingInk pending;
        synchronized (lock) {
            pending = pendingInk.get(id);
            if (pending == null) {
                return;
            }
        }
        long started = System.nanoTime();
        byte[] bytes = pending.strokes.isEmpty() ? null : InkCodec.encode(pending.strokes);
        String hash = bytes == null ? null : InkFileStore.sha256(bytes);
        File staged = null;
        IOException failure = null;
        try {
            if (bytes != null) {
                staged = ink.stage(id, "zdi", bytes);
            }
        } catch (IOException e) {
            failure = e;
        }
        boolean again;
        synchronized (lock) {
            PendingInk current = pendingInk.get(id);
            if (current != pending) {
                ink.discard(staged);
                again = current != null;
            } else {
                if (failure == null) {
                    try {
                        commitInkLocked(id, pending, bytes, hash, staged);
                    } catch (IOException e) {
                        failure = e;
                    }
                }
                if (failure == null) {
                    pendingInk.remove(id);
                } else {
                    Log.e(TAG, "ink write failed " + id + "; retried on the next save", failure);
                    ink.discard(staged);
                    pending.failed = true;
                }
                again = false;
            }
        }
        long ms = (System.nanoTime() - started) / 1_000_000;
        if (ms >= SLOW_WRITE_MS) {
            Log.w(TAG, "slow ink write " + id + ": " + ms + " ms");
        }
        if (again) {
            writer.execute(() -> flushInk(id));
        }
    }

    private void commitInkLocked(String id, PendingInk pending, byte[] bytes, String hash, File staged)
            throws IOException {
        BoardEntity row = dao.board(id);
        if (row == null || row.deletedAt != null || Objects.equals(row.inkHash, hash)) {
            ink.discard(staged);
            return;
        }
        if (bytes == null) {
            ink.delete(id);
        } else {
            ink.commit(staged, id);
        }
        row.inkHash = hash;
        row.inkBytes = bytes == null ? 0 : bytes.length;
        row.updatedAt = Math.max(row.updatedAt, pending.editedAt);
        db.runInTransaction(() -> {
            dao.upsertBoard(row);
            queueLocked(OutboxEntry.BOARD, row.id, OutboxEntry.UPSERT);
        });
        mirrorInk(id, bytes);
        scheduleIndex();
    }

    /** The stored blank that ends its list; the unsaved trailing blank has no row at all. */
    private boolean isTrailingBlankLocked(BoardEntity row) {
        if (!isBlankLocked(row) || trailingBlanks.containsKey(listKey(row.notebookId))) {
            return false;
        }
        List<BoardEntity> rows = rowsLocked(row.notebookId);
        return !rows.isEmpty() && rows.get(rows.size() - 1).id.equals(row.id);
    }

    /** A blank board has no ink on disk and no unwritten strokes. */
    private boolean isBlankLocked(BoardEntity row) {
        PendingInk pending = pendingInk.get(row.id);
        return pending != null ? pending.strokes.isEmpty() : row.inkHash == null;
    }

    /**
     * Writer thread, at startup: decodes every live page into the cache so
     * switching lists rarely waits on ink files. One page per writer task, so
     * an ink save queued meanwhile is written after at most one page.
     */
    /** After the first page list is read, so warming never competes with what the screen waits for. */
    private void scheduleWarmCache() {
        if (warmScheduled.compareAndSet(false, true)) {
            writer.execute(this::warmCache);
        }
    }

    private void warmCache() {
        long started = SystemClock.uptimeMillis();
        List<BoardEntity> rows;
        synchronized (lock) {
            rows = new ArrayList<>();
            for (BoardEntity row : dao.liveBoardsForIndex()) {
                if (row.inkHash != null && row.conflictOf == null) {
                    rows.add(row);
                }
            }
        }
        warmNext(rows, 0, 0, started);
    }

    private void warmNext(List<BoardEntity> rows, int index, int decoded, long started) {
        if (index >= rows.size()) {
            LaunchLog.mark("ink cache warm: " + decoded + " of " + rows.size() + " inked pages decoded in "
                    + (SystemClock.uptimeMillis() - started) + " ms");
            return;
        }
        int now = decoded + decodeIntoCache(Collections.singletonList(rows.get(index)));
        writer.execute(() -> warmNext(rows, index + 1, now, started));
    }

    @Override
    public void movePageToNotebook(String boardId, String notebookId) {
        Board moving;
        synchronized (lock) {
            moving = cache.get(boardId);
        }
        if (moving != null && moving.paper != null) {
            if (knownPageHeight > 0) {
                ensureSheet(notebookId, knownPageHeight, knownLegacyHeight, knownShortPagesSince);
            }
            synchronized (lock) {
                OpenSheet dest = sheets.get(NotebookPaper.sheetId(notebookId));
                if (dest != null && dest.ready && moving.sliceIndex >= 0
                        && moving.sliceIndex < moving.paper.sliceCount()) {
                    moving.paper.tearMove(moving.sliceIndex, dest.paper);
                    // The slice id does not survive the move, so a link to it would name a missing page.
                    tombstoneLinksTouchingLocked(boardId);
                    writer.execute(() -> flushSheet(NotebookPaper.sheetId(notebookId)));
                    writer.execute(() -> flushSheet(moving.sheetId));
                }
            }
            return;
        }
        synchronized (lock) {
            NotebookEntity target = dao.notebook(notebookId);
            if (target == null || target.deletedAt != null) {
                return;
            }
            BoardEntity row = dao.board(boardId);
            String source;
            if (row == null) {
                String key = listOfBlankLocked(boardId);
                if (key == null) {
                    return;
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
            trimTrailingBlanksLocked(source);
            ensureTrailingBlankLocked(source);
            ensureTrailingBlankLocked(notebookId);
            scheduleIndex();
        }
    }

    @Override
    public void wipePage(String boardId) {
        synchronized (lock) {
            Board board = cache.get(boardId);
            if (board != null && board.paper != null) {
                if (board.sliceIndex >= 0 && board.sliceIndex < board.paper.sliceCount()) {
                    board.paper.tearWipe(board.sliceIndex);
                    String sheetId = board.sheetId;
                    writer.execute(() -> flushSheet(sheetId));
                }
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
            saveInk(board);
            trimTrailingBlanksLocked(row.notebookId);
            ensureTrailingBlankLocked(row.notebookId);
        }
    }

    @Override
    public void deletePage(String boardId) {
        synchronized (lock) {
            if (deletePaperSliceLocked(boardId)) {
                return;
            }
            Board board = cache.get(boardId);
            if (board != null && board.paper != null) {
                // The trailing blank is not a slice yet, and it cannot be deleted.
                return;
            }
            BoardEntity row = dao.board(boardId);
            if (row == null || row.deletedAt != null || row.conflictOf != null || isTrailingBlankLocked(row)) {
                return;
            }
            tombstoneLocked(row);
            tombstoneLinksTouchingLocked(row.id);
            trimTrailingBlanksLocked(row.notebookId);
            ensureTrailingBlankLocked(row.notebookId);
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
            found = placeIn(info.id, pageId);
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
            db.runInTransaction(() -> {
                dao.upsertNotebook(row);
                queueLocked(OutboxEntry.NOTEBOOK, row.id, OutboxEntry.UPSERT);
            });
            scheduleIndex();
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
            db.runInTransaction(() -> {
                dao.upsertNotebook(row);
                queueLocked(OutboxEntry.NOTEBOOK, row.id, OutboxEntry.UPSERT);
            });
            scheduleIndex();
        }
    }

    @Override
    public void deleteNotebook(String notebookId) {
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
                returnPagesToScratchpadLocked(notebookId);
                promoteChildrenLocked(notebookId, promoteTo);
            });
            Board blank = trailingBlanks.remove(listKey(notebookId));
            if (blank != null) {
                cache.remove(blank.id);
            }
            trimTrailingBlanksLocked(null);
            ensureTrailingBlankLocked(null);
            scheduleIndex();
        }
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
        HashMap<String, Long> boardQueuedAt = new HashMap<>();
        HashMap<String, Long> logQueuedAt = new HashMap<>();
        synchronized (lock) {
            for (OutboxEntry entry : dao.outboxBatch(limit)) {
                if (OutboxEntry.NOTEBOOK.equals(entry.entity)) {
                    NotebookEntity row = dao.notebook(entry.id);
                    if (row == null) {
                        dao.deleteOutbox(entry.entity, entry.id);
                        continue;
                    }
                    batch.notebooks.add(row);
                    batch.queuedAt.put(entry.entity + ":" + entry.id, entry.queuedAt);
                } else if (OutboxEntry.LOG.equals(entry.entity)) {
                    NotebookLogEntity row = dao.notebookLog(entry.id);
                    if (row == null) {
                        dao.deleteOutbox(entry.entity, entry.id);
                        continue;
                    }
                    logs.add(row);
                    logQueuedAt.put(row.id, entry.queuedAt);
                } else if (OutboxEntry.LINK.equals(entry.entity)) {
                    PageLinkEntity row = dao.pageLink(entry.id);
                    if (row == null) {
                        dao.deleteOutbox(entry.entity, entry.id);
                        continue;
                    }
                    batch.links.add(row);
                    batch.queuedAt.put(entry.entity + ":" + entry.id, entry.queuedAt);
                } else if (OutboxEntry.BOARD.equals(entry.entity)) {
                    BoardEntity row = dao.board(entry.id);
                    if (row == null) {
                        dao.deleteOutbox(entry.entity, entry.id);
                        continue;
                    }
                    boards.add(row);
                    boardQueuedAt.put(row.id, entry.queuedAt);
                } else {
                    dao.deleteOutbox(entry.entity, entry.id);
                }
            }
        }
        HashMap<String, byte[]> files = new HashMap<>();
        for (BoardEntity row : boards) {
            if (row.inkHash != null && row.deletedAt == null) {
                byte[] bytes = readInkQuietly(row.id);
                if (bytes != null) {
                    files.put(row.id, bytes);
                }
            }
        }
        HashMap<String, byte[]> logFiles = new HashMap<>();
        for (NotebookLogEntity row : logs) {
            if (row.inkHash != null && row.deletedAt == null) {
                byte[] bytes = readLogQuietly(row.id);
                if (bytes != null) {
                    logFiles.put(row.id, bytes);
                }
            }
        }
        synchronized (lock) {
            for (NotebookLogEntity seen : logs) {
                NotebookLogEntity row = dao.notebookLog(seen.id);
                if (row == null || !sameLog(seen, row)) {
                    continue;
                }
                byte[] bytes = logFiles.get(row.id);
                if (bytes != null) {
                    String actual = InkFileStore.sha256(bytes);
                    if (!actual.equals(row.inkHash)) {
                        row.inkHash = actual;
                        row.inkBytes = bytes.length;
                        dao.upsertNotebookLog(row);
                    }
                    batch.blobs.put(actual, bytes);
                }
                batch.logs.add(row);
                batch.queuedAt.put(OutboxEntry.LOG + ":" + row.id, logQueuedAt.get(row.id));
            }
            for (BoardEntity seen : boards) {
                BoardEntity row = dao.board(seen.id);
                if (row == null || !sameRow(seen, row)) {
                    continue;
                }
                byte[] bytes = files.get(row.id);
                if (bytes != null) {
                    String actual = InkFileStore.sha256(bytes);
                    if (!actual.equals(row.inkHash)) {
                        // The row did not change while the file was read, so this is a crash
                        // between a file commit and its row update; the file is newer.
                        row.inkHash = actual;
                        row.inkBytes = bytes.length;
                        dao.upsertBoard(row);
                    }
                    batch.blobs.put(actual, bytes);
                }
                batch.boards.add(row);
                batch.queuedAt.put(OutboxEntry.BOARD + ":" + row.id, boardQueuedAt.get(row.id));
            }
        }
        return batch;
    }

    private static boolean sameLog(NotebookLogEntity a, NotebookLogEntity b) {
        return Objects.equals(a.inkHash, b.inkHash) && Objects.equals(a.deletedAt, b.deletedAt)
                && a.updatedAt == b.updatedAt && a.rev == b.rev;
    }

    private static boolean sameRow(BoardEntity a, BoardEntity b) {
        return Objects.equals(a.inkHash, b.inkHash) && Objects.equals(a.deletedAt, b.deletedAt)
                && a.updatedAt == b.updatedAt && a.rev == b.rev;
    }

    @Override
    public void applyPushResults(PushBatch batch, List<PushResult> results) {
        synchronized (lock) {
            db.runInTransaction(() -> {
                for (PushResult result : results) {
                    Long queuedAt = batch.queuedAt.get(result.entity + ":" + result.id);
                    if (queuedAt == null) {
                        continue;
                    }
                    switch (result.status) {
                        case PushResult.APPLIED:
                        case PushResult.CONFLICT_WON:
                            setRevLocked(result.entity, result.id, result.rev);
                            dao.deleteOutboxIfUnchanged(result.entity, result.id, queuedAt);
                            break;
                        case PushResult.CONFLICT_LOST:
                            // The server kept its row and stored ours as a conflict copy;
                            // the next pull brings both down.
                            dao.deleteOutboxIfUnchanged(result.entity, result.id, queuedAt);
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

    /**
     * Blobs are verified, decoded and staged (fsync'd) before the lock is
     * taken; under the lock each applied board only renames its staged file.
     */
    @Override
    public void applyPull(PullPage page) throws IOException {
        HashMap<String, List<InkRenderer.InkStroke>> refreshed = new HashMap<>();
        HashMap<String, Long> editsSeen = new HashMap<>();
        HashMap<String, Board> boardsSeen = new HashMap<>();
        HashMap<String, Staged> staged = new HashMap<>();
        HashMap<String, byte[]> logBytes = new HashMap<>();
        try {
            for (NotebookLogEntity remote : page.logs) {
                if (remote.inkHash == null || remote.deletedAt != null) {
                    continue;
                }
                byte[] bytes = page.blobs.get(remote.inkHash);
                if (bytes == null) {
                    continue;
                }
                if (!InkFileStore.sha256(bytes).equals(remote.inkHash)) {
                    throw new IOException("bad ink log for " + remote.id);
                }
                // Replay now, before the lock, so a torn file never becomes the notebook.
                NotebookPaper.replace(bytes);
                logBytes.put(remote.id, bytes);
            }
            for (BoardEntity remote : page.boards) {
                if (remote.inkHash == null || remote.deletedAt != null) {
                    continue;
                }
                byte[] bytes = page.blobs.get(remote.inkHash);
                if (bytes == null) {
                    continue;
                }
                if (!InkFileStore.sha256(bytes).equals(remote.inkHash)) {
                    throw new IOException("bad ink blob for " + remote.id);
                }
                List<InkRenderer.InkStroke> strokes;
                try {
                    strokes = InkCodec.decode(bytes);
                } catch (IOException e) {
                    // Kept byte-for-byte (e.g. a newer ink format); it must not block the rest of the pull.
                    Log.w(TAG, "undecodable ink for " + remote.id, e);
                    strokes = new ArrayList<>();
                }
                staged.put(remote.id, new Staged(ink.stage(remote.id, "pull", bytes), bytes, strokes));
            }
            applyPullLocked(page, staged, logBytes, refreshed, editsSeen, boardsSeen);
        } finally {
            for (Staged s : staged.values()) {
                ink.discard(s.file);
            }
        }
        if (!page.boards.isEmpty() || !page.notebooks.isEmpty() || !page.logs.isEmpty() || !page.links.isEmpty()) {
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
            if (Objects.equals(localEdits.get(r.board.id), r.editsSeen)) {
                r.board.strokes.clear();
                r.board.strokes.addAll(r.strokes);
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

    private static final class Refresh {
        final Board board;
        final List<InkRenderer.InkStroke> strokes;
        final Long editsSeen;

        Refresh(Board board, List<InkRenderer.InkStroke> strokes, Long editsSeen) {
            this.board = board;
            this.strokes = strokes;
            this.editsSeen = editsSeen;
        }
    }

    private void applyPullLocked(PullPage page, Map<String, Staged> staged, Map<String, byte[]> logBytes,
                                 Map<String, List<InkRenderer.InkStroke>> refreshed,
                                 Map<String, Long> editsSeen, Map<String, Board> boardsSeen) throws IOException {
        synchronized (lock) {
            for (BoardEntity remote : page.boards) {
                if (remote.inkHash == null || remote.deletedAt != null || page.blobs.containsKey(remote.inkHash)) {
                    continue;
                }
                BoardEntity local = dao.board(remote.id);
                if (local == null || !remote.inkHash.equals(local.inkHash)) {
                    throw new IOException("pull page is missing ink " + remote.inkHash);
                }
            }
            for (NotebookLogEntity remote : page.logs) {
                if (remote.inkHash == null || remote.deletedAt != null || page.blobs.containsKey(remote.inkHash)) {
                    continue;
                }
                NotebookLogEntity local = dao.notebookLog(remote.id);
                if (local == null || !remote.inkHash.equals(local.inkHash)) {
                    throw new IOException("pull page is missing ink log " + remote.inkHash);
                }
            }
            ArrayList<Runnable> mirrorOps = new ArrayList<>();
            try {
                db.runInTransaction(() -> {
                    try {
                        for (NotebookEntity remote : page.notebooks) {
                            applyRemoteNotebookLocked(remote);
                        }
                        for (BoardEntity remote : page.boards) {
                            applyRemoteBoardLocked(remote, staged, refreshed, mirrorOps);
                        }
                        for (NotebookLogEntity remote : page.logs) {
                            applyRemoteLogLocked(remote, logBytes, mirrorOps);
                        }
                        for (PageLinkEntity remote : page.links) {
                            applyRemoteLinkLocked(remote);
                        }
                        for (NotebookEntity remote : page.notebooks) {
                            NotebookEntity now = dao.notebook(remote.id);
                            if (now != null && now.deletedAt != null) {
                                // Pages still filed here (e.g. moved in on this device after the
                                // other device deleted it) go back to the scratchpad.
                                // Children still pointing here are promoted, including one whose
                                // own row was not in this pull page.
                                returnPagesToScratchpadLocked(remote.id);
                                promoteChildrenLocked(remote.id, now.parentId);
                            }
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
            if (!page.boards.isEmpty() || !page.notebooks.isEmpty() || !page.logs.isEmpty() || !page.links.isEmpty()) {
                scheduleIndex();
            }
        }
    }

    private static final class Staged {
        final File file;
        final byte[] bytes;
        final List<InkRenderer.InkStroke> strokes;

        Staged(File file, byte[] bytes, List<InkRenderer.InkStroke> strokes) {
            this.file = file;
            this.bytes = bytes;
            this.strokes = strokes;
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
            ensureTrailingBlankLocked(null);
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
            db.runInTransaction(() -> {
                dao.upsertNotebook(row);
                queueLocked(OutboxEntry.NOTEBOOK, row.id, OutboxEntry.UPSERT);
            });
            scheduleIndex();
            return true;
        }
    }

    /** Moves the notebook's live pages to the end of the scratchpad, keeping their order. */
    private void returnPagesToScratchpadLocked(String notebookId) {
        List<BoardEntity> pages = dao.notebookBoards(notebookId);
        String last = dao.lastScratchpadPosition();
        long now = clock.getAsLong();
        for (BoardEntity page : pages) {
            last = Positions.after(last);
            page.notebookId = null;
            page.position = last;
            page.updatedAt = now;
            dao.upsertBoard(page);
            queueLocked(OutboxEntry.BOARD, page.id, OutboxEntry.UPSERT);
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
            queueLocked(OutboxEntry.NOTEBOOK, row.id, OutboxEntry.UPSERT);
        }
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

    private Board ensureTrailingBlankLocked(String notebookId) {
        String key = listKey(notebookId);
        Board blank = trailingBlanks.get(key);
        if (blank != null) {
            return blank;
        }
        List<BoardEntity> rows = rowsLocked(notebookId);
        if (!rows.isEmpty() && isBlankLocked(rows.get(rows.size() - 1))) {
            return load(rows.get(rows.size() - 1));
        }
        blank = Board.blank();
        trailingBlanks.put(key, blank);
        cache.put(blank.id, blank);
        return blank;
    }

    /** Keep at most one blank page at the end of the list. */
    private void trimTrailingBlanksLocked(String notebookId) {
        String key = listKey(notebookId);
        while (true) {
            List<BoardEntity> rows = rowsLocked(notebookId);
            Board blank = trailingBlanks.get(key);
            int n = rows.size() + (blank != null ? 1 : 0);
            if (n < 2) {
                return;
            }
            boolean lastBlank = blank != null || isBlankLocked(rows.get(rows.size() - 1));
            BoardEntity prev = blank != null ? rows.get(rows.size() - 1) : rows.get(rows.size() - 2);
            if (!lastBlank || !isBlankLocked(prev)) {
                return;
            }
            if (blank != null) {
                String blankId = blank.id;
                cache.remove(blank.id);
                trailingBlanks.remove(key);
                tombstoneLinksTouchingLocked(blankId);
            } else {
                BoardEntity dropped = rows.get(rows.size() - 1);
                tombstoneLocked(dropped);
                tombstoneLinksTouchingLocked(dropped.id);
            }
        }
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
        pendingInk.remove(row.id);
        mirrorInk(row.id, null);
        scheduleIndex();
    }

    /** Finds the slice by id on an open sheet, so a stale cache cannot delete the wrong page. */
    private boolean deletePaperSliceLocked(String boardId) {
        if (boardId == null) {
            return false;
        }
        for (Map.Entry<String, OpenSheet> entry : sheets.entrySet()) {
            OpenSheet sheet = entry.getValue();
            if (!sheet.ready || sheet.paper == null) {
                continue;
            }
            NotebookPaper paper = sheet.paper;
            for (int i = 0; i < paper.sliceCount(); i++) {
                if (!boardId.equals(paper.sliceId(i))) {
                    continue;
                }
                paper.tearDelete(i);
                tombstoneLinksTouchingLocked(boardId);
                String sheetId = entry.getKey();
                writer.execute(() -> flushSheet(sheetId));
                return true;
            }
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

    private void applyRemoteNotebookLocked(NotebookEntity remote) {
        NotebookEntity local = dao.notebook(remote.id);
        if (local != null) {
            if (remote.rev <= local.rev) {
                return;
            }
            if (pendingLocked(OutboxEntry.NOTEBOOK, remote.id)
                    && !Lww.remoteWins(local.updatedAt, remote.updatedAt)) {
                return;
            }
        }
        dao.upsertNotebook(remote);
        dao.deleteOutbox(OutboxEntry.NOTEBOOK, remote.id);
    }

    /**
     * A pulled log replaces the notebook's log whole. It does not splice strokes
     * and it does not delete the pre-migration page files.
     */
    private void applyRemoteLogLocked(NotebookLogEntity remote, Map<String, byte[]> logBytes,
                                      List<Runnable> mirrorOps) throws IOException {
        if (remote.conflictOf != null) {
            byte[] hidden = logBytes.remove(remote.id);
            if (hidden != null) {
                ink.writeLog(remote.id, hidden);
                byte[] copy = hidden;
                mirrorOps.add(() -> mirrorLog(remote.id, copy));
            }
            dao.upsertNotebookLog(remote);
            dao.deleteOutbox(OutboxEntry.LOG, remote.id);
            return;
        }
        NotebookLogEntity local = dao.notebookLog(remote.id);
        OpenSheet open = sheets.get(remote.id);
        if (local != null) {
            if (remote.rev <= local.rev) {
                return;
            }
            if (open != null && open.ready && open.paper.bytes().length > open.flushed) {
                return;
            }
            if (pendingLocked(OutboxEntry.LOG, remote.id)
                    && !Lww.remoteWins(local.updatedAt, remote.updatedAt)) {
                return;
            }
        }
        if (remote.deletedAt == null && remote.inkHash != null) {
            byte[] bytes = logBytes.remove(remote.id);
            if (bytes == null) {
                throw new IOException("bad ink log for " + remote.id);
            }
            NotebookPaper paper = NotebookPaper.replace(bytes);
            ink.writeLog(remote.id, bytes);
            mirrorOps.add(() -> mirrorLog(remote.id, bytes));
            if (open == null) {
                open = new OpenSheet(remote.id, remote.notebookId, paper, bytes.length);
                sheets.put(remote.id, open);
            } else {
                open.paper = paper;
                open.flushed = bytes.length;
            }
            open.ready = true;
            for (Board board : cache.values()) {
                if (remote.id.equals(board.sheetId)) {
                    board.paper = paper;
                }
            }
            for (Board blank : trailingBlanks.values()) {
                if (remote.id.equals(blank.sheetId)) {
                    blank.paper = paper;
                }
            }
        }
        dao.upsertNotebookLog(remote);
        dao.deleteOutbox(OutboxEntry.LOG, remote.id);
    }

    private void applyRemoteLinkLocked(PageLinkEntity remote) {
        PageLinkEntity local = dao.pageLink(remote.id);
        if (local != null) {
            if (remote.rev <= local.rev) {
                return;
            }
            if (pendingLocked(OutboxEntry.LINK, remote.id)
                    && !Lww.remoteWins(local.updatedAt, remote.updatedAt)) {
                return;
            }
        }
        dao.upsertPageLink(remote);
        dao.deleteOutbox(OutboxEntry.LINK, remote.id);
    }

    private void applyRemoteBoardLocked(BoardEntity remote, Map<String, Staged> staged,
                                        Map<String, List<InkRenderer.InkStroke>> refreshed,
                                        List<Runnable> mirrorOps) throws IOException {
        BoardEntity local = dao.board(remote.id);
        if (local != null) {
            if (remote.rev <= local.rev) {
                return;
            }
            if (pendingInk.containsKey(remote.id)) {
                // Drawn on this device and not yet written: that edit is the newest and pushes after.
                return;
            }
            if (pendingLocked(OutboxEntry.BOARD, remote.id)) {
                if (!Lww.remoteWins(local.updatedAt, remote.updatedAt)) {
                    // Local edit is newer; it stays queued with its old base rev and the
                    // server keeps its current row as a conflict copy on the next push.
                    return;
                }
                if (Lww.needsConflictCopy(local, remote)) {
                    conflictCopyLocked(local, mirrorOps);
                }
            }
        }
        if (remote.deletedAt != null || remote.inkHash == null) {
            ink.delete(remote.id);
            String id = remote.id;
            mirrorOps.add(() -> mirrorInk(id, null));
            refreshed.put(remote.id, new ArrayList<>());
        } else if (local == null || !remote.inkHash.equals(local.inkHash)) {
            Staged blob = staged.remove(remote.id);
            if (blob == null) {
                throw new IOException("bad ink blob for " + remote.id);
            }
            ink.commit(blob.file, remote.id);
            String id = remote.id;
            mirrorOps.add(() -> mirrorInk(id, blob.bytes));
            refreshed.put(remote.id, blob.strokes);
        }
        dao.upsertBoard(remote);
        dao.deleteOutbox(OutboxEntry.BOARD, remote.id);
        if (remote.deletedAt != null) {
            cache.remove(remote.id);
        }
    }

    private void conflictCopyLocked(BoardEntity loser, List<Runnable> mirrorOps) throws IOException {
        BoardEntity copy = loser.copy();
        copy.id = UUID.randomUUID().toString();
        copy.conflictOf = loser.id;
        copy.rev = 0;
        byte[] bytes = ink.read(loser.id);
        if (bytes != null) {
            ink.write(copy.id, bytes);
            String id = copy.id;
            mirrorOps.add(() -> mirrorInk(id, bytes));
        }
        dao.upsertBoard(copy);
        queueLocked(OutboxEntry.BOARD, copy.id, OutboxEntry.UPSERT);
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

    private List<Board> toBoards(List<BoardEntity> rows) {
        ArrayList<Board> result = new ArrayList<>(rows.size());
        for (BoardEntity row : rows) {
            result.add(load(row));
        }
        return result;
    }

    private Board load(BoardEntity row) {
        Board cached = cache.get(row.id);
        if (cached != null) {
            return cached;
        }
        Board board = new Board(row.id, row.createdAt);
        if (row.inkHash != null) {
            byte[] bytes = readInkQuietly(row.id);
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

    private byte[] readLogQuietly(String sheetId) {
        try {
            return ink.readLog(sheetId);
        } catch (IOException e) {
            Log.e(TAG, "ink log read failed " + sheetId, e);
            return null;
        }
    }

    private byte[] readInkQuietly(String boardId) {
        try {
            return ink.read(boardId);
        } catch (IOException e) {
            Log.e(TAG, "ink read failed " + boardId, e);
            LaunchLog.mark("ink read failed for page " + boardId + ": " + e);
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

    /** Drops one cached sheet so the next open replays its log. Link presses must not be that next open. */
    public void forgetSheetForTest(String notebookId) {
        synchronized (lock) {
            sheets.remove(NotebookPaper.sheetId(notebookId));
        }
    }

    @Override
    public void ensureSheet(String notebookId, int pageHeight, int legacyPageHeight, long shortPagesSince) {
        notebookReads.incrementAndGet();
        if (pageHeight <= 0) {
            return;
        }
        knownPageHeight = pageHeight;
        knownLegacyHeight = legacyPageHeight;
        knownShortPagesSince = shortPagesSince;
        String sheetId = NotebookPaper.sheetId(notebookId);
        synchronized (lock) {
            OpenSheet open = sheets.get(sheetId);
            if (open != null && open.ready) {
                return;
            }
            if (notebookId != null) {
                NotebookEntity notebook = dao.notebook(notebookId);
                if (notebook == null || notebook.deletedAt != null) {
                    return;
                }
            }
        }
        byte[] existing;
        try {
            existing = ink.readLog(sheetId);
        } catch (IOException e) {
            Log.e(TAG, "ink log unreadable " + sheetId, e);
            return;
        }
        if (existing != null) {
            try {
                logReplays.incrementAndGet();
                installSheet(notebookId, sheetId, NotebookPaper.replay(existing), existing.length, false);
            } catch (IOException e) {
                Log.e(TAG, "ink log will not replay " + sheetId, e);
            }
            return;
        }
        synchronized (lock) {
            if (dao.notebookLog(sheetId) != null) {
                // The log is the source of truth, and its file is missing. Do not rebuild
                // it from the pre-migration pages; a pull can put the log back.
                LaunchLog.mark("ink log missing for " + sheetId + "; page files left as they are");
                return;
            }
        }
        List<BoardEntity> rows;
        synchronized (lock) {
            rows = new ArrayList<>(rowsLocked(notebookId));
            if (!rows.isEmpty() && isBlankLocked(rows.get(rows.size() - 1))
                    && !trailingBlanks.containsKey(listKey(notebookId))) {
                rows.remove(rows.size() - 1);
            }
        }
        logReplays.incrementAndGet();
        ArrayList<NotebookPaper.SourcePage> sources = new ArrayList<>();
        for (BoardEntity row : rows) {
            if (row.conflictOf != null) {
                continue;
            }
            List<InkRenderer.InkStroke> strokes = Collections.emptyList();
            if (row.inkHash != null) {
                byte[] bytes = readInkQuietly(row.id);
                if (bytes != null) {
                    try {
                        strokes = InkCodec.decode(bytes);
                    } catch (IOException e) {
                        Log.e(TAG, "page ink skipped in migration " + row.id, e);
                        return;
                    }
                }
            }
            int height = NotebookPaper.measuredHeight(
                    row.createdAt, strokes, pageHeight, legacyPageHeight, shortPagesSince);
            sources.add(new NotebookPaper.SourcePage(row.id, height, strokes));
        }
        NotebookPaper built = NotebookPaper.migrate(sources, pageHeight);
        NotebookPaper readBack;
        try {
            readBack = NotebookPaper.replay(built.bytes());
        } catch (IOException e) {
            Log.e(TAG, "migration log will not replay " + sheetId, e);
            return;
        }
        if (!NotebookPaper.readsBack(sources, built) || !NotebookPaper.readsBack(sources, readBack)) {
            Log.e(TAG, "migration refused for " + sheetId + "; page files left untouched");
            return;
        }
        byte[] bytes = built.bytes();
        try {
            ink.writeLog(sheetId, bytes);
            byte[] onDisk = ink.readLog(sheetId);
            if (onDisk == null || !java.util.Arrays.equals(bytes, onDisk)) {
                Log.e(TAG, "migration read-back mismatch " + sheetId);
                return;
            }
            readBack = NotebookPaper.replay(onDisk);
        } catch (IOException e) {
            Log.e(TAG, "migration write failed " + sheetId, e);
            return;
        }
        if (!NotebookPaper.readsBack(sources, readBack)) {
            Log.e(TAG, "migration disk read-back refused " + sheetId);
            return;
        }
        installSheet(notebookId, sheetId, readBack, bytes.length, true);
    }

    private void installSheet(String notebookId, String sheetId, NotebookPaper paper, int flushed, boolean queue) {
        synchronized (lock) {
            OpenSheet open = new OpenSheet(sheetId, notebookId, paper, flushed);
            open.ready = true;
            sheets.put(sheetId, open);
            if (!queue) {
                return;
            }
            long now = clock.getAsLong();
            NotebookLogEntity row = dao.notebookLog(sheetId);
            if (row == null) {
                row = new NotebookLogEntity();
                row.id = sheetId;
                row.notebookId = notebookId;
                row.createdAt = now;
                row.rev = 0;
            }
            byte[] bytes;
            try {
                bytes = ink.readLog(sheetId);
            } catch (IOException e) {
                return;
            }
            row.inkHash = bytes == null ? null : InkFileStore.sha256(bytes);
            row.inkBytes = bytes == null ? 0 : bytes.length;
            row.sliceHeight = paper.sliceHeight;
            row.updatedAt = now;
            row.deletedAt = null;
            row.conflictOf = null;
            NotebookLogEntity stored = row;
            db.runInTransaction(() -> {
                dao.upsertNotebookLog(stored);
                queueLocked(OutboxEntry.LOG, stored.id, OutboxEntry.UPSERT);
            });
            if (bytes != null) {
                mirrorLog(sheetId, bytes);
            }
        }
    }

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
        boolean needBlank = pages.isEmpty() || !pages.get(pages.size() - 1).isBlank();
        String key = listKey(notebookId);
        if (needBlank) {
            Board blank = trailingBlanks.get(key);
            if (blank == null) {
                blank = Board.blank();
                trailingBlanks.put(key, blank);
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

    /** Writer thread. Appends the new tail of the log; it does not rewrite the prefix. */
    private void flushSheet(String sheetId) {
        if (sheetId == null) {
            return;
        }
        byte[] suffix;
        int flushed;
        synchronized (lock) {
            OpenSheet open = sheets.get(sheetId);
            if (open == null || !open.ready) {
                return;
            }
            byte[] all = open.paper.bytes();
            if (all.length <= open.flushed) {
                return;
            }
            suffix = java.util.Arrays.copyOfRange(all, open.flushed, all.length);
            flushed = all.length;
        }
        try {
            ink.appendLog(sheetId, suffix);
        } catch (IOException e) {
            Log.e(TAG, "ink log append failed " + sheetId, e);
            return;
        }
        byte[] all;
        try {
            all = ink.readLog(sheetId);
        } catch (IOException e) {
            Log.e(TAG, "ink log read failed " + sheetId, e);
            return;
        }
        if (all == null) {
            return;
        }
        String hash = InkFileStore.sha256(all);
        synchronized (lock) {
            OpenSheet open = sheets.get(sheetId);
            if (open == null) {
                return;
            }
            open.flushed = Math.max(open.flushed, flushed);
            NotebookLogEntity row = dao.notebookLog(sheetId);
            if (row == null || java.util.Objects.equals(row.inkHash, hash)) {
                if (row != null) {
                    return;
                }
                row = new NotebookLogEntity();
                row.id = sheetId;
                row.notebookId = open.notebookId;
                row.createdAt = clock.getAsLong();
            }
            row.inkHash = hash;
            row.inkBytes = all.length;
            row.sliceHeight = open.paper.sliceHeight;
            row.updatedAt = Math.max(row.updatedAt, clock.getAsLong());
            row.deletedAt = null;
            row.conflictOf = null;
            NotebookLogEntity stored = row;
            db.runInTransaction(() -> {
                dao.upsertNotebookLog(stored);
                queueLocked(OutboxEntry.LOG, stored.id, OutboxEntry.UPSERT);
            });
            mirrorLog(sheetId, all);
            localEdits.merge(sheetId, 1L, Long::sum);
        }
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

    private static final class OpenSheet {
        final String notebookId;
        NotebookPaper paper;
        int flushed;
        boolean ready;

        OpenSheet(String sheetId, String notebookId, NotebookPaper paper, int flushed) {
            this.notebookId = notebookId;
            this.paper = paper;
            this.flushed = flushed;
        }
    }

    private static final class PendingInk {
        final List<InkRenderer.InkStroke> strokes;
        final long editedAt;
        boolean failed;

        PendingInk(List<InkRenderer.InkStroke> strokes, long editedAt) {
            this.strokes = strokes;
            this.editedAt = editedAt;
        }
    }

    private static final class PullFailed extends RuntimeException {
        PullFailed(IOException cause) {
            super(cause);
        }
    }

    // endregion
}
