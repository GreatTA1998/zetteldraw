package com.zetteldraw.penpoc.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Exception-path conflict shelves on the device side of sync (Hand off /
 * history). Day-to-day sync is single live writer push + pull catch-up.
 *
 * <p>Ready path shelves losing local {@code .zdl} via {@link InkSheetStore};
 * legacy BoardEntity {@code .zdi} shelf remains in {@link MetaPullApply} until
 * writers no longer need page-file rows.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class)
public class ConflictTest {
    private static final int PAGE = 800;

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Device device;
    private RoomBoardRepository repo;
    private Board page;

    @Before
    public void setUp() throws Exception {
        device = new Device("a", tmp.newFolder("a"), 10_000_000L);
        repo = device.repo;
        // BoardEntity conflict shelf still seeds via legacy page-file path
        // (no ensure — Ready migration would absorb the row into .zdl).
        // saveInk is Ready-only; importer-style sync persist for shelf seeds.
        page = Board.blank();
        TestInk.draw(page, 10f);
        repo.persistBoardEntityInk(page);
    }

    @After
    public void tearDown() {
        device.close();
    }

    @Test
    public void lwwRule() {
        assertTrue("server wins a tie", Lww.remoteWins(100, 100));
        assertTrue(Lww.remoteWins(100, 101));
        assertFalse(Lww.remoteWins(101, 100));
        BoardEntity a = row("a", 1);
        BoardEntity b = row("b", 2);
        assertTrue(Lww.needsConflictCopy(a, b));
        assertFalse(Lww.needsConflictCopy(a, row("a", 3)));
        assertFalse(Lww.needsConflictCopy(row(null, 1), b));
        BoardEntity deleted = row(null, 5);
        deleted.deletedAt = 5L;
        assertTrue("an edit losing to a delete is kept", Lww.needsConflictCopy(a, deleted));
    }

    @Test
    public void newerRemoteEditWinsAndLocalInkBecomesHiddenCopy() throws Exception {
        BoardEntity local = device.db.dao().board(page.id);
        List<InkRenderer.InkStroke> localInk = new ArrayList<>(page.strokes);
        AtomicInteger notified = new AtomicInteger();
        repo.addRemoteChangeListener(notified::incrementAndGet);

        List<InkRenderer.InkStroke> remoteInk = Collections.singletonList(TestInk.stroke(500f, 600f, 9));
        SyncStore.PullPage pulled = remotePage(local, remoteInk, local.updatedAt + 5_000, 10);
        repo.applyPull(pulled);

        BoardEntity after = device.db.dao().board(page.id);
        assertEquals(10, after.rev);
        assertEquals(pulled.boards.get(0).inkHash, after.inkHash);
        assertNull("remote row replaced the pending edit", device.db.dao().outbox(OutboxEntry.BOARD, page.id));
        TestInk.assertSameInk(remoteInk, page.strokes);
        assertEquals(1, notified.get());

        List<BoardEntity> copies = device.db.dao().conflictCopies();
        assertEquals(1, copies.size());
        BoardEntity copy = copies.get(0);
        assertEquals(page.id, copy.conflictOf);
        assertEquals(local.inkHash, copy.inkHash);
        assertEquals(0, copy.rev);
        assertNotNull("copy is pushed so the server keeps it too", device.db.dao().outbox(OutboxEntry.BOARD, copy.id));
        TestInk.assertSameInk(localInk, InkCodec.decode(new InkFileStore(device.inkDir()).read(copy.id)));
        for (Board b : repo.scratchpadPages()) {
            assertNotEquals("copies stay hidden", copy.id, b.id);
        }
        assertEquals(10, repo.pullCursor());
    }

    @Test
    public void newerLocalEditSurvivesAnOlderRemoteOne() throws Exception {
        BoardEntity local = device.db.dao().board(page.id);
        repo.applyPull(remotePage(local, Collections.singletonList(TestInk.stroke(1f, 1f, 3)),
                local.updatedAt - 1, 10));
        BoardEntity after = device.db.dao().board(page.id);
        assertEquals(local.inkHash, after.inkHash);
        assertEquals("keeps its stale base rev so the server resolves it on push", 0, after.rev);
        assertNotNull(device.db.dao().outbox(OutboxEntry.BOARD, page.id));
        assertTrue(device.db.dao().conflictCopies().isEmpty());
    }

