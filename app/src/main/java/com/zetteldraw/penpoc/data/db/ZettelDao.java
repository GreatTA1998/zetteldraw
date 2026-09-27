package com.zetteldraw.penpoc.data.db;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface ZettelDao {
    // Notebooks

    @Query("SELECT * FROM notebooks WHERE deleted_at IS NULL ORDER BY position, id")
    List<NotebookEntity> liveNotebooks();

    @Query("SELECT * FROM notebooks WHERE id = :id")
    NotebookEntity notebook(String id);

    @Query("SELECT MAX(position) FROM notebooks")
    String lastNotebookPosition();

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsertNotebook(NotebookEntity notebook);

    // Boards

    @Query("SELECT * FROM boards WHERE notebook_id IS NULL AND deleted_at IS NULL"
            + " AND conflict_of IS NULL ORDER BY position, id")
    List<BoardEntity> scratchpadBoards();

    @Query("SELECT * FROM boards WHERE notebook_id = :notebookId AND deleted_at IS NULL"
            + " AND conflict_of IS NULL ORDER BY position, id")
    List<BoardEntity> notebookBoards(String notebookId);

    @Query("SELECT MAX(position) FROM boards WHERE notebook_id IS NULL AND deleted_at IS NULL"
            + " AND conflict_of IS NULL")
    String lastScratchpadPosition();

    @Query("SELECT MAX(position) FROM boards WHERE notebook_id = :notebookId AND deleted_at IS NULL"
            + " AND conflict_of IS NULL")
    String lastNotebookBoardPosition(String notebookId);

    @Query("SELECT * FROM boards WHERE id = :id")
    BoardEntity board(String id);

    /** Everything that should appear in the Documents backup index, including conflict copies. */
    @Query("SELECT * FROM boards WHERE deleted_at IS NULL ORDER BY notebook_id, position, id")
    List<BoardEntity> liveBoardsForIndex();

    @Query("SELECT * FROM boards WHERE conflict_of IS NOT NULL AND deleted_at IS NULL")
    List<BoardEntity> conflictCopies();

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsertBoard(BoardEntity board);

    // Outbox

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsertOutbox(OutboxEntry entry);

    @Query("SELECT * FROM outbox WHERE entity = :entity AND id = :id")
    OutboxEntry outbox(String entity, String id);

    @Query("SELECT * FROM outbox ORDER BY CASE entity WHEN 'notebook' THEN 0 ELSE 1 END, queued_at LIMIT :limit")
    List<OutboxEntry> outboxBatch(int limit);

    @Query("SELECT COUNT(*) FROM outbox")
    int outboxCount();

    @Query("SELECT COALESCE(MAX(queued_at), 0) FROM outbox")
    long maxQueuedAt();

    @Query("DELETE FROM outbox WHERE entity = :entity AND id = :id AND queued_at = :queuedAt")
    int deleteOutboxIfUnchanged(String entity, String id, long queuedAt);

    @Query("DELETE FROM outbox WHERE entity = :entity AND id = :id")
    void deleteOutbox(String entity, String id);

    // Sync state

    @Query("SELECT * FROM sync_state WHERE id = 1")
    SyncState syncState();

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsertSyncState(SyncState state);
}
