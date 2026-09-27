package com.zetteldraw.penpoc.data;

import android.content.Context;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;

import com.zetteldraw.penpoc.data.db.ZettelDatabase;

import java.io.File;
import java.util.concurrent.atomic.AtomicLong;

/** One simulated install: its own database file, ink dir, mirror dir and clock. */
final class Device {
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
