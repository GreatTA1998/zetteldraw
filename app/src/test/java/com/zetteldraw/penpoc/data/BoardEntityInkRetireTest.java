package com.zetteldraw.penpoc.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import com.zetteldraw.penpoc.Board;
import com.zetteldraw.penpoc.data.db.BoardEntity;
import com.zetteldraw.penpoc.data.db.NotebookLogEntity;
import com.zetteldraw.penpoc.data.db.OutboxEntry;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.util.List;

/**
 * After Ready {@code .zdl} install, BoardEntity {@code .zdi} must not remain a
 * second ink SoT. Page-file migrate-on-open is gone (fleet Ready-covered).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class)
public class BoardEntityInkRetireTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Device device;
    private RoomBoardRepository repo;
    private File inkDir;

    @Before
    public void setUp() throws Exception {
        File root = tmp.newFolder("retire");
        inkDir = new File(root, "ink");
        device = new Device("retire", root, 30_000_000L);
        repo = device.repo;
    }

    @After
    public void tearDown() {
        device.close();
    }

    @Test
    public void importLegacyInstallsReadyAndRetiresPageFiles() throws Exception {
        Board page = Board.blank();
        TestInk.draw(page, 15f);
        LegacyBoardImporter.Legacy legacy = new LegacyBoardImporter.Legacy();
        legacy.scratchpad.add(page);

        assertEquals(1, repo.importLegacy(legacy, Device.PAGE, Device.PAGE, 0L));

        assertFalse("Ready must retire .zdi after install",
                new File(inkDir, page.id + ".zdi").exists());
        BoardEntity row = device.db.dao().board(page.id);
        assertNotNull(row);
        assertNull("inkHash cleared — residue only", row.inkHash);
        assertNull(device.db.dao().outbox(OutboxEntry.BOARD, page.id));
        assertTrue(new InkFileStore(inkDir).hasLog(NotebookPaper.sheetId(null)));
        List<Board> pages = repo.scratchpadPages();
        assertEquals(1, pages.size());
        assertEquals(page.id, pages.get(0).id);
        assertFalse(pages.get(0).isBlank());
    }

    @Test
    public void ensureRefusesUnmigratedBoardEntityInk() throws Exception {
        Board page = Board.blank();
        TestInk.draw(page, 11f);
        device.seedUnmigrated(page);
        assertFalse("fleet endgame: no blank-wipe migrate",
                device.ensureReady());
        assertTrue(new File(inkDir, page.id + ".zdi").exists());
        assertNotNull(device.db.dao().board(page.id).inkHash);
        BoardRepository.NotebookInfo dest = repo.createNotebook("move-refuse");
        assertFalse("BoardEntity Move dual deleted — Ready first",
                repo.movePageToNotebook(page.id, dest.id()));
        assertEquals(null, device.db.dao().board(page.id).notebookId);
    }

    @Test
    public void deletePageRefusesUnmigratedPaperNull() throws Exception {
        Board page = Board.blank();
        TestInk.draw(page, 12f);
        device.seedUnmigrated(page);
        BoardEntity before = device.db.dao().board(page.id);
        assertNotNull(before);
        assertNull(before.deletedAt);
        assertNotNull(before.inkHash);
        repo.deletePage(page.id);
        BoardEntity after = device.db.dao().board(page.id);
        assertNotNull(after);
        assertNull("BoardEntity wipe/delete dual deleted — no tombstone", after.deletedAt);
        assertEquals(before.inkHash, after.inkHash);
        OutboxEntry out = device.db.dao().outbox(OutboxEntry.BOARD, page.id);
        assertNotNull(out);
        assertEquals(OutboxEntry.UPSERT, out.op);
        assertTrue(new File(inkDir, page.id + ".zdi").exists());
    }

    @Test
    public void logBackedNotebookIgnoresRemoteBoardInk() throws Exception {
        assertTrue(device.ensureReadyWithPage(null));
        Board ready = repo.scratchpadPages().get(0);
        TestInk.draw(ready, 20f);
        repo.saveInk(ready);
        for (int i = 0; i < 40 && repo.pendingWrites() > 0; i++) {
            Thread.sleep(20);
        }

        String sheetId = NotebookPaper.sheetId(null);
        NotebookLogEntity log = device.db.dao().notebookLog(sheetId);
        assertNotNull(log);
        byte[] logBefore = new InkFileStore(inkDir).readLog(sheetId);

        SyncStore.PullPage pull = new SyncStore.PullPage();
        pull.cursor = Math.max(1, log.rev) + 50;
        repo.applyPull(pull);

        assertFalse("log-backed must not recreate page .zdi",
                new File(inkDir, ready.id + ".zdi").exists());
        BoardEntity after = device.db.dao().board(ready.id);
        if (after != null) {
            assertNull(after.inkHash);
        }
        byte[] logAfter = new InkFileStore(inkDir).readLog(sheetId);
        assertTrue(java.util.Arrays.equals(logBefore, logAfter));
        assertTrue(device.db.dao().conflictCopies().isEmpty());
    }
}