    @Test
    public void remoteChangeWithoutLocalEditsJustApplies() throws Exception {
        BoardEntity local = device.db.dao().board(page.id);
        device.db.dao().deleteOutbox(OutboxEntry.BOARD, page.id);
        repo.applyPull(remotePage(local, Collections.singletonList(TestInk.stroke(1f, 1f, 3)),
                local.updatedAt - 1, 10));
        assertNotEquals(local.inkHash, device.db.dao().board(page.id).inkHash);
        assertTrue(device.db.dao().conflictCopies().isEmpty());
        repo.applyPull(remotePage(local, Collections.singletonList(TestInk.stroke(2f, 2f, 3)),
                local.updatedAt + 1, 9));
        assertEquals("older revs are ignored", 10, device.db.dao().board(page.id).rev);
    }

    @Test
    public void pushResultsAdvanceRevAndClearOutboxUnlessEditedMidFlight() throws Exception {
        repo.queuePlaceholderSeedsForUpload();
        SyncStore.PushBatch batch = repo.pendingPush(50);
        assertEquals(4, batch.notebooks.size());
        assertEquals(1, batch.boards.size());
        String hash = batch.boards.get(0).inkHash;
        assertEquals(hash, InkFileStore.sha256(batch.blobs.get(hash)));

        TestInk.draw(page, 99f);
        device.tick();
        repo.persistBoardEntityInk(page);

        List<SyncStore.PushResult> results = new ArrayList<>();
        for (var n : batch.notebooks) {
            results.add(new SyncStore.PushResult(OutboxEntry.NOTEBOOK, n.id, SyncStore.PushResult.APPLIED, 3));
        }
        results.add(new SyncStore.PushResult(OutboxEntry.BOARD, page.id, SyncStore.PushResult.APPLIED, 7));
        repo.applyPushResults(batch, results);

        assertEquals(1, repo.outboxSize());
        assertNotNull("edited after the snapshot: still queued", device.db.dao().outbox(OutboxEntry.BOARD, page.id));
        assertEquals(7, device.db.dao().board(page.id).rev);
    }

    @Test
    public void lostConflictClearsOutboxAndWaitsForPull() {
        SyncStore.PushBatch batch = repo.pendingPush(50);
        repo.applyPushResults(batch, Collections.singletonList(
                new SyncStore.PushResult(OutboxEntry.BOARD, page.id, SyncStore.PushResult.CONFLICT_LOST, 12)));
        assertNull(device.db.dao().outbox(OutboxEntry.BOARD, page.id));
        assertEquals(0, device.db.dao().board(page.id).rev);
    }

    @Test
    public void missingBlobStaysQueued() {
        SyncStore.PushBatch batch = repo.pendingPush(50);
        repo.applyPushResults(batch, Collections.singletonList(
                new SyncStore.PushResult(OutboxEntry.BOARD, page.id, SyncStore.PushResult.MISSING_BLOB, 0)));
        assertNotNull(device.db.dao().outbox(OutboxEntry.BOARD, page.id));
    }

    @Test
    public void newerRemoteLogWinsAndLocalLogBecomesHiddenCopy() throws Exception {
        // Fresh Ready device — no legacy BoardEntity seed (that would inflate local .zdl).
        Device readyDevice = new Device("ready-shelf", tmp.newFolder("ready-shelf"), 20_000_000L);
        RoomBoardRepository readyRepo = readyDevice.repo;
        try {
            assertTrue(readyDevice.ensureReady());
            Board ready = readyRepo.scratchpadPages().get(0);
            TestInk.draw(ready, 10f);
            readyRepo.saveInk(ready);
            drainWrites(readyRepo);

            String sheetId = NotebookPaper.sheetId(null);
            NotebookLogEntity local = readyDevice.db.dao().notebookLog(sheetId);
            assertNotNull(local);
            assertNotNull(readyDevice.db.dao().outbox(OutboxEntry.LOG, sheetId));
            String localHash = local.inkHash;
            byte[] localBytes = new InkFileStore(readyDevice.inkDir()).readLog(sheetId);
            assertNotNull(localBytes);

            // Same slice count; remote body not shorter on disk (install refuses shrink).
            NotebookPaper remotePaper = NotebookPaper.empty(PAGE);
            remotePaper.appendStroke(TestInk.stroke(500f, 60f, 24), ready.id);
            byte[] remoteBytes = remotePaper.bytes();
            assertTrue("remote must not be shorter than local disk",
                    remoteBytes.length >= localBytes.length);
            NotebookLogEntity remote = local.copy();
            remote.inkHash = InkFileStore.sha256(remoteBytes);
            remote.inkBytes = remoteBytes.length;
            remote.updatedAt = local.updatedAt + 5_000;
            remote.rev = Math.max(1, local.rev) + 10;
            SyncStore.PullPage pull = new SyncStore.PullPage();
            pull.logs.add(remote);
            pull.blobs.put(remote.inkHash, remoteBytes);
            pull.cursor = remote.rev;
            readyRepo.applyPull(pull);

            NotebookLogEntity after = readyDevice.db.dao().notebookLog(sheetId);
            assertEquals(remote.rev, after.rev);
            assertEquals(remote.inkHash, after.inkHash);
            assertNull(readyDevice.db.dao().outbox(OutboxEntry.LOG, sheetId));
            assertEquals(remote.inkHash, readyRepo.localInkLogHash(sheetId));

            List<NotebookLogEntity> copies = readyDevice.db.dao().conflictLogCopies();
            assertEquals(1, copies.size());
            NotebookLogEntity copy = copies.get(0);
            assertEquals(sheetId, copy.conflictOf);
            assertEquals(localHash, copy.inkHash);
            assertEquals(0, copy.rev);
            assertNotNull("copy is pushed so the server keeps it too",
                    readyDevice.db.dao().outbox(OutboxEntry.LOG, copy.id));
            byte[] shelved = new InkFileStore(readyDevice.inkDir()).readLog(copy.id);
            assertNotNull(shelved);
            assertTrue(java.util.Arrays.equals(localBytes, shelved));
            for (Board b : readyRepo.scratchpadPages()) {
                assertNotEquals("copies stay hidden from Ready projection", copy.id, b.id);
            }
        } finally {
            readyDevice.close();
        }
    }

