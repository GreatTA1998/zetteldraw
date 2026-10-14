package com.zetteldraw.penpoc.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import com.zetteldraw.penpoc.Board;
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

/**
 * Pull must not replace a Ready longer Scratchpad with a shorter remote log.
 * That was the v79 ~49→~23 hole when {@code notebook_logs} was missing after
 * {@code installSheet(..., queue=false)}.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class)
public class RemoteLogTruncateTest {
    private static final int PAGE = 400;

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Device device;
    private RoomBoardRepository repo;

    @Before
    public void setUp() throws Exception {
        device = new Device("truncate", tmp.newFolder("truncate"), 4_000_000L);
        repo = device.repo;
    }

    @After
    public void tearDown() {
        device.close();
    }

    @Test
    public void installSheetRegistersNotebookLogEvenForDiskReplay() throws Exception {
        assertTrue(repo.ensureSheet(null, PAGE, PAGE, 0L));
        Board blank = repo.scratchpadPages().get(0);
        for (int i = 0; i < 8; i++) {
            blank.paper.appendStroke(TestInk.stroke(10f, i * PAGE + 20f, 8));
        }
        repo.saveInk(blank);
        for (int i = 0; i < 20 && repo.pendingWrites() > 0; i++) {
            Thread.sleep(20);
        }
        String sheetId = NotebookPaper.sheetId(null);
        assertNotNull(device.db.dao().notebookLog(sheetId));

        // Cold OpenSheet: disk replay path (historically queue=false, no meta).
        repo.invalidateOpenSheet(null);
        device.db.dao().deleteNotebookLog(sheetId);
        device.db.dao().deleteOutbox(OutboxEntry.LOG, sheetId);
        assertTrue(repo.ensureSheet(null, PAGE, PAGE, 0L));
        assertNotNull("disk install must create notebook_logs so pull LWW runs",
                device.db.dao().notebookLog(sheetId));
        assertTrue(repo.scratchpadPages().size() > 5);
    }

    @Test
    public void shorterRemoteWithMissingNotebookLogCannotCollapseReadySheet() throws Exception {
        int fullCount = seedDenseScratchpad(40);
        String sheetId = NotebookPaper.sheetId(null);
        assertTrue(repo.isSheetReady(null));

        // Recreate the v79 hole: Ready sheet in memory, no notebook_logs row.
        device.db.dao().deleteNotebookLog(sheetId);
        device.db.dao().deleteOutbox(OutboxEntry.LOG, sheetId);
        assertTrue(repo.isSheetReady(null));
        assertEquals(fullCount, repo.scratchpadPages().size());

        byte[] shortBytes = shortLogBytes(22);
        NotebookLogEntity remote = new NotebookLogEntity();
        remote.id = sheetId;
        remote.notebookId = null;
        remote.inkHash = InkFileStore.sha256(shortBytes);
        remote.inkBytes = shortBytes.length;
        remote.sliceHeight = PAGE;
        remote.createdAt = 1L;
        remote.updatedAt = device.clock.get() + 60_000;
        remote.rev = 99;
        SyncStore.PullPage pull = new SyncStore.PullPage();
        pull.logs.add(remote);
        pull.blobs.put(remote.inkHash, shortBytes);
        pull.cursor = remote.rev;
        repo.applyPull(pull);

        assertEquals("Ready sheet must not collapse to shorter remote",
                fullCount, repo.scratchpadPages().size());
        assertNotNull("meta healed from Ready paper", device.db.dao().notebookLog(sheetId));
        assertFalse("shorter remote shelved as conflict",
                device.db.dao().conflictLogCopies().isEmpty());
    }

    @Test
    public void shorterRemoteWithMetaRowIsAlsoRefused() throws Exception {
        int fullCount = seedDenseScratchpad(36);
        String sheetId = NotebookPaper.sheetId(null);
        NotebookLogEntity local = device.db.dao().notebookLog(sheetId);
        assertNotNull(local);
        device.db.dao().deleteOutbox(OutboxEntry.LOG, sheetId);

        byte[] shortBytes = shortLogBytes(20);
        NotebookLogEntity remote = local.copy();
        remote.inkHash = InkFileStore.sha256(shortBytes);
        remote.inkBytes = shortBytes.length;
        remote.rev = local.rev + 5;
        remote.updatedAt = local.updatedAt + 120_000;
        SyncStore.PullPage pull = new SyncStore.PullPage();
        pull.logs.add(remote);
        pull.blobs.put(remote.inkHash, shortBytes);
        pull.cursor = remote.rev;
        repo.applyPull(pull);

        assertEquals(fullCount, repo.scratchpadPages().size());
        assertFalse(device.db.dao().conflictLogCopies().isEmpty());
    }

    private int seedDenseScratchpad(int inkedSlices) throws Exception {
        assertTrue(repo.ensureSheet(null, PAGE, PAGE, 0L));
        Board blank = repo.scratchpadPages().get(0);
        for (int i = 0; i < inkedSlices; i++) {
            blank.paper.appendStroke(TestInk.stroke(10f, i * PAGE + 20f, 8));
        }
        repo.saveInk(blank);
        for (int i = 0; i < 20 && repo.pendingWrites() > 0; i++) {
            Thread.sleep(20);
        }
        int fullCount = repo.scratchpadPages().size();
        assertTrue("expected dense Scratchpad, was " + fullCount, fullCount > inkedSlices / 2);
        return fullCount;
    }

    private static byte[] shortLogBytes(int slices) throws Exception {
        NotebookPaper shortPaper = NotebookPaper.empty(PAGE);
        for (int i = 0; i < slices; i++) {
            shortPaper.appendStroke(TestInk.stroke(10f, i * PAGE + 20f, 8));
        }
        return shortPaper.bytes();
    }
}
