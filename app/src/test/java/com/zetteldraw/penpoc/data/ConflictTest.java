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

/**
 * Exception-path conflict shelves on the device side of sync (Hand off /
 * history). Day-to-day sync is single live writer push + pull catch-up.
 *
 * <p>Ready path shelves losing local {@code .zdl} via {@link InkSheetStore}.
 * BoardEntity {@code .zdi} conflict shelf is deleted — unmigrated board pull
 * writes migration residue without shelving local page-file ink or UI stroke swap.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class)
public class ConflictTest {
    private static final int PAGE = 800;

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Device device;
    private RoomBoardRepository repo;

    @Before
    public void setUp() throws Exception {
        device = new Device("a", tmp.newFolder("a"), 10_000_000L);
        repo = device.repo;
        assertTrue(device.ensureReady());
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
    public void newerRemoteLogWinsAndLocalLogBecomesHiddenCopy() throws Exception {
        Board ready = repo.scratchpadPages().get(0);
        TestInk.draw(ready, 10f);
        repo.saveInk(ready);
        drainWrites(repo);

        String sheetId = NotebookPaper.sheetId(null);
        NotebookLogEntity local = device.db.dao().notebookLog(sheetId);
        assertNotNull(local);
        assertNotNull(device.db.dao().outbox(OutboxEntry.LOG, sheetId));
        String localHash = local.inkHash;
        byte[] localBytes = new InkFileStore(device.inkDir()).readLog(sheetId);
        assertNotNull(localBytes);

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
        repo.applyPull(pull);

        NotebookLogEntity after = device.db.dao().notebookLog(sheetId);
        assertEquals(remote.rev, after.rev);
        assertEquals(remote.inkHash, after.inkHash);
        assertNull(device.db.dao().outbox(OutboxEntry.LOG, sheetId));
        assertEquals(remote.inkHash, repo.localInkLogHash(sheetId));

        List<NotebookLogEntity> copies = device.db.dao().conflictLogCopies();
        assertEquals(1, copies.size());
        NotebookLogEntity copy = copies.get(0);
        assertEquals(sheetId, copy.conflictOf);
        assertEquals(localHash, copy.inkHash);
        assertEquals(0, copy.rev);
        assertNotNull("copy is pushed so the server keeps it too",
                device.db.dao().outbox(OutboxEntry.LOG, copy.id));
        byte[] shelved = new InkFileStore(device.inkDir()).readLog(copy.id);
        assertNotNull(shelved);
        assertTrue(java.util.Arrays.equals(localBytes, shelved));
        for (Board b : repo.scratchpadPages()) {
            assertNotEquals("copies stay hidden from Ready projection", copy.id, b.id);
        }
    }

    @Test
    public void readyNewerLocalLogSurvivesOlderRemoteWithoutShelf() throws Exception {
        Board ready = repo.scratchpadPages().get(0);
        TestInk.draw(ready, 11f);
        repo.saveInk(ready);
        drainWrites(repo);

        String sheetId = NotebookPaper.sheetId(null);
        NotebookLogEntity local = device.db.dao().notebookLog(sheetId);
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
        repo.applyPull(pull);

        assertEquals(localHash, device.db.dao().notebookLog(sheetId).inkHash);
        assertNotNull(device.db.dao().outbox(OutboxEntry.LOG, sheetId));
        assertTrue(device.db.dao().conflictLogCopies().isEmpty());
    }

    @Test
    public void unmigratedRemoteBoardWinsWithoutShelvingLocal() throws Exception {
        Device bare = new Device("bare", tmp.newFolder("bare"), 11_000_000L);
        try {
            Board page = Board.blank();
            TestInk.draw(page, 10f);
            bare.seedUnmigrated(page);
            BoardEntity local = bare.db.dao().board(page.id);
            assertNotNull(local);
            assertNotNull(bare.db.dao().outbox(OutboxEntry.BOARD, page.id));

            List<InkRenderer.InkStroke> remoteInk =
                    Collections.singletonList(TestInk.stroke(500f, 600f, 9));
            SyncStore.PullPage pulled = remoteBoardPage(local, remoteInk, local.updatedAt + 5_000, 10);
            bare.repo.applyPull(pulled);

            BoardEntity after = bare.db.dao().board(page.id);
            assertEquals(10, after.rev);
            assertEquals(pulled.boards.get(0).inkHash, after.inkHash);
            assertNull(bare.db.dao().outbox(OutboxEntry.BOARD, page.id));
            assertTrue("BoardEntity shelf deleted — no hidden copy",
                    bare.db.dao().conflictCopies().isEmpty());
            TestInk.assertSameInk(remoteInk, InkCodec.decode(
                    new InkFileStore(bare.inkDir()).read(page.id)));
        } finally {
            bare.close();
        }
    }

    @Test
    public void pushResultsAdvanceLogRevAndClearOutboxUnlessEditedMidFlight() throws Exception {
        Board ready = repo.scratchpadPages().get(0);
        TestInk.draw(ready, 10f);
        repo.saveInk(ready);
        drainWrites(repo);

        String sheetId = NotebookPaper.sheetId(null);
        repo.queuePlaceholderSeedsForUpload();
        SyncStore.PushBatch batch = repo.pendingPush(50);
        assertEquals(1, batch.logs.size());
        String hash = batch.logs.get(0).inkHash;
        assertEquals(hash, InkFileStore.sha256(batch.blobs.get(hash)));

        TestInk.draw(ready, 99f);
        device.tick();
        repo.saveInk(ready);
        drainWrites(repo);

        List<SyncStore.PushResult> results = new ArrayList<>();
        for (var n : batch.notebooks) {
            results.add(new SyncStore.PushResult(OutboxEntry.NOTEBOOK, n.id, SyncStore.PushResult.APPLIED, 3));
        }
        results.add(new SyncStore.PushResult(OutboxEntry.LOG, sheetId, SyncStore.PushResult.APPLIED, 7));
        repo.applyPushResults(batch, results);

        assertTrue(repo.outboxSize() >= 1);
        assertNotNull("edited after the snapshot: still queued",
                device.db.dao().outbox(OutboxEntry.LOG, sheetId));
        assertEquals(7, device.db.dao().notebookLog(sheetId).rev);
    }

    @Test
    public void lostLogConflictClearsOutboxAndWaitsForPull() throws Exception {
        Board ready = repo.scratchpadPages().get(0);
        TestInk.draw(ready, 10f);
        repo.saveInk(ready);
        drainWrites(repo);
        String sheetId = NotebookPaper.sheetId(null);
        SyncStore.PushBatch batch = repo.pendingPush(50);
        repo.applyPushResults(batch, Collections.singletonList(
                new SyncStore.PushResult(OutboxEntry.LOG, sheetId, SyncStore.PushResult.CONFLICT_LOST, 12)));
        assertNull(device.db.dao().outbox(OutboxEntry.LOG, sheetId));
        assertEquals(0, device.db.dao().notebookLog(sheetId).rev);
    }

    @Test
    public void missingLogBlobStaysQueued() throws Exception {
        Board ready = repo.scratchpadPages().get(0);
        TestInk.draw(ready, 10f);
        repo.saveInk(ready);
        drainWrites(repo);
        String sheetId = NotebookPaper.sheetId(null);
        SyncStore.PushBatch batch = repo.pendingPush(50);
        repo.applyPushResults(batch, Collections.singletonList(
                new SyncStore.PushResult(OutboxEntry.LOG, sheetId, SyncStore.PushResult.MISSING_BLOB, 0)));
        assertNotNull(device.db.dao().outbox(OutboxEntry.LOG, sheetId));
    }

    private static void drainWrites(RoomBoardRepository target) throws Exception {
        for (int i = 0; i < 40 && target.pendingWrites() > 0; i++) {
            Thread.sleep(20);
        }
        assertEquals(0, target.pendingWrites());
    }

    private SyncStore.PullPage remoteBoardPage(BoardEntity base, List<InkRenderer.InkStroke> ink,
                                               long updatedAt, long rev) {
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
