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

    @Test
    public void diskReplayDoesNotQueueTruncatedLogForPush() throws Exception {
        assertTrue(repo.ensureSheet(null, PAGE, PAGE, 0L));
        Board blank = repo.scratchpadPages().get(0);
        for (int i = 0; i < 6; i++) {
            blank.paper.appendStroke(TestInk.stroke(10f, i * PAGE + 20f, 8));
        }
        repo.saveInk(blank);
        for (int i = 0; i < 20 && repo.pendingWrites() > 0; i++) {
            Thread.sleep(20);
        }
        String sheetId = NotebookPaper.sheetId(null);
        device.db.dao().deleteOutbox(OutboxEntry.LOG, sheetId);
        device.db.dao().deleteNotebookLog(sheetId);
        repo.invalidateOpenSheet(null);
        int before = repo.outboxSize();
        assertTrue(repo.ensureSheet(null, PAGE, PAGE, 0L));
        assertNotNull(device.db.dao().notebookLog(sheetId));
        assertEquals("meta heal must not enqueue truncated disk as push SoT",
                before, repo.outboxSize());
    }

    @Test
    public void longerRemoteRecoversTruncatedLocalDespitePendingNewerMeta() throws Exception {
        // Device stuck at short Ready (v79 truncate) with a queued meta row whose
        // updatedAt beats the server — v80 left this frozen at 23 forever.
        byte[] shortBytes = shortLogBytes(22);
        String sheetId = NotebookPaper.sheetId(null);
        new InkFileStore(device.inkDir()).writeLog(sheetId, shortBytes);
        assertTrue(repo.ensureSheet(null, PAGE, PAGE, 0L));
        int shortPages = repo.scratchpadPages().size();
        assertTrue(shortPages < 30);

        NotebookLogEntity local = device.db.dao().notebookLog(sheetId);
        assertNotNull(local);
        local.updatedAt = device.clock.get() + 1_000_000L;
        device.db.dao().upsertNotebookLog(local);
        device.db.dao().deleteOutbox(OutboxEntry.LOG, sheetId);
        OutboxEntry pending = new OutboxEntry();
        pending.entity = OutboxEntry.LOG;
        pending.id = sheetId;
        pending.op = OutboxEntry.UPSERT;
        pending.queuedAt = device.clock.get();
        device.db.dao().upsertOutbox(pending);

        byte[] longBytes = shortLogBytes(40);
        NotebookLogEntity remote = local.copy();
        remote.inkHash = InkFileStore.sha256(longBytes);
        remote.inkBytes = longBytes.length;
        remote.rev = Math.max(1, local.rev) + 10;
        remote.updatedAt = local.updatedAt - 500_000L; // older clock than pending local
        SyncStore.PullPage pull = new SyncStore.PullPage();
        pull.logs.add(remote);
        pull.blobs.put(remote.inkHash, longBytes);
        pull.cursor = remote.rev;
        repo.applyPull(pull);

        int recovered = repo.scratchpadPages().size();
        assertTrue("longer remote must recover truncated Ready (" + shortPages
                        + " → " + recovered + ")",
                recovered > shortPages);
        assertTrue(recovered >= 40);
    }

    @Test
    public void localInkLogBytesIgnoresStaleRoomMetaWhenFileMissing() throws Exception {
        String sheetId = NotebookPaper.sheetId(null);
        NotebookLogEntity row = new NotebookLogEntity();
        row.id = sheetId;
        row.inkHash = "deadbeef";
        row.inkBytes = 17_175_780;
        row.sliceHeight = PAGE;
        row.createdAt = device.clock.get();
        row.updatedAt = device.clock.get();
        row.rev = 387;
        device.db.dao().upsertNotebookLog(row);
        assertEquals("missing .zdl must report 0 so reconcile still fetches",
                0L, repo.localInkLogBytes(sheetId));
        assertEquals("", repo.localInkLogHash(sheetId));
    }

    @Test
    public void longerRemoteBytesAdoptEvenWhenReadySliceCountConfused() throws Exception {
        // Disk short; Ready open somehow reports equal/higher slices than remote —
        // still adopt when remote blob is larger on disk (cloud primary).
        byte[] shortBytes = shortLogBytes(22);
        String sheetId = NotebookPaper.sheetId(null);
        new InkFileStore(device.inkDir()).writeLog(sheetId, shortBytes);
        assertTrue(repo.ensureSheet(null, PAGE, PAGE, 0L));
        assertTrue(repo.localInkLogBytes(sheetId) < 1_000_000L);

        byte[] longBytes = shortLogBytes(40);
        NotebookLogEntity local = device.db.dao().notebookLog(sheetId);
        assertNotNull(local);
        NotebookLogEntity remote = local.copy();
        remote.inkHash = InkFileStore.sha256(longBytes);
        remote.inkBytes = longBytes.length;
        remote.rev = Math.max(1, local.rev) + 5;
        remote.updatedAt = local.updatedAt + 1;
        SyncStore.PullPage pull = new SyncStore.PullPage();
        pull.logs.add(remote);
        pull.blobs.put(remote.inkHash, longBytes);
        pull.cursor = remote.rev;
        repo.applyPull(pull);

        assertTrue(repo.scratchpadPages().size() >= 40);
        assertEquals(longBytes.length, repo.localInkLogBytes(sheetId));
        assertEquals(remote.inkHash, repo.localInkLogHash(sheetId));
    }

    @Test
    public void longerScratchpadPullDoesNotTouchOtherNotebookLog() throws Exception {
        assertTrue(repo.ensureSheet(null, PAGE, PAGE, 0L));
        Board blank = repo.scratchpadPages().get(0);
        for (int i = 0; i < 8; i++) {
            blank.paper.appendStroke(TestInk.stroke(10f, i * PAGE + 20f, 8));
        }
        repo.saveInk(blank);
        for (int i = 0; i < 20 && repo.pendingWrites() > 0; i++) {
            Thread.sleep(20);
        }
        String otherId = repo.createNotebook("safe-notes").id();
        assertTrue(repo.ensureSheet(otherId, PAGE, PAGE, 0L));
        Board otherBlank = repo.pages(otherId).get(0);
        for (int i = 0; i < 5; i++) {
            otherBlank.paper.appendStroke(TestInk.stroke(12f, i * PAGE + 30f, 6));
        }
        repo.saveInk(otherBlank);
        for (int i = 0; i < 20 && repo.pendingWrites() > 0; i++) {
            Thread.sleep(20);
        }
        int otherPages = repo.pages(otherId).size();
        byte[] otherBytes = new InkFileStore(device.inkDir()).readLog(otherId);
        assertNotNull(otherBytes);

        // Truncate Scratchpad only; leave other notebook intact.
        String scratchId = NotebookPaper.sheetId(null);
        byte[] shortBytes = shortLogBytes(10);
        new InkFileStore(device.inkDir()).writeLog(scratchId, shortBytes);
        repo.invalidateOpenSheet(null);
        assertTrue(repo.ensureSheet(null, PAGE, PAGE, 0L));

        byte[] longScratch = shortLogBytes(30);
        NotebookLogEntity remote = device.db.dao().notebookLog(scratchId).copy();
        remote.inkHash = InkFileStore.sha256(longScratch);
        remote.inkBytes = longScratch.length;
        remote.rev = remote.rev + 3;
        remote.updatedAt = remote.updatedAt + 1;
        SyncStore.PullPage pull = new SyncStore.PullPage();
        pull.logs.add(remote);
        pull.blobs.put(remote.inkHash, longScratch);
        pull.cursor = remote.rev;
        repo.applyPull(pull);

        assertTrue(repo.scratchpadPages().size() >= 30);
        assertEquals("other notebook page count unchanged", otherPages, repo.pages(otherId).size());
        byte[] otherAfter = new InkFileStore(device.inkDir()).readLog(otherId);
        assertNotNull(otherAfter);
        assertTrue("other notebook .zdl bytes unchanged",
                java.util.Arrays.equals(otherBytes, otherAfter));
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
