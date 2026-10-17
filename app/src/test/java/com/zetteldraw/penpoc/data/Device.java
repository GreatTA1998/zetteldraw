package com.zetteldraw.penpoc.data;

import android.content.Context;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;

import com.zetteldraw.penpoc.Board;
import com.zetteldraw.penpoc.data.db.BoardEntity;
import com.zetteldraw.penpoc.data.db.OutboxEntry;
import com.zetteldraw.penpoc.data.db.ZettelDatabase;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;

/** One simulated install: its own database file, ink dir, mirror dir and clock. */
final class Device {
    /** Default slice height for {@link #ensureReady()} — matches most Ready-path tests. */
    static final int PAGE = 800;

    final String name;
    final File root;
    final AtomicLong clock;
    ZettelDatabase db;
    RoomBoardRepository repo;

    Device(String name, File root, long startMillis) {
        this.name = name;
        this.root = root;
        this.clock = new AtomicLong(startMillis);
        open();
    }

    void open() {
        Context context = ApplicationProvider.getApplicationContext();
        db = Room.databaseBuilder(context, ZettelDatabase.class, new File(root, "zetteldraw.db").getPath())
                .allowMainThreadQueries()
                .build();
        repo = new RoomBoardRepository(db, new InkFileStore(inkDir()), new DirectoryMirror(mirrorDir()),
                Runnable::run, Runnable::run, clock::get);
    }

    void reopen() {
        db.close();
        open();
    }

    void close() {
        db.close();
    }

    /** Installs a Ready sheet for Scratchpad (or {@code notebookId}). */
    boolean ensureReady() {
        return ensureReady(null);
    }

    boolean ensureReady(String notebookId) {
        return repo.ensureSheet(notebookId, PAGE, PAGE, 0L);
    }

    /**
     * Historical BOARD residue ({@code .zdi} + BOARD outbox) for migration /
     * pull / retire tests. Production import is Ready-direct.
     */
    void seedUnmigrated(Board page) throws IOException {
        if (page == null || page.isBlank() || db.dao().board(page.id) != null) {
            return;
        }
        String pos = Positions.after(db.dao().lastScratchpadPosition());
        BoardEntity row = new BoardEntity();
        row.id = page.id;
        row.notebookId = null;
        row.position = pos;
        row.createdAt = page.createdAt;
        row.updatedAt = clock.get();
        byte[] bytes = InkCodec.encode(page.strokes);
        row.inkHash = InkFileStore.sha256(bytes);
        row.inkBytes = bytes.length;
        new InkFileStore(inkDir()).write(page.id, bytes);
        db.dao().upsertBoard(row);
        OutboxEntry out = new OutboxEntry();
        out.entity = OutboxEntry.BOARD;
        out.id = page.id;
        out.op = OutboxEntry.UPSERT;
        out.queuedAt = clock.get();
        db.dao().upsertOutbox(out);
    }

    File inkDir() {
        return new File(root, "ink");
    }

    File mirrorDir() {
        return new File(root, "Documents/zetteldraw");
    }

    long tick() {
        return clock.addAndGet(1000);
    }
}
