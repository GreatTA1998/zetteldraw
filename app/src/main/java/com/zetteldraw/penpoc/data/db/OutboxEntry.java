package com.zetteldraw.penpoc.data.db;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;

/**
 * Device-only: one pending push per row. Re-queuing the same row bumps
 * {@code queuedAt}, which is how a push knows the row changed mid-flight.
 */
@Entity(tableName = "outbox", primaryKeys = {"entity", "id"})
public class OutboxEntry {
    public static final String NOTEBOOK = "notebook";
    public static final String BOARD = "board";
    /** The notebook's ink log. One row per notebook, not per page. */
    public static final String LOG = "log";
    public static final String UPSERT = "upsert";
    public static final String DELETE = "delete";

    @NonNull
    public String entity = "";
    @NonNull
    public String id = "";
    @NonNull
    public String op = UPSERT;
    /** Strictly increasing per device, not wall-clock. */
    @ColumnInfo(name = "queued_at")
    public long queuedAt;
}
