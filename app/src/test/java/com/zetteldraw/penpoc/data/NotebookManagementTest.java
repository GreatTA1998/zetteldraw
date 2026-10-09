package com.zetteldraw.penpoc.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import com.zetteldraw.penpoc.Board;
import com.zetteldraw.penpoc.Notebook;
import com.zetteldraw.penpoc.data.db.NotebookEntity;
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
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class)
public class NotebookManagementTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Device device;
    private RoomBoardRepository repo;

    @Before
    public void setUp() throws Exception {
        device = new Device("a", tmp.newFolder("a"), 20_000_000L);
        repo = device.repo;
    }

    @After
    public void tearDown() {
        device.close();
    }

    @Test
    public void createAppendsAfterExistingNotebooks() {
        BoardRepository.NotebookInfo ideas = repo.createNotebook("  ideas   for later ");
        assertEquals("ideas for later", ideas.title);
        BoardRepository.NotebookInfo last = repo.createNotebook("zzz");
        List<BoardRepository.NotebookInfo> all = repo.notebooks();
        assertEquals(6, all.size());
        assertEquals(ideas.id, all.get(4).id);
        assertEquals(last.id, all.get(5).id);
        NotebookEntity row = device.db.dao().notebook(ideas.id);
        assertTrue(row.position.compareTo(device.db.dao().notebook(Notebook.MISCELLANEOUS.uuid).position) > 0);
        assertEquals(0, row.rev);
        assertNotNull(device.db.dao().outbox(OutboxEntry.NOTEBOOK, ideas.id));
        assertNull("blank titles are rejected", repo.createNotebook("   "));
    }

    @Test
    public void renameBumpsUpdatedAtAndRequeues() {
        SyncStore.PushBatch batch = repo.pendingPush(50);
        List<SyncStore.PushResult> results = new ArrayList<>();
        for (NotebookEntity n : batch.notebooks) {
            results.add(new SyncStore.PushResult(OutboxEntry.NOTEBOOK, n.id, SyncStore.PushResult.APPLIED, 4));
        }
        repo.applyPushResults(batch, results);
        assertEquals(0, repo.outboxSize());

        long now = device.tick();
        repo.renameNotebook(Notebook.COMEDY.uuid, "stand-up");
        NotebookEntity row = device.db.dao().notebook(Notebook.COMEDY.uuid);
        assertEquals("stand-up", row.title);
        assertEquals(now, row.updatedAt);
        assertEquals("base rev stays the last synced rev", 4, row.rev);
        assertEquals(OutboxEntry.UPSERT, device.db.dao().outbox(OutboxEntry.NOTEBOOK, row.id).op);
        assertEquals("stand-up", repo.notebooks().get(0).title);
    }

    @Test
    public void deleteTombstonesAndReturnsPagesToScratchpadInOrder() {
        Board keep = inked(5f);
        Board a = inked(10f);
        Board b = inked(20f);
        repo.movePageToNotebook(a.id, Notebook.JOURNAL.uuid);
        repo.movePageToNotebook(b.id, Notebook.JOURNAL.uuid);

        device.tick();
        repo.deleteNotebook(Notebook.JOURNAL.uuid);

        NotebookEntity row = device.db.dao().notebook(Notebook.JOURNAL.uuid);
        assertNotNull(row.deletedAt);
        assertEquals(OutboxEntry.DELETE, device.db.dao().outbox(OutboxEntry.NOTEBOOK, row.id).op);
        assertEquals(3, repo.notebooks().size());
        assertTrue(repo.notebookPages(Notebook.JOURNAL.uuid).isEmpty());

        List<Board> scratch = repo.scratchpadPages();
        assertEquals(RoomBoardRepositoryTest.ids(keep, a, b), RoomBoardRepositoryTest.ids(scratch).subList(0, 3));
        assertTrue(scratch.get(scratch.size() - 1).isBlank());
        assertNotNull(device.db.dao().outbox(OutboxEntry.BOARD, a.id));
        assertEquals(1, a.strokes.size());

        device.reopen();
        assertEquals("seed is not re-created after delete", 3, device.repo.notebooks().size());
    }

    @Test
    public void moveIntoNewNotebookAndNotIntoDeletedOne() {
        Board a = inked(10f);
        BoardRepository.NotebookInfo fresh = repo.createNotebook("fresh");
        repo.movePageToNotebook(a.id, fresh.id);
        assertEquals(RoomBoardRepositoryTest.ids(a), RoomBoardRepositoryTest.ids(RoomBoardRepositoryTest.stored(repo.notebookPages(fresh.id))));

        repo.deleteNotebook(fresh.id);
        Board b = inked(20f);
        repo.movePageToNotebook(b.id, fresh.id);
        assertTrue(repo.notebookPages(fresh.id).isEmpty());
        assertTrue(RoomBoardRepositoryTest.ids(repo.scratchpadPages()).contains(b.id));
    }

    @Test
    public void seedsLoseToAnyRealEditFromAnotherDevice() throws Exception {
        NotebookEntity seed = device.db.dao().notebook(Notebook.COMEDY.uuid);
        assertEquals(0, seed.updatedAt);
        NotebookEntity remote = seed.copy();
        remote.title = "renamed elsewhere";
        remote.updatedAt = 1;
        remote.rev = 3;
        SyncStore.PullPage page = new SyncStore.PullPage();
        page.notebooks.add(remote);
        page.cursor = 3;
        repo.applyPull(page);
        assertEquals("renamed elsewhere", device.db.dao().notebook(seed.id).title);
    }

    @Test
    public void pulledDeleteSendsLocallyFiledPagesToScratchpad() throws Exception {
        Board a = inked(10f);
        repo.movePageToNotebook(a.id, Notebook.ACTIONS_LIFE.uuid);
        NotebookEntity remote = device.db.dao().notebook(Notebook.ACTIONS_LIFE.uuid).copy();
        remote.deletedAt = device.tick();
        remote.updatedAt = remote.deletedAt;
        remote.rev = 8;
        SyncStore.PullPage page = new SyncStore.PullPage();
        page.notebooks.add(remote);
        page.cursor = 8;
        repo.applyPull(page);
        assertEquals(3, repo.notebooks().size());
        assertTrue(RoomBoardRepositoryTest.ids(repo.scratchpadPages()).contains(a.id));
        assertNotNull(device.db.dao().outbox(OutboxEntry.BOARD, a.id));
    }

    @Test
    public void parentPagesExcludeTheChildsInk() {
        BoardRepository.NotebookInfo shelf = repo.createNotebook("shelf");
        BoardRepository.NotebookInfo chapter = repo.createNotebook("chapter", shelf.id);
        assertEquals(shelf.id, chapter.parentId);
        repo.ensureSheet(shelf.id, 800, 800, 0L);
        repo.ensureSheet(chapter.id, 800, 800, 0L);
        Board parentPage = repo.pages(shelf.id).get(0);
        Board childPage = repo.pages(chapter.id).get(0);
        assertNotEquals(parentPage.sheetId, childPage.sheetId);
        parentPage.paper.appendStroke(TestInk.stroke(11f, 20f, 4));
        childPage.paper.appendStroke(TestInk.stroke(99f, 20f, 4));
        repo.saveInk(parentPage);
        repo.saveInk(childPage);

        for (Board page : repo.pages(shelf.id)) {
            if (page.paper == null) {
                continue;
            }
            for (com.zetteldraw.penpoc.InkRenderer.InkStroke stroke : page.paper.strokes()) {
                assertTrue(stroke.points.get(0).x < 50f);
            }
        }
        assertEquals(99f, repo.pages(chapter.id).get(0).paper.strokes().get(0).points.get(0).x, 0.01f);
        assertEquals(2, repo.pages(shelf.id).size());
    }

    @Test
    public void placeRejectsSelfParentAndDescendants() {
        BoardRepository.NotebookInfo shelf = repo.createNotebook("shelf");
        BoardRepository.NotebookInfo chapter = repo.createNotebook("chapter", shelf.id);
        BoardRepository.NotebookInfo scene = repo.createNotebook("scene", chapter.id);
        assertFalse(repo.placeNotebook(shelf.id, shelf.id));
        assertFalse(repo.placeNotebook(shelf.id, chapter.id));
        assertFalse(repo.placeNotebook(shelf.id, scene.id));
        assertFalse(repo.placeNotebook(chapter.id, "not-a-notebook"));
        assertEquals(shelf.id, device.db.dao().notebook(chapter.id).parentId);
        assertTrue(repo.placeNotebook(chapter.id, null));
        assertNull(device.db.dao().notebook(chapter.id).parentId);
        assertEquals(chapter.id, device.db.dao().notebook(scene.id).parentId);
        assertTrue("already top-level is a no-op", repo.placeNotebook(chapter.id, null));
    }

    @Test
    public void deletePromotesChildrenAndKeepsTheirPages() {
        BoardRepository.NotebookInfo shelf = repo.createNotebook("shelf");
        BoardRepository.NotebookInfo chapter = repo.createNotebook("chapter", shelf.id);
        Board childPage = inked(30f);
        repo.movePageToNotebook(childPage.id, chapter.id);
        Board parentPage = inked(40f);
        repo.movePageToNotebook(parentPage.id, shelf.id);
        String childPos = device.db.dao().notebook(chapter.id).position;

        repo.deleteNotebook(shelf.id);

        NotebookEntity child = device.db.dao().notebook(chapter.id);
        assertNull(child.deletedAt);
        assertNull(child.parentId);
        assertEquals(childPos, child.position);
        assertTrue(RoomBoardRepositoryTest.ids(repo.notebookPages(chapter.id)).contains(childPage.id));
        assertTrue(RoomBoardRepositoryTest.ids(repo.scratchpadPages()).contains(parentPage.id));
        assertNotNull(device.db.dao().outbox(OutboxEntry.NOTEBOOK, chapter.id));
    }

    @Test
    public void pulledTombstonePromotesLocalChildren() throws Exception {
        BoardRepository.NotebookInfo shelf = repo.createNotebook("shelf");
        BoardRepository.NotebookInfo chapter = repo.createNotebook("chapter", shelf.id);
        NotebookEntity remote = device.db.dao().notebook(shelf.id).copy();
        remote.deletedAt = device.tick();
        remote.updatedAt = remote.deletedAt;
        remote.rev = 8;
        SyncStore.PullPage page = new SyncStore.PullPage();
        page.notebooks.add(remote);
        page.cursor = 8;
        repo.applyPull(page);
        assertNull(device.db.dao().notebook(chapter.id).parentId);
        assertNotNull(device.db.dao().outbox(OutboxEntry.NOTEBOOK, chapter.id));
        assertEquals(OutboxEntry.UPSERT, device.db.dao().outbox(OutboxEntry.NOTEBOOK, chapter.id).op);
    }

    private Board inked(float x) {
        List<Board> pages = repo.scratchpadPages();
        Board page = pages.get(pages.size() - 1);
        TestInk.draw(page, x);
        device.tick();
        repo.saveInk(page);
        return page;
    }
}
