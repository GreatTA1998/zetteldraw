package com.zetteldraw.penpoc.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;

import com.zetteldraw.penpoc.Board;
import com.zetteldraw.penpoc.InkRenderer;
import com.zetteldraw.penpoc.Notebook;
import com.zetteldraw.penpoc.data.db.BoardEntity;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Ink saves are write-behind on one writer thread. With the disk stalled, the
 * UI-facing calls must still return at once, and the eventual file, row and
 * outbox must match the newest snapshot, whatever happened meanwhile.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class)
public class WriteBehindTest {
    /** Far below {@link SlowInkFileStore#SAFETY_TIMEOUT_MS}: waiting on the disk would blow it. */
    private static final long UI_BUDGET_MS = 1_000;

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private ZettelDatabase db;
    private File inkDir;
    private SlowInkFileStore ink;
    private ExecutorService writer;
    private RoomBoardRepository repo;
    private final AtomicLong clock = new AtomicLong(5_000_000L);

    @Before
    public void setUp() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        File root = tmp.newFolder("device");
        db = Room.databaseBuilder(context, ZettelDatabase.class, new File(root, "zetteldraw.db").getPath())
                .allowMainThreadQueries()
                .build();
        inkDir = new File(root, "ink");
        ink = new SlowInkFileStore(inkDir);
        writer = Executors.newSingleThreadExecutor();
        repo = new RoomBoardRepository(db, ink, InkMirror.NONE, writer, Runnable::run, Runnable::run, clock::get);
        drain();
    }

    @After
    public void tearDown() throws Exception {
        ink.release();
        writer.shutdown();
        writer.awaitTermination(10, TimeUnit.SECONDS);
        db.close();
    }

    @Test
    public void saveReturnsAndTheUiKeepsWorkingWhileTheDiskIsStuck() throws Exception {
        Board page = repo.scratchpadPages().get(0);
        TestInk.draw(page, 10f);

        long started = System.nanoTime();
        repo.saveInk(page);
        ink.awaitStall();
        List<Board> pages = repo.scratchpadPages();
        repo.notebooks();
        repo.notebookPages(Notebook.JOURNAL.uuid);
        TestInk.draw(page, 40f);
        repo.saveInk(page);
        long ms = elapsedMs(started);

        assertTrue("UI calls took " + ms + " ms with the disk stuck", ms < UI_BUDGET_MS);
        assertEquals("the page counts as inked and a blank follows at once", 2, pages.size());
        assertSame(page, pages.get(0));
        assertTrue(pages.get(1).isBlank());
        assertNull("nothing is on disk yet", db.dao().board(page.id).inkHash);

        ink.release();
        drain();
        BoardEntity row = db.dao().board(page.id);
        byte[] onDisk = new InkFileStore(inkDir).read(page.id);
        assertEquals(row.inkHash, InkFileStore.sha256(onDisk));
        TestInk.assertSameInk(page.strokes, InkCodec.decode(onDisk));
        assertNotNull("queued for sync once written", db.dao().outbox(OutboxEntry.BOARD, page.id));
        assertEquals(0, repo.pendingWrites());
    }

    @Test
    public void manySavesOfOnePageCostOneMoreWrite() throws Exception {
        Board page = repo.scratchpadPages().get(0);
        TestInk.draw(page, 10f);
        repo.saveInk(page);
        ink.awaitStall();
        for (int i = 0; i < 5; i++) {
            TestInk.draw(page, 100f + i * 20f);
            repo.saveInk(page);
        }
        ink.release();
        drain();

        assertTrue("stuck write plus the newest snapshot, not one per stroke: " + ink.stages.get(),
                ink.stages.get() <= 2);
        TestInk.assertSameInk(page.strokes, InkCodec.decode(new InkFileStore(inkDir).read(page.id)));
        assertEquals(6, page.strokes.size());
    }

    @Test
    public void undoToBlankWhileTheWriteIsStuckLeavesNoInkBehind() throws Exception {
        Board page = repo.scratchpadPages().get(0);
        TestInk.draw(page, 10f);
        repo.saveInk(page);
        ink.awaitStall();
        page.strokes.clear();
        repo.saveInk(page);

        List<Board> pages = repo.scratchpadPages();
        assertEquals("the extra blank collapses immediately", 1, pages.size());
        assertSame(page, pages.get(0));

        ink.release();
        drain();
        assertNull(db.dao().board(page.id).inkHash);
        assertNull("the stale snapshot was never committed", new InkFileStore(inkDir).read(page.id));
        assertEquals(1, repo.scratchpadPages().size());
    }

    @Test
    public void deletingAPageWhileItsWriteIsStuckKeepsItDeleted() throws Exception {
        Board first = repo.scratchpadPages().get(0);
        TestInk.draw(first, 10f);
        repo.saveInk(first);
        ink.release();
        drain();

        ink.hold();
        Board second = repo.scratchpadPages().get(1);
        TestInk.draw(second, 10f);
        repo.saveInk(second);
        ink.awaitStall();
        repo.deletePage(second.id);
        ink.release();
        drain();

        BoardEntity row = db.dao().board(second.id);
        assertNotNull(row.deletedAt);
        assertNull(row.inkHash);
        assertNull(new InkFileStore(inkDir).read(second.id));
        assertEquals(OutboxEntry.DELETE, db.dao().outbox(OutboxEntry.BOARD, second.id).op);
    }

    @Test
    public void pushOnlySendsInkThatIsOnDisk() throws Exception {
        Board page = repo.scratchpadPages().get(0);
        TestInk.draw(page, 10f);
        repo.saveInk(page);
        ink.release();
        drain();
        String written = db.dao().board(page.id).inkHash;

        ink.hold();
        TestInk.draw(page, 60f);
        repo.saveInk(page);
        ink.awaitStall();
        long started = System.nanoTime();
        SyncStore.PushBatch batch = repo.pendingPush(50);
        assertTrue("push preparation does not wait on the stuck write", elapsedMs(started) < UI_BUDGET_MS);
        BoardEntity pushed = boardIn(batch, page.id);
        assertEquals(written, pushed.inkHash);
        assertEquals(written, InkFileStore.sha256(batch.blobs.get(written)));

        ink.release();
        drain();
        pushed = boardIn(repo.pendingPush(50), page.id);
        assertNotEquals(written, pushed.inkHash);
    }

    @Test
    public void aPullNeverOverwritesAnEditStillBeingWritten() throws Exception {
        Board page = repo.scratchpadPages().get(0);
        TestInk.draw(page, 10f);
        repo.saveInk(page);
        ink.release();
        drain();
        SyncStore.PushBatch batch = repo.pendingPush(50);
        ArrayList<SyncStore.PushResult> results = new ArrayList<>();
        results.add(new SyncStore.PushResult(OutboxEntry.BOARD, page.id, SyncStore.PushResult.APPLIED, 1));
        repo.applyPushResults(batch, results);
        assertNull("the first version is synced", db.dao().outbox(OutboxEntry.BOARD, page.id));

        ink.hold();
        TestInk.draw(page, 70f);
        repo.saveInk(page);
        ink.awaitStall();
        List<InkRenderer.InkStroke> local = new ArrayList<>(page.strokes);

        BoardEntity remote = db.dao().board(page.id).copy();
        List<InkRenderer.InkStroke> remoteInk = Collections.singletonList(TestInk.stroke(400f, 400f, 7));
        byte[] remoteBytes = InkCodec.encode(remoteInk);
        remote.inkHash = InkFileStore.sha256(remoteBytes);
        remote.inkBytes = remoteBytes.length;
        remote.rev = 5;
        remote.updatedAt = clock.get() + 60_000;
        SyncStore.PullPage pulled = new SyncStore.PullPage();
        pulled.boards.add(remote);
        pulled.blobs.put(remote.inkHash, remoteBytes);
        pulled.cursor = 5;
        long started = System.nanoTime();
        repo.applyPull(pulled);
        assertTrue("a pull does not wait on the stuck write", elapsedMs(started) < UI_BUDGET_MS);

        assertEquals("the unwritten local edit is kept", 1, db.dao().board(page.id).rev);
        TestInk.assertSameInk(local, page.strokes);
        assertEquals(5, repo.pullCursor());

        ink.release();
        drain();
        BoardEntity row = db.dao().board(page.id);
        TestInk.assertSameInk(local, InkCodec.decode(new InkFileStore(inkDir).read(page.id)));
        assertEquals(row.inkHash, InkFileStore.sha256(new InkFileStore(inkDir).read(page.id)));
        assertNotNull("the local edit pushes next and the server settles it", db.dao().outbox(OutboxEntry.BOARD, page.id));
    }

    private static BoardEntity boardIn(SyncStore.PushBatch batch, String id) {
        for (BoardEntity row : batch.boards) {
            if (row.id.equals(id)) {
                return row;
            }
        }
        throw new AssertionError("board not in push batch");
    }

    /** Waits until every snapshot is committed. */
    private void drain() throws Exception {
        for (int round = 0; round < 3; round++) {
            writer.submit(() -> { }).get(10, TimeUnit.SECONDS);
        }
        long deadline = System.currentTimeMillis() + 10_000;
        while (repo.pendingWrites() > 0 && System.currentTimeMillis() < deadline) {
            writer.submit(() -> { }).get(10, TimeUnit.SECONDS);
        }
        assertFalse("writer drained", repo.pendingWrites() > 0);
    }

    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }
}
