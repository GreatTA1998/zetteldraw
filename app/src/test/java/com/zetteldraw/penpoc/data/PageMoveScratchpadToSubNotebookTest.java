package com.zetteldraw.penpoc.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;

import com.onyx.android.sdk.data.note.TouchPoint;
import com.zetteldraw.penpoc.Board;
import com.zetteldraw.penpoc.InkRenderer;
import com.zetteldraw.penpoc.data.db.NotebookLogEntity;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Elton 2026-10-12: drew on the last Scratchpad page, Move → navigate into a
 * sub-notebook under actions.life → Confirm place → page/ink gone.
 *
 * Root cause family: confirmMoving queues {@code saveInk(source)} before
 * {@code placePageBefore}; with an async writer that flush can persist the
 * in-memory source tear before the destination rewrite, reopening the v46
 * dest-first hole when dest never lands on disk.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class)
public class PageMoveScratchpadToSubNotebookTest {
    private static final int PAGE = 800;

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File root;
    private ZettelDatabase db;
    private RoomBoardRepository repo;
    private final AtomicLong clock = new AtomicLong(4_000_000L);
    private final ArrayList<Runnable> deferred = new ArrayList<>();
    private boolean deferWriter;

    @Before
    public void setUp() throws Exception {
        root = tmp.newFolder("device");
        openRepo(task -> {
            if (deferWriter) {
                deferred.add(task);
            } else {
                task.run();
            }
        });
    }

    @After
    public void tearDown() {
        if (db != null) {
            db.close();
        }
    }

    @Test
    public void lastScratchpadPageIntoActionsLifeSubNotebookKeepsPageAndInk() {
        String actions = repo.createNotebook("actions.life").id();
        String sub = repo.createNotebook("today", actions).id();
        repo.ensureSheet(null, PAGE);
        assertTrue(TestSheets.ready(repo, sub, PAGE));

        Board earlier = inkNearBottom(null, "keep");
        Board last = inkNearBottom(null, "elton-drawing");
        Board destBlank = repo.pages(sub).get(repo.pages(sub).size() - 1);
        assertTrue(destBlank.isBlank());
        String movedId = last.id;

        // Exact UI path after navigating to the sub-notebook: save target,
        // save source (no longer on screen), place before dest blank.
        repo.saveInk(destBlank);
        repo.saveInk(last);
        assertTrue(repo.placePageBefore(movedId, destBlank.id, sub));

        List<Board> scratch = repo.pages(null);
        // Last-page move leaves earlier only — no soft blank auto-appended.
        assertEquals(1, scratch.size());
        assertEquals(earlier.id, scratch.get(0).id);
        assertNotNull(scratch.get(0).paper.stroke("keep"));
        assertTrue("moved page must leave Scratchpad",
                scratch.stream().noneMatch(b -> movedId.equals(b.id)));

        List<Board> dest = repo.pages(sub);
        assertEquals(movedId, dest.get(0).id);
        assertTrue(dest.get(1).isBlank());
        assertNotNull("dest must show the drawing", dest.get(0).paper.stroke("elton-drawing"));
        assertEquals(0, dest.get(0).sliceIndex);
    }

    /**
     * Elton v62 wild failure: snackbar said Place in "home page" (child of
     * actions.life) but Confirm used a stale midline page still bound to the
     * parent sheet — page left Scratchpad and landed on actions.life, so
     * Scratchpad + home page both looked empty.
     */
    @Test
    public void placeIntoHomePageChildIgnoresStaleParentMidlineTarget() {
        String actions = repo.createNotebook("actions.life").id();
        String home = repo.createNotebook("home page", actions).id();
        repo.ensureSheet(null, PAGE);
        assertTrue(TestSheets.ready(repo, actions, PAGE));
        assertTrue(TestSheets.ready(repo, home, PAGE));

        Board parentPage = inkNearBottom(actions, "parent-stay");
        Board drawing = inkNearBottom(null, "second-loss");
        String movedId = drawing.id;

        // Stale target: parent page id, but explicit dest = home page (selected tab).
        assertTrue(repo.placePageBefore(movedId, parentPage.id, home));

        assertTrue("must leave Scratchpad",
                repo.pages(null).stream().noneMatch(b -> movedId.equals(b.id)));
        assertTrue("must NOT misfile onto actions.life parent",
                repo.pages(actions).stream().noneMatch(b -> movedId.equals(b.id)));
        assertNotNull("parent ink stays on actions.life",
                find(repo.pages(actions), parentPage.id).paper.stroke("parent-stay"));
        List<Board> homePages = repo.pages(home);
        Board landed = find(homePages, movedId);
        assertNotNull(landed.paper.stroke("second-loss"));
    }

