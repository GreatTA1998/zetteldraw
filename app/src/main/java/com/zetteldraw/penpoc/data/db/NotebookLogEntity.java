package com.zetteldraw.penpoc.data.db;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * One append-only ink log for a notebook. Sits beside {@link BoardEntity}:
 * the old per-page blobs stay. {@code conflictOf != null} is a hidden copy
 * of a whole log, never a torn stroke.
 */
@Entity(tableName = "notebook_logs")
public class NotebookLogEntity {
    @PrimaryKey
    @NonNull
    public String id = "";
    @ColumnInfo(name = "notebook_id")
    public String notebookId;
    @ColumnInfo(name = "ink_hash")
    public String inkHash;
    @ColumnInfo(name = "ink_bytes")
    public long inkBytes;
    @ColumnInfo(name = "slice_height")
    public int sliceHeight;
    @ColumnInfo(name = "conflict_of")
    public String conflictOf;
    @ColumnInfo(name = "created_at")
    public long createdAt;
    @ColumnInfo(name = "updated_at")
    public long updatedAt;
    public long rev;
    @ColumnInfo(name = "deleted_at")
    public Long deletedAt;

    public NotebookLogEntity copy() {
        NotebookLogEntity c = new NotebookLogEntity();
        c.id = id;
        c.notebookId = notebookId;
        c.inkHash = inkHash;
        c.inkBytes = inkBytes;
        c.sliceHeight = sliceHeight;
        c.conflictOf = conflictOf;
        c.createdAt = createdAt;
        c.updatedAt = updatedAt;
        c.rev = rev;
        c.deletedAt = deletedAt;
        return c;
    }
}
