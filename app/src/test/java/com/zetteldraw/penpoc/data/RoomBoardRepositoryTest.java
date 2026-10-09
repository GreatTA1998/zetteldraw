package com.zetteldraw.penpoc.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import com.zetteldraw.penpoc.Board;
import com.zetteldraw.penpoc.Notebook;
import com.zetteldraw.penpoc.data.db.BoardEntity;
import com.zetteldraw.penpoc.data.db.OutboxEntry;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class)
public class RoomBoardRepositoryTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Device device;
    private RoomBoardRepository repo;

    @Before
    public void setUp() throws Exception {
        device = new Device("a", tmp.newFolder("a"), 1_000_000L);
        repo = device.repo;
    }

    @After
    public void tearDown() {
        device.close();
    }

    @Test
    public void seedsNotebooksInOrderWithStableIds() {
        List<BoardRepository.NotebookInfo> notebooks = repo.notebooks();
        assertEquals(Notebook.values().length, notebooks.size());
        for (int i = 0; i < notebooks.size(); i++) {
            assertEquals(Notebook.values()[i].uuid, notebooks.get(i).id);
            assertEquals(Notebook.values()[i].label, notebooks.get(i).title);
        }
        assertEquals(4, device.db.dao().outboxCount());
        device.reopen();
        assertEquals(4, device.repo.notebooks().size());
    }

    @Test
    public void scratchpadStartsWithOneUnsavedBlankPage() {
        List<Board> pages = repo.scratchpadPages();
        assertEquals(1, pages.size());
        assertTrue(pages.get(0).isBlank());
        assertNull(device.db.dao().board(pages.get(0).id));
        repo.saveInk(pages.get(0));
        assertNull("blank pages are not stored", device.db.dao().board(pages.get(0).id));
    }

    @Test
    public void firstStrokeSavesPageAndNextBlankAppearsOnlyWhenAsked() {
        Board page = repo.scratchpadPages().get(0);
        TestInk.draw(page, 10f);
        repo.saveInk(page);
        BoardEntity row = device.db.dao().board(page.id);
        assertNotNull(row);
        assertNull(row.notebookId);
        assertNotNull(row.inkHash);
        assertTrue(device.inkDir().toPath().resolve(page.id + ".zdi").toFile().exists());
        // The first stroke stores the trailing blank and a new blank follows it.
        assertEquals(2, repo.scratchpadPages().size());
        Board blank = repo.scratchpadPages().get(1);
        assertTrue(blank.isBlank());
        assertSame("the same blank until it gets ink", blank, repo.scratchpadPages().get(1));
        assertSame("same id, same object", page, repo.scratchpadPages().get(0));
    }

    @Test
    public void sheetFirstStrokeAdoptsBlankIdAndMintsAFreshTrailingBlank() {
        repo.ensureSheet(null, 800, 800, 0L);
        Board blank = repo.scratchpadPages().get(0);
        assertTrue(blank.isBlank());
        assertNotNull(blank.paper);
        String adopted = blank.id;
        blank.paper.appendStroke(TestInk.stroke(10f, 20f, 8), adopted);
        repo.saveInk(blank);

        List<Board> pages = repo.scratchpadPages();
        assertEquals(2, pages.size());
        assertEquals(adopted, pages.get(0).id);
        assertSame("the adopted blank stays as slice 0", blank, pages.get(0));
        assertFalse(pages.get(0).isBlank());
        assertTrue(pages.get(1).isBlank());
        assertFalse("a new blank id follows; the adopted one must not appear twice",
                adopted.equals(pages.get(1).id));
        assertSame(pages.get(1), repo.scratchpadPages().get(1));
    }

    @Test
    public void saveWithoutChangesDoesNotRequeue() {
        Board page = repo.scratchpadPages().get(0);
        TestInk.draw(page, 10f);
        repo.saveInk(page);
        long queued = device.db.dao().outbox(OutboxEntry.BOARD, page.id).queuedAt;
        long updated = device.db.dao().board(page.id).updatedAt;
        device.tick();
        repo.saveInk(page);
        assertEquals(queued, device.db.dao().outbox(OutboxEntry.BOARD, page.id).queuedAt);
        assertEquals(updated, device.db.dao().board(page.id).updatedAt);
    }

    @Test
    public void lassoMoveSavesLikeAPenUpAndKeepsStrokeIds() {
        Board page = repo.scratchpadPages().get(0);
        TestInk.draw(page, 10f);
        TestInk.draw(page, 200f);
        repo.saveInk(page);
        BoardEntity before = device.db.dao().board(page.id);
        String hashBefore = before.inkHash;
        long updatedBefore = before.updatedAt;
        String movedId = page.strokes.get(0).id;
        String keptId = page.strokes.get(1).id;
        device.db.dao().deleteOutbox(OutboxEntry.BOARD, page.id);

        device.tick();
        page.strokes.set(0, page.strokes.get(0).translated(25f, 40f));
        repo.saveInk(page);

        BoardEntity after = device.db.dao().board(page.id);
        assertFalse(hashBefore.equals(after.inkHash));
        assertTrue(after.updatedAt > updatedBefore);
        assertNotNull("queued for sync", device.db.dao().outbox(OutboxEntry.BOARD, page.id));

        device.reopen();
        Board reloaded = device.repo.scratchpadPages().get(0);
        assertEquals(page.id, reloaded.id);
        assertEquals(movedId, reloaded.strokes.get(0).id);
        assertEquals(keptId, reloaded.strokes.get(1).id);
        assertEquals(10f + 25f, reloaded.strokes.get(0).points.get(0).x, 0f);
        assertEquals(50f + 40f, reloaded.strokes.get(0).points.get(0).y, 0f);
        assertEquals(200f, reloaded.strokes.get(1).points.get(0).x, 0f);
    }

    @Test
    public void moveAppendsAsNewestInNotebook() {
        Board a = inkedScratchpadPage(10f);
        Board b = inkedScratchpadPage(20f);
        Board c = inkedScratchpadPage(30f);
        String comedy = Notebook.COMEDY.uuid;

        repo.movePageToNotebook(b.id, comedy);
        repo.movePageToNotebook(a.id, comedy);
        assertEquals(ids(b, a), ids(stored(repo.notebookPages(comedy))));
        List<Board> scratch = repo.scratchpadPages();
        assertEquals(c.id, scratch.get(0).id);
        assertTrue(scratch.get(scratch.size() - 1).isBlank());

        repo.movePageToNotebook(b.id, comedy);
        assertEquals("moving again makes it newest", ids(a, b), ids(stored(repo.notebookPages(comedy))));

        repo.movePageToNotebook(c.id, Notebook.JOURNAL.uuid);
        assertEquals(ids(c), ids(stored(repo.notebookPages(Notebook.JOURNAL.uuid))));
        assertEquals(1, repo.scratchpadPages().size());
        assertTrue(repo.scratchpadPages().get(0).isBlank());
    }

    @Test
    public void wipeClearsOnePageAndKeepsOneTrailingBlank() {
        Board a = inkedScratchpadPage(10f);
        Board b = inkedScratchpadPage(20f);
        assertEquals(3, repo.scratchpadPages().size());

        repo.wipePage(a.id);
        assertTrue(a.isBlank());
        BoardEntity row = device.db.dao().board(a.id);
        assertNull(row.inkHash);
        assertEquals(0, row.inkBytes);
        assertFalse(device.inkDir().toPath().resolve(a.id + ".zdi").toFile().exists());
        assertEquals("a wiped middle page stays", 3, repo.scratchpadPages().size());

        // Same as BoardStore: blank pages at the end collapse into one.
        repo.wipePage(b.id);
        List<Board> pages = repo.scratchpadPages();
        assertEquals(ids(a), ids(pages));
        assertTrue(pages.get(0).isBlank());
        BoardEntity tombstone = device.db.dao().board(b.id);
        assertNotNull(tombstone.deletedAt);
        assertEquals(OutboxEntry.DELETE, device.db.dao().outbox(OutboxEntry.BOARD, b.id).op);
    }

    @Test
    public void notebookGrowsAPageAfterDrawingOnItsLastBlank() {
        BoardRepository.NotebookInfo notebook = repo.createNotebook("grows");
        List<Board> pages = repo.pages(notebook.id);
        assertEquals("an empty notebook shows one blank page", 1, pages.size());
        Board first = pages.get(0);
        assertTrue(first.isBlank());
        assertNull("not stored before its first stroke", device.db.dao().board(first.id));

        TestInk.draw(first, 10f);
        device.tick();
        repo.saveInk(first);
        BoardEntity row = device.db.dao().board(first.id);
        assertEquals(notebook.id, row.notebookId);
        assertEquals(OutboxEntry.UPSERT, device.db.dao().outbox(OutboxEntry.BOARD, first.id).op);
        pages = repo.pages(notebook.id);
        assertEquals(2, pages.size());
        assertSame(first, pages.get(0));
        assertTrue(pages.get(1).isBlank());
        assertEquals("the Scratchpad is untouched", 1, repo.scratchpadPages().size());

        Board second = pages.get(1);
        TestInk.draw(second, 20f);
        device.tick();
        repo.saveInk(second);
        assertTrue("next fractional position in that notebook",
                device.db.dao().board(second.id).position.compareTo(row.position) > 0);
        assertEquals(ids(first, second), ids(stored(repo.pages(notebook.id))));
        assertEquals(3, repo.pages(notebook.id).size());

        device.reopen();
        List<Board> reopened = device.repo.pages(notebook.id);
        assertEquals(ids(first, second), ids(stored(reopened)));
        assertTrue(reopened.get(2).isBlank());
    }

    @Test
    public void deletedOrUnknownNotebookHasNoPages() {
        BoardRepository.NotebookInfo notebook = repo.createNotebook("brief");
        assertEquals(1, repo.pages(notebook.id).size());
        repo.deleteNotebook(notebook.id);
        assertTrue(repo.pages(notebook.id).isEmpty());
        assertTrue(repo.pages("no-such-notebook").isEmpty());
    }

    @Test
    public void wipeInsideNotebookKeepsThePage() {
        Board a = inkedScratchpadPage(10f);
        repo.movePageToNotebook(a.id, Notebook.COMEDY.uuid);
        repo.wipePage(a.id);
        assertEquals(ids(a), ids(repo.notebookPages(Notebook.COMEDY.uuid)));
        assertTrue(repo.notebookPages(Notebook.COMEDY.uuid).get(0).isBlank());
    }

    @Test
    public void reopenReloadsInkAndOrderFromDisk() {
        Board a = inkedScratchpadPage(10f);
        Board b = inkedScratchpadPage(20f);
        Board c = inkedScratchpadPage(30f);
        repo.movePageToNotebook(c.id, Notebook.ACTIONS_LIFE.uuid);
        List<Board> before = repo.scratchpadPages();

        device.reopen();
        RoomBoardRepository reopened = device.repo;
        List<Board> after = reopened.scratchpadPages();
        assertEquals(ids(a, b), ids(after).subList(0, 2));
        TestInk.assertSameInk(before.get(0).strokes, after.get(0).strokes);
        TestInk.assertSameInk(before.get(1).strokes, after.get(1).strokes);
        assertEquals(ids(c), ids(stored(reopened.notebookPages(Notebook.ACTIONS_LIFE.uuid))));
        TestInk.assertSameInk(c.strokes, reopened.notebookPages(Notebook.ACTIONS_LIFE.uuid).get(0).strokes);
    }

    @Test
    public void mirrorsInkAndIndexToDocuments() throws Exception {
        Board a = inkedScratchpadPage(10f);
        repo.movePageToNotebook(a.id, Notebook.JOURNAL.uuid);
        File inkCopy = new File(device.mirrorDir(), "ink/" + a.id + ".zdi");
        assertTrue(inkCopy.exists());
        TestInk.assertSameInk(a.strokes, InkCodec.decode(Files.readAllBytes(inkCopy.toPath())));
        JSONObject index = new JSONObject(new String(
                Files.readAllBytes(new File(device.mirrorDir(), "index.json").toPath()), StandardCharsets.UTF_8));
        assertEquals(4, index.getJSONArray("notebooks").length());
        JSONArray boards = index.getJSONArray("boards");
        assertEquals(1, boards.length());
        assertEquals(Notebook.JOURNAL.uuid, boards.getJSONObject(0).getString("notebook_id"));
        assertEquals("ink/" + a.id + ".zdi", boards.getJSONObject(0).getString("file"));

        repo.wipePage(a.id);
        assertFalse(inkCopy.exists());
    }

    private Board inkedScratchpadPage(float x) {
        List<Board> pages = repo.scratchpadPages();
        Board page = pages.get(pages.size() - 1);
        TestInk.draw(page, x);
        device.tick();
        repo.saveInk(page);
        return page;
    }

    static List<String> ids(Board... boards) {
        List<String> ids = new ArrayList<>();
        for (Board b : boards) {
            ids.add(b.id);
        }
        return ids;
    }

    /** The list without its unsaved trailing blank page. */
    static List<Board> stored(List<Board> pages) {
        List<Board> copy = new ArrayList<>(pages);
        if (!copy.isEmpty() && copy.get(copy.size() - 1).isBlank()) {
            copy.remove(copy.size() - 1);
        }
        return copy;
    }

    static List<String> ids(List<Board> boards) {
        return ids(boards.toArray(new Board[0]));
    }
}
