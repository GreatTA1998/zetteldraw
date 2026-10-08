package com.zetteldraw.penpoc.data.db;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * A link between two pages, stored by page id. It copies no ink. Mirrors
 * {@code page_links} in server/migrations.
 */
@Entity(tableName = "page_links")
public class PageLinkEntity {
    @PrimaryKey
    @NonNull
    public String id = "";
    @ColumnInfo(name = "source_id")
    @NonNull
    public String sourceId = "";
    @ColumnInfo(name = "target_id")
    @NonNull
    public String targetId = "";
    @ColumnInfo(name = "created_at")
    public long createdAt;
    @ColumnInfo(name = "updated_at")
    public long updatedAt;
    /** Server revision this row was last synced at; 0 = never pushed. */
    public long rev;
    @ColumnInfo(name = "deleted_at")
    public Long deletedAt;

    public PageLinkEntity copy() {
        PageLinkEntity c = new PageLinkEntity();
        c.id = id;
        c.sourceId = sourceId;
        c.targetId = targetId;
        c.createdAt = createdAt;
        c.updatedAt = updatedAt;
        c.rev = rev;
        c.deletedAt = deletedAt;
        return c;
    }
}
