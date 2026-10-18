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
import com.zetteldraw.penpoc.Notebook;
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
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Ready-path ink saves flush the sheet log on one writer thread. With the disk
 * stalled, UI-facing calls must still return at once; the eventual {@code .zdl}
 * and log outbox must match the newest paper.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class)
public class WriteBehindTest {
    /** Far below {@link SlowInkFileStore#SAFETY_TIMEOUT_MS}: waiting on the disk would blow it. */
    private static final long UI_BUDGET_MS = 1_000;
    private static final int PAGE = 800;

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
        ink.release();
        drain();
        assertTrue(repo.ensureSheet(null, PAGE, PAGE, 0L));
        if (repo.scratchpadPages().isEmpty()) {
            assertNotNull(repo.createPage(null));
        }
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
        ink.hold();
        repo.saveInk(page);
        ink.awaitStall();
        List<Board> pages = repo.scratchpadPages();
        repo.notebooks();
        // Do not ensure another sheet while held — migration writeLog would block the test thread.
        assertTrue(repo.pages(Notebook.JOURNAL.uuid).isEmpty());
        TestInk.draw(page, 40f);
        repo.saveInk(page);
        long ms = elapsedMs(started);

        assertTrue("UI calls took " + ms + " ms with the disk stuck", ms < UI_BUDGET_MS);
        assertEquals(1, pages.size());
        assertSame(page, pages.get(0));
        assertFalse(page.isBlank());
        String sheetId = NotebookPaper.sheetId(null);
        NotebookLogEntity mid = db.dao().notebookLog(sheetId);
        // First ensure may have written an empty log; append of ink is still stuck.
        assertTrue("log append still in flight or empty meta only",
                mid == null || mid.inkBytes == 0 || ink.stages.get() >= 1);

        ink.release();
        drain();
        NotebookLogEntity row = db.dao().notebookLog(sheetId);
        byte[] onDisk = new InkFileStore(inkDir).readLog(sheetId);
        assertNotNull(row);
        assertNotNull(onDisk);
        assertEquals(row.inkHash, InkFileStore.sha256(onDisk));
        assertNotNull("queued for sync once written", db.dao().outbox(OutboxEntry.LOG, sheetId));
    }

    @Test
    public void manySavesOfOnePageCostOneMoreWrite() throws Exception {
        // createPage flush in setUp already staged writes — measure delta only.
        int stagesBefore = ink.stages.get();
        Board page = repo.scratchpadPages().get(0);
        TestInk.draw(page, 10f);
        ink.hold();
        repo.saveInk(page);
        ink.awaitStall();
        for (int i = 0; i < 5; i++) {
            TestInk.draw(page, 100f + i * 20f);
            repo.saveInk(page);
        }
        ink.release();
        drain();

        int delta = ink.stages.get() - stagesBefore;
        assertTrue("stuck write plus the newest snapshot, not one per stroke: delta=" + delta
                        + " absolute=" + ink.stages.get(),
                delta <= 3);
        byte[] onDisk = new InkFileStore(inkDir).readLog(NotebookPaper.sheetId(null));
        assertNotNull(onDisk);
        assertEquals(6, TestInk.strokesOf(page).size());
        assertTrue(onDisk.length >= page.paper.bytes().length - 64);
    }

    @Test
    public void undoToBlankWhileTheWriteIsStuckLeavesNoInkBehind() throws Exception {
        Board page = repo.scratchpadPages().get(0);
        TestInk.draw(page, 10f);
        ink.hold();
        repo.saveInk(page);
        ink.awaitStall();
        page.paper.deleteIds(List.of(TestInk.strokesOf(page).get(0).id));
        repo.saveInk(page);

        List<Board> pages = repo.scratchpadPages();
        assertEquals("the extra blank collapses immediately", 1, pages.size());
        assertSame(page, pages.get(0));
        assertTrue(page.isBlank());

        ink.release();
        drain();
        assertEquals(1, repo.scratchpadPages().size());
        assertTrue(repo.scratchpadPages().get(0).isBlank());
    }

    @Test
    public void deletingAPageWhileItsWriteIsStuckKeepsItDeleted() throws Exception {
        Board first = repo.scratchpadPages().get(0);
        TestInk.draw(first, 10f);
        repo.saveInk(first);
        drain();

        Board second = repo.createPage(null);
        assertNotNull(second);
        ink.hold();
        TestInk.draw(second, 10f);
        repo.saveInk(second);
        ink.awaitStall();
        String secondId = second.id;
        repo.deletePage(secondId);
        ink.release();
        drain();

        List<Board> pages = repo.scratchpadPages();
        // No auto blank after delete — first page only.
        assertEquals(1, pages.size());
        assertEquals(first.id, pages.get(0).id);
        assertTrue("deleted slice id is gone from paper",
                pages.get(0).paper.indexOfSliceId(secondId) < 0);
        String sheetId = NotebookPaper.sheetId(null);
        assertNotNull(db.dao().outbox(OutboxEntry.LOG, sheetId));
    }

    @Test
    public void pushOnlySendsLogThatIsOnDisk() throws Exception {
        Board page = repo.scratchpadPages().get(0);
        TestInk.draw(page, 10f);
        repo.saveInk(page);
        drain();
        String sheetId = NotebookPaper.sheetId(null);
        String written = db.dao().notebookLog(sheetId).inkHash;

        ink.hold();
        TestInk.draw(page, 60f);
        repo.saveInk(page);
        ink.awaitStall();
        long started = System.nanoTime();
        SyncStore.PushBatch batch = repo.pendingPush(50);
        assertTrue("push preparation does not wait on the stuck write", elapsedMs(started) < UI_BUDGET_MS);
        assertEquals(written, logHashIn(batch, sheetId));
        assertEquals(written, InkFileStore.sha256(batch.blobs.get(written)));

        ink.release();
        drain();
        assertNotEquals(written, logHashIn(repo.pendingPush(50), sheetId));
    }

    private static String logHashIn(SyncStore.PushBatch batch, String sheetId) {
        for (var row : batch.logs) {
            if (sheetId.equals(row.id)) {
                return row.inkHash;
            }
        }
        throw new AssertionError("log not in push batch");
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
        // Ready flushes are fire-and-forget on writer; barrier queue tasks.
        writer.submit(() -> { }).get(10, TimeUnit.SECONDS);
        assertFalse("writer drained", repo.pendingWrites() > 0);
    }

    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }
}
