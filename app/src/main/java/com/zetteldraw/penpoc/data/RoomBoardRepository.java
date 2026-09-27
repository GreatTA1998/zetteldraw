package com.zetteldraw.penpoc.data;

import android.util.Log;

import com.zetteldraw.penpoc.Board;
import com.zetteldraw.penpoc.InkRenderer;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * Room + ink files. One lock guards the database, the ink directory and the
 * shared {@link Board} cache; the UI thread and the sync worker both take it.
 */
public final class RoomBoardRepository implements BoardRepository, SyncStore {
    private static final String TAG = "zd-repo";

    private final ZettelDatabase db;
    private final ZettelDao dao;
    private final InkFileStore ink;
    private final InkMirror mirror;
    private final Executor background;
    private final Executor ui;
    private final LongSupplier clock;

    private final Object lock = new Object();
    private final HashMap<String, Board> cache = new HashMap<>();
    private final AtomicBoolean indexPending = new AtomicBoolean();
    /** Unsaved blank page shown at the end of the scratchpad. */
    private Board trailingBlank;
    private long lastQueuedAt;
    private volatile Runnable remoteListener;

    public RoomBoardRepository(ZettelDatabase db, InkFileStore ink, InkMirror mirror,
                               Executor background, Executor ui, LongSupplier clock) {
        this.db = db;
        this.dao = db.dao();
        this.ink = ink;
        this.mirror = mirror;
        this.background = background;
        this.ui = ui;
        this.clock = clock;
        synchronized (lock) {
            lastQueuedAt = dao.maxQueuedAt();
            db.runInTransaction(this::seedNotebooks);
            ensureTrailingBlankLocked();
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

    @Override
    public List<Board> scratchpadPages() {
        synchronized (lock) {
            List<Board> pages = toBoards(dao.scratchpadBoards());
            if (trailingBlank != null) {
                pages.add(trailingBlank);
            }
            return pages;
        }
    }

    @Override
    public List<Board> notebookPages(String notebookId) {
        synchronized (lock) {
            return toBoards(dao.notebookBoards(notebookId));
        }
    }

    @Override
    public Board createScratchpadPage() {
        synchronized (lock) {
            return ensureTrailingBlankLocked();
        }
    }

    @Override
    public void saveInk(Board page) {
        if (page == null) {
            return;
        }
        synchronized (lock) {
            byte[] bytes = page.isBlank() ? null : InkCodec.encode(page.strokes);
            String hash = bytes == null ? null : InkFileStore.sha256(bytes);
            BoardEntity row = dao.board(page.id);
            if (row == null) {
                if (bytes == null) {
                    return;
                }
                row = new BoardEntity();
                row.id = page.id;
                row.notebookId = null;
                row.position = Positions.after(dao.lastScratchpadPosition());
                row.createdAt = page.createdAt;
                cache.put(page.id, page);
                if (trailingBlank == page) {
                    trailingBlank = null;
                }
            } else if (Objects.equals(row.inkHash, hash)) {
                return;
            }
            try {
                writeInkLocked(page.id, bytes);
            } catch (IOException e) {
                Log.e(TAG, "ink write failed " + page.id, e);
                return;
            }
            row.inkHash = hash;
            row.inkBytes = bytes == null ? 0 : bytes.length;
            row.updatedAt = clock.getAsLong();
            BoardEntity toSave = row;
            db.runInTransaction(() -> {
                dao.upsertBoard(toSave);
                queueLocked(OutboxEntry.BOARD, toSave.id, OutboxEntry.UPSERT);
            });
            scheduleIndex();
        }
    }

    @Override
    public void movePageToNotebook(String boardId, String notebookId) {
        synchronized (lock) {
            NotebookEntity target = dao.notebook(notebookId);
            if (target == null || target.deletedAt != null) {
                return;
            }
            BoardEntity row = dao.board(boardId);
            if (row == null) {
                if (trailingBlank == null || !trailingBlank.id.equals(boardId)) {
                    return;
                }
                row = new BoardEntity();
                row.id = trailingBlank.id;
                row.createdAt = trailingBlank.createdAt;
                cache.put(row.id, trailingBlank);
                trailingBlank = null;
            }
            row.position = Positions.after(dao.lastNotebookBoardPosition(notebookId));
            row.notebookId = notebookId;
            row.updatedAt = clock.getAsLong();
            BoardEntity toSave = row;
            db.runInTransaction(() -> {
                dao.upsertBoard(toSave);
                queueLocked(OutboxEntry.BOARD, toSave.id, OutboxEntry.UPSERT);
            });
            ensureTrailingBlankLocked();
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
            if (board == null) {
                return;
            }
            board.strokes.clear();
            saveInk(board);
            trimTrailingBlanksLocked();
            ensureTrailingBlankLocked();
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
            ensureTrailingBlankLocked();
            scheduleIndex();
        }
    }

    @Override
    public void setRemoteChangeListener(Runnable listener) {
        remoteListener = listener;
    }

    // endregion

    // region SyncStore

    @Override
    public PushBatch pendingPush(int limit) {
        synchronized (lock) {
            PushBatch batch = new PushBatch();
            for (OutboxEntry entry : dao.outboxBatch(limit)) {
                if (OutboxEntry.NOTEBOOK.equals(entry.entity)) {
                    NotebookEntity row = dao.notebook(entry.id);
                    if (row == null) {
                        dao.deleteOutbox(entry.entity, entry.id);
                        continue;
                    }
                    batch.notebooks.add(row);
                } else {
                    BoardEntity row = dao.board(entry.id);
                    if (row == null) {
                        dao.deleteOutbox(entry.entity, entry.id);
                        continue;
                    }
                    if (row.inkHash != null && row.deletedAt == null) {
                        byte[] bytes = readInkQuietly(row.id);
                        if (bytes != null) {
                            String actual = InkFileStore.sha256(bytes);
                            if (!actual.equals(row.inkHash)) {
                                // A crash between the file write and the row update; the file is newer.
                                row.inkHash = actual;
                                row.inkBytes = bytes.length;
                                dao.upsertBoard(row);
                            }
                            batch.blobs.put(actual, bytes);
                        }
                    }
                    batch.boards.add(row);
                }
                batch.queuedAt.put(entry.entity + ":" + entry.id, entry.queuedAt);
            }
            return batch;
        }
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

    @Override
    public void applyPull(PullPage page) throws IOException {
        HashMap<String, List<InkRenderer.InkStroke>> refreshed = new HashMap<>();
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
                            applyRemoteBoardLocked(remote, page.blobs, refreshed, mirrorOps);
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
                background.execute(op);
            }
            if (!page.boards.isEmpty() || !page.notebooks.isEmpty()) {
                scheduleIndex();
            }
        }
        if (!page.boards.isEmpty() || !page.notebooks.isEmpty()) {
            ui.execute(() -> {
                synchronized (lock) {
                    for (Map.Entry<String, List<InkRenderer.InkStroke>> e : refreshed.entrySet()) {
                        Board board = cache.get(e.getKey());
                        if (board != null) {
                            board.strokes.clear();
                            board.strokes.addAll(e.getValue());
                        }
                    }
                }
                Runnable listener = remoteListener;
                if (listener != null) {
                    listener.run();
                }
            });
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
            if (trailingBlank != null) {
                cache.remove(trailingBlank.id);
                trailingBlank = null;
            }
            ensureTrailingBlankLocked();
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

    private Board ensureTrailingBlankLocked() {
        if (trailingBlank != null) {
            return trailingBlank;
        }
        List<BoardEntity> rows = dao.scratchpadBoards();
        if (!rows.isEmpty() && rows.get(rows.size() - 1).inkHash == null) {
            return load(rows.get(rows.size() - 1));
        }
        trailingBlank = Board.blank();
        cache.put(trailingBlank.id, trailingBlank);
        return trailingBlank;
    }

    /** Keep at most one blank page at the end of the scratchpad. */
    private void trimTrailingBlanksLocked() {
        while (true) {
            List<BoardEntity> rows = dao.scratchpadBoards();
            int n = rows.size() + (trailingBlank != null ? 1 : 0);
            if (n < 2) {
                return;
            }
            boolean lastBlank = trailingBlank != null || rows.get(rows.size() - 1).inkHash == null;
            BoardEntity prev = trailingBlank != null ? rows.get(rows.size() - 1) : rows.get(rows.size() - 2);
            if (!lastBlank || prev.inkHash != null) {
                return;
            }
            if (trailingBlank != null) {
                cache.remove(trailingBlank.id);
                trailingBlank = null;
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
        String id = row.id;
        background.execute(() -> mirror.deleteInk(id));
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

    private void applyRemoteBoardLocked(BoardEntity remote, Map<String, byte[]> blobs,
                                        Map<String, List<InkRenderer.InkStroke>> refreshed,
                                        List<Runnable> mirrorOps) throws IOException {
        BoardEntity local = dao.board(remote.id);
        if (local != null) {
            if (remote.rev <= local.rev) {
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
            mirrorOps.add(() -> mirror.deleteInk(id));
            refreshed.put(remote.id, new ArrayList<>());
        } else if (local == null || !remote.inkHash.equals(local.inkHash)) {
            byte[] bytes = blobs.get(remote.inkHash);
            if (bytes == null || !InkFileStore.sha256(bytes).equals(remote.inkHash)) {
                throw new IOException("bad ink blob for " + remote.id);
            }
            writeInkLocked(remote.id, bytes);
            try {
                refreshed.put(remote.id, InkCodec.decode(bytes));
            } catch (IOException e) {
                // Kept byte-for-byte (e.g. a newer ink format); it must not block the rest of the pull.
                Log.w(TAG, "undecodable ink for " + remote.id, e);
                refreshed.put(remote.id, new ArrayList<>());
            }
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
            mirrorOps.add(() -> mirror.writeInk(id, bytes));
        }
        dao.upsertBoard(copy);
        queueLocked(OutboxEntry.BOARD, copy.id, OutboxEntry.UPSERT);
    }

    private void writeInkLocked(String boardId, byte[] bytes) throws IOException {
        if (bytes == null) {
            ink.delete(boardId);
            background.execute(() -> mirror.deleteInk(boardId));
        } else {
            ink.write(boardId, bytes);
            background.execute(() -> mirror.writeInk(boardId, bytes));
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
            if (bytes != null) {
                try {
                    board.strokes.addAll(InkCodec.decode(bytes));
                } catch (IOException e) {
                    Log.e(TAG, "unreadable ink " + row.id, e);
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
            return null;
        }
    }

    private void scheduleIndex() {
        if (!indexPending.compareAndSet(false, true)) {
            return;
        }
        background.execute(() -> {
            indexPending.set(false);
            String json;
            synchronized (lock) {
                json = buildIndexLocked();
            }
            if (json != null) {
                mirror.writeIndex(json);
            }
        });
    }

    private String buildIndexLocked() {
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

    private static final class PullFailed extends RuntimeException {
        PullFailed(IOException cause) {
            super(cause);
        }
    }

    // endregion
}