    @Test
    public void readyNewerLocalLogSurvivesOlderRemoteWithoutShelf() throws Exception {
        Device readyDevice = new Device("ready-keep", tmp.newFolder("ready-keep"), 21_000_000L);
        RoomBoardRepository readyRepo = readyDevice.repo;
        try {
            assertTrue(readyDevice.ensureReady());
            Board ready = readyRepo.scratchpadPages().get(0);
            TestInk.draw(ready, 11f);
            readyRepo.saveInk(ready);
            drainWrites(readyRepo);

            String sheetId = NotebookPaper.sheetId(null);
            NotebookLogEntity local = readyDevice.db.dao().notebookLog(sheetId);
            assertNotNull(local);
            String localHash = local.inkHash;

            NotebookPaper remotePaper = NotebookPaper.empty(PAGE);
            remotePaper.appendStroke(TestInk.stroke(1f, 60f, 12), ready.id);
            byte[] remoteBytes = remotePaper.bytes();
            NotebookLogEntity remote = local.copy();
            remote.inkHash = InkFileStore.sha256(remoteBytes);
            remote.inkBytes = remoteBytes.length;
            remote.updatedAt = local.updatedAt - 1;
            remote.rev = Math.max(1, local.rev) + 10;
            SyncStore.PullPage pull = new SyncStore.PullPage();
            pull.logs.add(remote);
            pull.blobs.put(remote.inkHash, remoteBytes);
            pull.cursor = remote.rev;
            readyRepo.applyPull(pull);

            assertEquals(localHash, readyDevice.db.dao().notebookLog(sheetId).inkHash);
            assertNotNull(readyDevice.db.dao().outbox(OutboxEntry.LOG, sheetId));
            assertTrue(readyDevice.db.dao().conflictLogCopies().isEmpty());
        } finally {
            readyDevice.close();
        }
    }

    private static void drainWrites(RoomBoardRepository target) throws Exception {
        for (int i = 0; i < 40 && target.pendingWrites() > 0; i++) {
            Thread.sleep(20);
        }
        assertEquals(0, target.pendingWrites());
    }

    private SyncStore.PullPage remotePage(BoardEntity base, List<InkRenderer.InkStroke> ink, long updatedAt, long rev) {
        byte[] bytes = InkCodec.encode(ink);
        BoardEntity remote = base.copy();
        remote.inkHash = InkFileStore.sha256(bytes);
        remote.inkBytes = bytes.length;
        remote.updatedAt = updatedAt;
        remote.rev = rev;
        SyncStore.PullPage p = new SyncStore.PullPage();
        p.boards.add(remote);
        p.blobs.put(remote.inkHash, bytes);
        p.cursor = rev;
        return p;
    }

    private static BoardEntity row(String hash, long updatedAt) {
        BoardEntity b = new BoardEntity();
        b.id = "x";
        b.position = "a0";
        b.inkHash = hash;
        b.updatedAt = updatedAt;
        return b;
    }
}
