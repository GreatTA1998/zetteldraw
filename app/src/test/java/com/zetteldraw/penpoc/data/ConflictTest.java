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

/** Last-write-wins with hidden conflict copies, on the device side of sync. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class)
public class ConflictTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Device device;
    private RoomBoardRepository repo;
    private Board page;

    @Before
    public void setUp() throws Exception {
        device = new Device("a", tmp.newFolder("a"), 10_000_000L);
        repo = device.repo;
        page = repo.scratchpadPages().get(0);
        TestInk.draw(page, 10f);
        repo.saveInk(page);
        repo.createScratchpadPage();
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
        repo.setRemoteChangeListener(notified::incrementAndGet);

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
    public void pushResultsAdvanceRevAndClearOutboxUnlessEditedMidFlight() {
        SyncStore.PushBatch batch = repo.pendingPush(50);
        assertEquals(4, batch.notebooks.size());
        assertEquals(1, batch.boards.size());
        String hash = batch.boards.get(0).inkHash;
        assertEquals(hash, InkFileStore.sha256(batch.blobs.get(hash)));

        TestInk.draw(page, 99f);
        device.tick();
        repo.saveInk(page);

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
