package com.zetteldraw.penpoc.data.db;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

/**
 * Room version == shared {@code schema_version}. Bumping it means adding
 * {@code server/migrations/000N_*.sql} with the same change and a Room
 * {@code Migration} here; {@code SchemaContractTest} keeps the two in step.
 */
@Database(
        entities = {NotebookEntity.class, BoardEntity.class, OutboxEntry.class, SyncState.class},
        version = ZettelDatabase.SCHEMA_VERSION,
        exportSchema = true)
public abstract class ZettelDatabase extends RoomDatabase {
    public static final int SCHEMA_VERSION = 1;
    private static final String NAME = "zetteldraw.db";

    public abstract ZettelDao dao();

    /**
     * Main-thread access is allowed on purpose: a pen-up write is one small
     * row plus one ink file and must finish before the next stroke.
     */
    public static ZettelDatabase open(Context context) {
        return Room.databaseBuilder(context.getApplicationContext(), ZettelDatabase.class, NAME)
                .allowMainThreadQueries()
                .build();
    }

    public static ZettelDatabase inMemory(Context context) {
        return Room.inMemoryDatabaseBuilder(context, ZettelDatabase.class)
                .allowMainThreadQueries()
                .build();
    }
}
