package com.zetteldraw.penpoc.data.db;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * Mirrors {@code boards} in server/migrations. {@code notebookId == null}
 * means scratchpad. {@code conflictOf != null} marks a hidden conflict copy.
 */
@Entity(tableName = "boards", indices = {@Index({"notebook_id", "position"})})
public class BoardEntity {
    @PrimaryKey
    @NonNull
    public String id = "";
    @ColumnInfo(name = "notebook_id")
    public String notebookId;
    @NonNull
    public String position = "";
    @ColumnInfo(name = "ink_hash")
    public String inkHash;
    @ColumnInfo(name = "ink_bytes")
    public long inkBytes;
    @ColumnInfo(name = "thumb_hash")
    public String thumbHash;
    @ColumnInfo(name = "conflict_of")
    public String conflictOf;
    @ColumnInfo(name = "created_at")
    public long createdAt;
    @ColumnInfo(name = "updated_at")
    public long updatedAt;
    /** Server revision this row was last synced at; 0 = never pushed. */
    public long rev;
    @ColumnInfo(name = "deleted_at")
    public Long deletedAt;

    public BoardEntity copy() {
        BoardEntity c = new BoardEntity();
        c.id = id;
        c.notebookId = notebookId;
        c.position = position;
        c.inkHash = inkHash;
        c.inkBytes = inkBytes;
        c.thumbHash = thumbHash;
        c.conflictOf = conflictOf;
        c.createdAt = createdAt;
        c.updatedAt = updatedAt;
        c.rev = rev;
        c.deletedAt = deletedAt;
        return c;
    }
}
