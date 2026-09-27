package com.zetteldraw.penpoc.data.db;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/** Mirrors {@code notebooks} in server/migrations. */
@Entity(tableName = "notebooks")
public class NotebookEntity {
    @PrimaryKey
    @NonNull
    public String id = "";
    @NonNull
    public String title = "";
    @NonNull
    public String position = "";
    @ColumnInfo(name = "created_at")
    public long createdAt;
    @ColumnInfo(name = "updated_at")
    public long updatedAt;
    /** Server revision this row was last synced at; 0 = never pushed. */
    public long rev;
    @ColumnInfo(name = "deleted_at")
    public Long deletedAt;

    public NotebookEntity copy() {
        NotebookEntity c = new NotebookEntity();
        c.id = id;
        c.title = title;
        c.position = position;
        c.createdAt = createdAt;
        c.updatedAt = updatedAt;
        c.rev = rev;
        c.deletedAt = deletedAt;
        return c;
    }
}
