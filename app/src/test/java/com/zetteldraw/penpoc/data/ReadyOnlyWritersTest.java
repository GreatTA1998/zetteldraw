package com.zetteldraw.penpoc.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;

import com.zetteldraw.penpoc.Board;
import com.zetteldraw.penpoc.data.db.BoardEntity;
import com.zetteldraw.penpoc.data.db.NotebookLogEntity;
import com.zetteldraw.penpoc.data.db.OutboxEntry;
import com.zetteldraw.penpoc.data.db.ZettelDatabase;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Production writers (UI + Move/tear) must hit {@code page.paper != null} →
 * {@code flushSheetOrFalse}. {@code saveInk} is Ready-only — paper-null is a
 * no-op (no BoardEntity {@code .zdi} write-behind).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class)
public class ReadyOnlyWritersTest {
    private static final int PAGE = 800;

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private ZettelDatabase db;
    private File inkDir;
    private InkFileStore ink;
    private ExecutorService writer;
    private RoomBoardRepository repo;
    private final AtomicLong clock = new AtomicLong(6_000_000L);

    @Before
    public void setUp() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        File root = tmp.newFolder("device");
        db = Room.databaseBuilder(context, ZettelDatabase.class, new File(root, "zetteldraw.db").getPath())
                .allowMainThreadQueries()
                .build();
        inkDir = new File(root, "ink");
        ink = new InkFileStore(inkDir);
        writer = Executors.newSingleThreadExecutor();
        repo = new RoomBoardRepository(db, ink, InkMirror.NONE, writer, Runnable::run, Runnable::run, clock::get);
    }

    @After
    public void tearDown() throws Exception {
        writer.shutdown();
        writer.awaitTermination(10, TimeUnit.SECONDS);
        db.close();
    }

    @Test
    public void readySaveFlushesLogAndNeverCreatesZdi() throws Exception {
        assertTrue(repo.ensureSheet(null, PAGE, PAGE, 0L));
        if (repo.scratchpadPages().isEmpty()) {
            repo.createPage(null);
        }
        Board page = repo.scratchpadPages().get(0);
        assertNotNull(page.paper);
        assertNotNull(page.sheetId);
        TestInk.draw(page, 12f);
        repo.saveInk(page);
        drain();

        assertEquals(0, repo.pendingWrites());
        assertFalse("Ready writer must not stage page .zdi", new File(inkDir, page.id + ".zdi").exists());
        String sheetId = NotebookPaper.sheetId(null);
        assertTrue(ink.hasLog(sheetId));
        NotebookLogEntity log = db.dao().notebookLog(sheetId);
        assertNotNull(log);
        assertTrue(log.inkBytes > 0);
        assertNotNull(db.dao().outbox(OutboxEntry.LOG, sheetId));
        BoardEntity boardRow = db.dao().board(page.id);
        assertTrue("no BoardEntity inkHash after Ready save",
                boardRow == null || boardRow.inkHash == null);
    }

    @Test
    public void logBackedPaperNullRefusesBoardEntityWriteBehind() throws Exception {
        assertTrue(repo.ensureSheet(null, PAGE, PAGE, 0L));
        if (repo.scratchpadPages().isEmpty()) {
            repo.createPage(null);
        }
        Board page = repo.scratchpadPages().get(0);
        String sheetId = page.sheetId;
        byte[] before = ink.readLog(sheetId);

        // Stale chrome: paper binding lost, strokes shoved onto legacy list.
        page.paper = null;
        TestInk.draw(page, 40f);
        repo.saveInk(page);
        drain();

        assertEquals(0, repo.pendingWrites());
        assertFalse(new File(inkDir, page.id + ".zdi").exists());
        BoardEntity row = db.dao().board(page.id);
        assertTrue(row == null || row.inkHash == null);
        assertNull(db.dao().outbox(OutboxEntry.BOARD, page.id));
        // Log untouched — refuse, do not invent a dual write.
        byte[] after = ink.readLog(sheetId);
        assertEquals(before == null ? 0 : before.length, after == null ? 0 : after.length);
    }

    @Test
    public void paperNullSaveInkIsNoOpEvenWhenUnmigrated() throws Exception {
        // No ensureSheet: saveInk must not invent BoardEntity .zdi write-behind.
        Board page = Board.blank();
        TestInk.draw(page, 7f);
        repo.saveInk(page);
        drain();

        assertEquals(0, repo.pendingWrites());
        assertFalse(new File(inkDir, page.id + ".zdi").exists());
        assertNull(db.dao().board(page.id));
        assertNull(db.dao().outbox(OutboxEntry.BOARD, page.id));
    }

    @Test
    public void importLegacyWritesReadyLogWithoutPageFilesOrBoardOutbox() throws Exception {
        Board page = Board.blank();
        TestInk.draw(page, 7f);
        LegacyBoardImporter.Legacy legacy = new LegacyBoardImporter.Legacy();
        legacy.scratchpad.add(page);
        assertEquals(1, repo.importLegacy(legacy, PAGE, PAGE, 0L));

        assertFalse("Ready-direct import must not write .zdi",
                new File(inkDir, page.id + ".zdi").exists());
        assertTrue(ink.hasLog(NotebookPaper.sheetId(null)));
        BoardEntity row = db.dao().board(page.id);
        assertNotNull(row);
        assertNull("identity row only — ink on .zdl", row.inkHash);
        assertNull(db.dao().outbox(OutboxEntry.BOARD, page.id));
        assertNotNull(db.dao().outbox(OutboxEntry.LOG, NotebookPaper.sheetId(null)));
        assertTrue(repo.isSheetReady(null));
    }

    @Test
    public void placePageBeforeRequiresPaperOnLogBackedSheets() throws Exception {
        assertTrue(repo.ensureSheet(null, PAGE, PAGE, 0L));
        if (repo.scratchpadPages().isEmpty()) {
            repo.createPage(null);
        }
        assertTrue(repo.ensureSheet(com.zetteldraw.penpoc.Notebook.COMEDY.uuid, PAGE, PAGE, 0L));
        Board source = repo.scratchpadPages().get(0);
        TestInk.draw(source, 10f);
        repo.saveInk(source);
        drain();
        Board destBlank = repo.pages(com.zetteldraw.penpoc.Notebook.COMEDY.uuid).get(0);
        assertNotNull(source.paper);
        assertNotNull(destBlank.paper);

        assertTrue(repo.placePageBefore(source.id, destBlank.id, com.zetteldraw.penpoc.Notebook.COMEDY.uuid));
        drain();
        assertFalse(new File(inkDir, source.id + ".zdi").exists());
        assertTrue(ink.hasLog(NotebookPaper.sheetId(com.zetteldraw.penpoc.Notebook.COMEDY.uuid)));
    }

    private void drain() throws Exception {
        for (int round = 0; round < 4; round++) {
            writer.submit(() -> { }).get(10, TimeUnit.SECONDS);
        }
        long deadline = System.currentTimeMillis() + 10_000;
        while (repo.pendingWrites() > 0 && System.currentTimeMillis() < deadline) {
            writer.submit(() -> { }).get(10, TimeUnit.SECONDS);
        }
        assertFalse(repo.pendingWrites() > 0);
    }
}