    @Test
    public void saveInkFlushBeforeDestPersistCannotDropLastScratchpadPage() throws Exception {
        String actions = repo.createNotebook("actions.life").id();
        String sub = repo.createNotebook("errands", actions).id();
        repo.ensureSheet(null, PAGE);
        assertTrue(TestSheets.ready(repo, sub, PAGE));

        inkNearBottom(null, "keep");
        Board last = inkNearBottom(null, "race-ink");
        String movedId = last.id;
        Board destBlank = repo.pages(sub).get(repo.pages(sub).size() - 1);

        deferWriter = true;
        deferred.clear();
        repo.saveInk(destBlank);
        repo.saveInk(last);
        assertTrue(repo.placePageBefore(movedId, destBlank.id, sub));
        assertTrue("confirmMoving queues flushes plus persist", deferred.size() >= 3);

        // Run only the pre-queued saveInk flushes — not the dest-first persist.
        // Pre-fix: source flush appended the tear and the page vanished on reopen.
        deferred.get(0).run();
        deferred.get(1).run();

        db.close();
        deferWriter = false;
        deferred.clear();
        openRepo(Runnable::run);
        repo.ensureSheet(null, PAGE);
        repo.ensureSheet(sub, PAGE);

        boolean onScratch = hasPageWithStroke(null, movedId, "race-ink");
        boolean onSub = hasPageWithStroke(sub, movedId, "race-ink");
        assertTrue(
                "page/ink must survive a crash after saveInk flushes and before dest persist"
                        + " (scratch=" + onScratch + " sub=" + onSub + ")",
                onScratch || onSub);
        assertFalse("must not be duplicated across both notebooks after partial flush",
                onScratch && onSub);
    }

    @Test
    public void syncPullDuringHeldCrossNotebookMoveCannotDropPage() throws Exception {
        String actions = repo.createNotebook("actions.life").id();
        String sub = repo.createNotebook("pull-race", actions).id();
        repo.ensureSheet(null, PAGE);
        assertTrue(TestSheets.ready(repo, sub, PAGE));

        Board last = inkNearBottom(null, "pull-ink");
        String movedId = last.id;
        Board destBlank = repo.pages(sub).get(repo.pages(sub).size() - 1);
        // Seed a local dest log row so LWW/rev checks run.
        repo.saveInk(destBlank);

        deferWriter = true;
        deferred.clear();
        assertTrue(repo.placePageBefore(movedId, destBlank.id, sub));
        assertFalse("persist must be queued while writer is deferred", deferred.isEmpty());

        // Pretend dest is fully flushed after the compact rewrite so the
        // unflushed-bytes skip does not fire — only holdFlush should block pull.
        repo.markSheetFlushedForTest(sub);

        NotebookLogEntity local = db.dao().notebookLog(sub);
        assertNotNull(local);
        byte[] emptyRemote = NotebookPaper.empty(PAGE).bytes();
        NotebookLogEntity remote = local.copy();
        remote.inkHash = InkFileStore.sha256(emptyRemote);
        remote.inkBytes = emptyRemote.length;
        remote.rev = local.rev + 10;
        remote.updatedAt = local.updatedAt + 60_000;
        SyncStore.PullPage pull = new SyncStore.PullPage();
        pull.logs.add(remote);
        pull.blobs.put(remote.inkHash, emptyRemote);
        pull.cursor = remote.rev;
        repo.applyPull(pull);

        ArrayList<Runnable> batch = new ArrayList<>(deferred);
        for (Runnable task : batch) {
            task.run();
        }
        deferred.clear();
        deferWriter = false;

        db.close();
        openRepo(Runnable::run);
        repo.ensureSheet(null, PAGE);
        repo.ensureSheet(sub, PAGE);

        boolean onScratch = hasPageWithStroke(null, movedId, "pull-ink");
        boolean onSub = hasPageWithStroke(sub, movedId, "pull-ink");
        assertTrue("page must survive a sync pull mid cross-notebook place"
                        + " (scratch=" + onScratch + " sub=" + onSub + ")",
                onScratch || onSub);
        assertTrue("after full persist the page should be on the sub-notebook", onSub);
        assertFalse(onScratch);
    }

