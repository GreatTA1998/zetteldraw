package com.zetteldraw.penpoc.data.db;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

import com.zetteldraw.penpoc.LaunchLog;

/**
 * Room version == shared {@code schema_version}. Bumping it means adding
 * {@code server/migrations/000N_*.sql} with the same change and a Room
 * {@code Migration} here; {@code SchemaContractTest} keeps the two in step.
 */
@Database(
        entities = {NotebookEntity.class, BoardEntity.class, NotebookLogEntity.class, PageLinkEntity.class,
                OutboxEntry.class, SyncState.class},
        version = ZettelDatabase.SCHEMA_VERSION,
        exportSchema = true)
public abstract class ZettelDatabase extends RoomDatabase {
    public static final int SCHEMA_VERSION = 4;

    /** Page links by the two page ids. No ink, and no foreign key: a slice id is not a board row. */
    public static final Migration MIGRATION_3_4 = new Migration(3, 4) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `page_links` ("
                    + "`id` TEXT NOT NULL, `source_id` TEXT NOT NULL, `target_id` TEXT NOT NULL, "
                    + "`created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, `rev` INTEGER NOT NULL, "
                    + "`deleted_at` INTEGER, PRIMARY KEY(`id`))");
        }
    };

    /** Nullable parent. Null is top-level. The column is metadata, not an ink merge. */
    public static final Migration MIGRATION_2_3 = new Migration(2, 3) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE notebooks ADD COLUMN parent_id TEXT");
        }
    };

    /**
     * Adds the notebook ink log beside the per-page rows. Does not touch
     * {@code boards} or their ink files, so a v19 install still has its pages.
     */
    public static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS `notebook_logs` ("
                    + "`id` TEXT NOT NULL, `notebook_id` TEXT, `ink_hash` TEXT, `ink_bytes` INTEGER NOT NULL, "
                    + "`slice_height` INTEGER NOT NULL, `conflict_of` TEXT, `created_at` INTEGER NOT NULL, "
                    + "`updated_at` INTEGER NOT NULL, `rev` INTEGER NOT NULL, `deleted_at` INTEGER, "
                    + "PRIMARY KEY(`id`))");
        }
    };
    private static final String NAME = "zetteldraw.db";

    public abstract ZettelDao dao();

    /**
     * Main-thread access is allowed on purpose: a pen-up write is one small
     * row plus one ink file and must finish before the next stroke.
     */
    public static ZettelDatabase open(Context context) {
        return Room.databaseBuilder(context.getApplicationContext(), ZettelDatabase.class, NAME)
                .allowMainThreadQueries()
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .addCallback(new Callback() {
                    @Override
                    public void onCreate(@NonNull SupportSQLiteDatabase db) {
                        LaunchLog.mark("database created new and empty at " + db.getPath());
                    }

                    @Override
                    public void onOpen(@NonNull SupportSQLiteDatabase db) {
                        LaunchLog.mark("database opened, version " + db.getVersion());
                    }

                    @Override
                    public void onDestructiveMigration(@NonNull SupportSQLiteDatabase db) {
                        LaunchLog.mark("database WIPED by a destructive migration");
                    }
                })
                .build();
    }
}
