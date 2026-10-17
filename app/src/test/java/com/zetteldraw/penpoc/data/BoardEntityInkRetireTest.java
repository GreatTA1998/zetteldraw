package com.zetteldraw.penpoc.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import com.zetteldraw.penpoc.Board;
import com.zetteldraw.penpoc.InkRenderer;
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
import java.util.Collections;
import java.util.List;

/**
 * After Ready {@code .zdl} install, BoardEntity {@code .zdi} must not remain a
 * second ink SoT. MetaPullApply board apply is a no-op when log-backed.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class)
public class BoardEntityInkRetireTest {
    private static final int PAGE = 800;

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
    public void ensureMigratesThenRetiresPageFiles() throws Exception {
        Board page = Board.blank();
        TestInk.draw(page, 15f);
        repo.persistBoardEntityInk(page);
        assertTrue(new File(inkDir, page.id + ".zdi").exists());
        assertNotNull(device.db.dao().board(page.id).inkHash);
        assertNotNull(device.db.dao().outbox(OutboxEntry.BOARD, page.id));

        assertTrue(device.ensureReady());

        assertFalse("Ready must retire .zdi after migrate",
                new File(inkDir, page.id + ".zdi").exists());
        BoardEntity row = device.db.dao().board(page.id);
        assertNotNull(row);
        assertNull("inkHash cleared — migration residue only", row.inkHash);
        assertNull(device.db.dao().outbox(OutboxEntry.BOARD, page.id));
        assertTrue(new InkFileStore(inkDir).hasLog(NotebookPaper.sheetId(null)));
        List<Board> pages = repo.scratchpadPages();
        assertTrue(pages.size() >= 2);
        assertEquals(page.id, pages.get(0).id);
        assertFalse(pages.get(0).isBlank());
    }

    @Test
    public void logBackedNotebookIgnoresRemoteBoardInk() throws Exception {
        assertTrue(device.ensureReady());
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

        BoardEntity remote = new BoardEntity();
        remote.id = ready.id;
        remote.notebookId = null;
        remote.position = "a0";
        remote.createdAt = ready.createdAt;
        remote.updatedAt = log.updatedAt + 10_000;
        remote.rev = Math.max(1, log.rev) + 50;
        byte[] remoteBytes = InkCodec.encode(
                Collections.singletonList(TestInk.stroke(900f, 10f, 12)));
        remote.inkHash = InkFileStore.sha256(remoteBytes);
        remote.inkBytes = remoteBytes.length;

        SyncStore.PullPage pull = new SyncStore.PullPage();
        pull.boards.add(remote);
        pull.blobs.put(remote.inkHash, remoteBytes);
        pull.cursor = remote.rev;
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

    @Test
    public void unmigratedBoardConflictShelfStillWorks() throws Exception {
        Board page = Board.blank();
        TestInk.draw(page, 10f);
        repo.persistBoardEntityInk(page);
        BoardEntity local = device.db.dao().board(page.id);
        List<InkRenderer.InkStroke> remoteInk =
                Collections.singletonList(TestInk.stroke(500f, 600f, 9));
        byte[] bytes = InkCodec.encode(remoteInk);
        BoardEntity remote = local.copy();
        remote.inkHash = InkFileStore.sha256(bytes);
        remote.inkBytes = bytes.length;
        remote.updatedAt = local.updatedAt + 5_000;
        remote.rev = 10;
        SyncStore.PullPage pull = new SyncStore.PullPage();
        pull.boards.add(remote);
        pull.blobs.put(remote.inkHash, bytes);
        pull.cursor = 10;
        repo.applyPull(pull);

        assertEquals(10, device.db.dao().board(page.id).rev);
        assertEquals(1, device.db.dao().conflictCopies().size());
        assertTrue(new File(inkDir, page.id + ".zdi").exists());
    }
}
