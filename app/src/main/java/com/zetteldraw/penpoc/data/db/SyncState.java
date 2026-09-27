package com.zetteldraw.penpoc.data.db;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/** Device-only single row: the server pull cursor. */
@Entity(tableName = "sync_state")
public class SyncState {
    public static final int SINGLETON = 1;

    @PrimaryKey
    public int id = SINGLETON;
    public long cursor;
    @ColumnInfo(name = "last_ok_at")
    public Long lastOkAt;
}