    /**
     * Elton 2026-10-15: Move Scratchpad → top-level notebook no-ops until the
     * dest is opened (or stays stuck if never opened). UI called {@code pages(dest)}
     * without {@code ensureSheet}; log-backed not-Ready sheets return [].
     * Fixed path: ensure → Create new page if empty → placePageBefore (dest-first).
     */
    @Test
    public void menuPathEnsureThenPlaceIntoInvalidatedTopLevelKeepsPage() {
        String top = repo.createNotebook("journal-like").id();
        repo.ensureSheet(null, PAGE);
        assertTrue(TestSheets.ready(repo, top, PAGE));
        // Touch dest so the ink log exists on disk, then drop the Ready sheet
        // (as after a long Scratchpad-only session that never opened it).
        Board destSeed = repo.pages(top).get(repo.pages(top).size() - 1);
        repo.saveInk(destSeed);
        repo.invalidateOpenSheet(top);
        assertTrue("log-backed not-Ready must refuse legacy projection",
                repo.pages(top).isEmpty());
        assertFalse(repo.isSheetReady(top));

        Board drawing = inkNearBottom(null, "top-level-move");
        String movedId = drawing.id;

        // Exact fixed Move-menu path (CanvasActivity.movePageToNotebook).
        assertTrue(repo.ensureSheet(top, PAGE));
        List<Board> destPages = repo.pages(top);
        assertFalse(destPages.isEmpty());
        Board trailing = destPages.get(destPages.size() - 1);
        assertTrue(repo.placePageBefore(movedId, trailing.id, top));

        assertTrue("must leave Scratchpad",
                repo.pages(null).stream().noneMatch(b -> movedId.equals(b.id)));
        List<Board> dest = repo.pages(top);
        assertEquals(movedId, dest.get(dest.size() - 2).id);
        assertNotNull(dest.get(dest.size() - 2).paper.stroke("top-level-move"));
        assertTrue(dest.get(dest.size() - 1).isBlank());
    }

    @Test
    public void fullConfirmMovingQueueLandsLastPageInSubNotebookAfterDrain() throws Exception {
        String actions = repo.createNotebook("actions.life").id();
        String sub = repo.createNotebook("inbox", actions).id();
        repo.ensureSheet(null, PAGE);
        assertTrue(TestSheets.ready(repo, sub, PAGE));

        inkNearBottom(null, "keep");
        Board last = inkNearBottom(null, "drained");
        String movedId = last.id;
        Board destBlank = repo.pages(sub).get(repo.pages(sub).size() - 1);

        deferWriter = true;
        deferred.clear();
        repo.saveInk(destBlank);
        repo.saveInk(last);
        assertTrue(repo.placePageBefore(movedId, destBlank.id, sub));
        ArrayList<Runnable> batch = new ArrayList<>(deferred);
        for (Runnable task : batch) {
            task.run();
        }
        deferred.clear();
        deferWriter = false;

        db.close();
        openRepo(Runnable::run);
        repo.ensureSheet(null, PAGE);
        repo.ensureSheet(sub, PAGE);

        assertFalse(hasPageWithStroke(null, movedId, "drained"));
        assertTrue(hasPageWithStroke(sub, movedId, "drained"));
    }

    private static Board find(List<Board> pages, String id) {
        for (Board page : pages) {
            if (page.id.equals(id)) {
                return page;
            }
        }
        throw new AssertionError("missing " + id);
    }

    private void openRepo(java.util.concurrent.Executor writer) {
        Context context = ApplicationProvider.getApplicationContext();
        db = Room.databaseBuilder(context, ZettelDatabase.class, new File(root, "zetteldraw.db").getPath())
                .allowMainThreadQueries()
                .build();
        repo = new RoomBoardRepository(
                db,
                new InkFileStore(new File(root, "ink")),
                new DirectoryMirror(new File(root, "Documents/zetteldraw")),
                writer,
                Runnable::run,
                clock::get);
    }

    private boolean hasPageWithStroke(String notebookId, String pageId, String strokeId) {
        for (Board page : repo.pages(notebookId)) {
            if (pageId.equals(page.id) && page.paper != null && page.paper.stroke(strokeId) != null) {
                return true;
            }
        }
        return false;
    }

    private Board inkNearBottom(String notebookId, String strokeId) {
        Board blank = repo.createPage(notebookId);
        assertTrue(blank.isBlank());
        TestInk.appendCovering(blank.paper, nearBottom(strokeId, blank.paperOrigin), blank.id);
        repo.saveInk(blank);
        List<Board> after = repo.pages(notebookId);
        Board inked = after.get(after.size() - 1);
        assertFalse(inked.isBlank());
        assertEquals(blank.id, inked.id);
        return inked;
    }

    private static InkRenderer.InkStroke nearBottom(String id, float paperOrigin) {
        ArrayList<TouchPoint> points = new ArrayList<>();
        float y = paperOrigin + PAGE - 6f;
        for (int i = 0; i < 5; i++) {
            points.add(new TouchPoint(40f + i, y, 1.5f, 12f, 0, 0, 1_000L + i));
        }
        return InkRenderer.strokeFrom(id == null ? UUID.randomUUID().toString() : id, points);
    }
}
