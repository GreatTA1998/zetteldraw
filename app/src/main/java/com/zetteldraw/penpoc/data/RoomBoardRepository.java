package com.zetteldraw.penpoc.data;

import android.os.SystemClock;
import android.util.Log;

import com.zetteldraw.penpoc.Board;
import com.zetteldraw.penpoc.InkRenderer;
import com.zetteldraw.penpoc.LaunchLog;
import com.zetteldraw.penpoc.Notebook;
import com.zetteldraw.penpoc.data.db.BoardEntity;
import com.zetteldraw.penpoc.data.db.NotebookEntity;
import com.zetteldraw.penpoc.data.db.OutboxEntry;
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
                result.add(new NotebookInfo(row.id, row.title));
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
            BoardEntity row = dao.board(boardId);
            if (row == null || row.deletedAt != null || row.conflictOf != null || isTrailingBlankLocked(row)) {
                return;
            }
            tombstoneLocked(row);
            trimTrailingBlanksLocked(row.notebookId);
            ensureTrailingBlankLocked(row.notebookId);
        }
    }

    @Override
    public NotebookInfo createNotebook(String title) {
        String clean = cleanTitle(title);
        if (clean == null) {
            return null;
        }
        synchronized (lock) {
            long now = clock.getAsLong();
            NotebookEntity row = new NotebookEntity();
            row.id = UUID.randomUUID().toString();
            row.title = clean;
            row.position = Positions.after(dao.lastNotebookPosition());
            row.createdAt = now;
            row.updatedAt = now;
            db.runInTransaction(() -> {
                dao.upsertNotebook(row);
                queueLocked(OutboxEntry.NOTEBOOK, row.id, OutboxEntry.UPSERT);
            });
            scheduleIndex();
            return new NotebookInfo(row.id, row.title);
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
            db.runInTransaction(() -> {
                dao.upsertNotebook(row);
                queueLocked(OutboxEntry.NOTEBOOK, row.id, OutboxEntry.DELETE);
                returnPagesToScratchpadLocked(notebookId);
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

    @Override
    public void setRemoteChangeListener(Runnable listener) {
        remoteListeners.clear();
        if (listener != null) {
            remoteListeners.add(listener);
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
        HashMap<String, Long> boardQueuedAt = new HashMap<>();
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
                } else {
                    BoardEntity row = dao.board(entry.id);
                    if (row == null) {
                        dao.deleteOutbox(entry.entity, entry.id);
                        continue;
                    }
                    boards.add(row);
                    boardQueuedAt.put(row.id, entry.queuedAt);
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
        synchronized (lock) {
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
        try {
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
            applyPullLocked(page, staged, refreshed, editsSeen, boardsSeen);
        } finally {
            for (Staged s : staged.values()) {
                ink.discard(s.file);
            }
        }
        if (!page.boards.isEmpty() || !page.notebooks.isEmpty()) {
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

    private void applyPullLocked(PullPage page, Map<String, Staged> staged,
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
                        for (NotebookEntity remote : page.notebooks) {
                            NotebookEntity now = dao.notebook(remote.id);
                            if (now != null && now.deletedAt != null) {
                                // Pages still filed here (e.g. moved in on this device after the
                                // other device deleted it) go back to the scratchpad.
                                returnPagesToScratchpadLocked(remote.id);
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
            if (!page.boards.isEmpty() || !page.notebooks.isEmpty()) {
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
                cache.remove(blank.id);
                trailingBlanks.remove(key);
            } else {
                tombstoneLocked(rows.get(rows.size() - 1));
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
                        .put("position", row.position));
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
            return root.toString(2);
        } catch (JSONException e) {
            Log.e(TAG, "index build failed", e);
            return null;
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
