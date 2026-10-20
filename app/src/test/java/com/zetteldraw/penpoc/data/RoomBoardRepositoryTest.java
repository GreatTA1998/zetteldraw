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
import com.zetteldraw.penpoc.data.db.NotebookLogEntity;
import com.zetteldraw.penpoc.data.db.OutboxEntry;

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
            assertEquals(Notebook.values()[i].uuid, notebooks.get(i).id());
            assertEquals(Notebook.values()[i].label, notebooks.get(i).title());
        }
        assertEquals("install seeds are not queued until restore push", 0, device.db.dao().outboxCount());
        device.reopen();
        assertEquals(4, device.repo.notebooks().size());
    }

    @Test
    public void pagesEmptyUntilReady() {
        assertTrue("Ready-only: no fallthrough to BoardEntity rows", repo.scratchpadPages().isEmpty());
        assertTrue(repo.ensureSheet(null, Device.PAGE));
        assertTrue("Ready empty sheet has no soft trailing blank", repo.scratchpadPages().isEmpty());
    }

    @Test
    public void createPageAddsRealBlankSlice() {
        assertTrue(repo.ensureSheet(null, Device.PAGE));
        assertTrue(repo.scratchpadPages().isEmpty());
        Board page = repo.createPage(null);
        assertNotNull(page);
        assertTrue(page.isBlank());
        assertNotNull(page.paper);
        assertEquals(1, repo.scratchpadPages().size());
        assertSame(page, repo.scratchpadPages().get(0));
        assertNull("blank pages are not BoardEntity rows", device.db.dao().board(page.id));
    }

    @Test
    public void firstStrokeFlushesLogWithoutMintingSoftBlank() {
        assertTrue(repo.ensureSheet(null, Device.PAGE));
        Board page = repo.createPage(null);
        TestInk.draw(page, 10f);
        repo.saveInk(page);
        String sheetId = NotebookPaper.sheetId(null);
        assertTrue(device.inkDir().toPath().resolve(sheetId + ".zdl").toFile().exists());
        NotebookLogEntity log = device.db.dao().notebookLog(sheetId);
        assertNotNull(log);
        assertNotNull(log.inkHash);
        assertNotNull(device.db.dao().outbox(OutboxEntry.LOG, sheetId));
        assertEquals(1, repo.scratchpadPages().size());
        assertSame(page, repo.scratchpadPages().get(0));
        assertFalse(page.isBlank());
    }

    @Test
    public void createPageThenInkKeepsSameSliceId() {
        assertTrue(repo.ensureSheet(null, Device.PAGE));
        Board blank = repo.createPage(null);
        assertTrue(blank.isBlank());
        String id = blank.id;
        TestInk.draw(blank, 10f);
        repo.saveInk(blank);

        List<Board> pages = repo.scratchpadPages();
        assertEquals(1, pages.size());
        assertEquals(id, pages.get(0).id);
        assertSame(blank, pages.get(0));
        assertFalse(pages.get(0).isBlank());
    }

    @Test
    public void saveWithoutChangesDoesNotRequeueLog() {
        device.ensureReady();
        Board page = repo.createPage(null);
        TestInk.draw(page, 10f);
        repo.saveInk(page);
        String sheetId = NotebookPaper.sheetId(null);
        long queued = device.db.dao().outbox(OutboxEntry.LOG, sheetId).queuedAt;
        String hash = device.db.dao().notebookLog(sheetId).inkHash;
        device.tick();
        repo.saveInk(page);
        assertEquals(queued, device.db.dao().outbox(OutboxEntry.LOG, sheetId).queuedAt);
        assertEquals(hash, device.db.dao().notebookLog(sheetId).inkHash);
    }

    @Test
    public void lassoTranslatePersistsOnLogAndSurvivesReopen() {
        device.ensureReady();
        Board page = repo.createPage(null);
        TestInk.draw(page, 10f);
        TestInk.draw(page, 200f);
        repo.saveInk(page);
        String sheetId = NotebookPaper.sheetId(null);
        String hashBefore = device.db.dao().notebookLog(sheetId).inkHash;
        String movedId = TestInk.strokesOf(page).get(0).id;
        String keptId = TestInk.strokesOf(page).get(1).id;
        device.db.dao().deleteOutbox(OutboxEntry.LOG, sheetId);

        device.tick();
        page.paper.translate(List.of(movedId), 25f, 40f);
        repo.saveInk(page);

        String hashAfter = device.db.dao().notebookLog(sheetId).inkHash;
        assertFalse(hashBefore.equals(hashAfter));
        assertNotNull("queued for sync", device.db.dao().outbox(OutboxEntry.LOG, sheetId));

        device.reopen();
        assertTrue(device.ensureReady());
        Board reloaded = device.repo.scratchpadPages().get(0);
        assertEquals(page.id, reloaded.id);
        assertEquals(movedId, TestInk.strokesOf(reloaded).get(0).id);
        assertEquals(keptId, TestInk.strokesOf(reloaded).get(1).id);
        assertEquals(10f + 25f, TestInk.strokesOf(reloaded).get(0).points.get(0).x, 0f);
        assertEquals(50f + 40f, TestInk.strokesOf(reloaded).get(0).points.get(0).y, 0f);
        assertEquals(200f, TestInk.strokesOf(reloaded).get(1).points.get(0).x, 0f);
    }

    @Test
    public void moveAppendsAsNewestInNotebook() {
        device.ensureReady();
        device.ensureReady(Notebook.COMEDY.uuid);
        device.ensureReady(Notebook.JOURNAL.uuid);
        Board a = inkedScratchpadPage(10f);
        Board b = inkedScratchpadPage(20f);
        Board c = inkedScratchpadPage(30f);
        String comedy = Notebook.COMEDY.uuid;

        repo.movePageToNotebook(b.id, comedy);
        repo.movePageToNotebook(a.id, comedy);
        assertEquals(ids(b, a), ids(repo.notebookPages(comedy)));
        List<Board> scratch = repo.scratchpadPages();
        assertEquals(1, scratch.size());
        assertEquals(c.id, scratch.get(0).id);

        repo.movePageToNotebook(b.id, comedy);
        assertEquals("moving again makes it newest", ids(a, b), ids(repo.notebookPages(comedy)));

        repo.movePageToNotebook(c.id, Notebook.JOURNAL.uuid);
        assertEquals(ids(c), ids(repo.notebookPages(Notebook.JOURNAL.uuid)));
        assertTrue(repo.scratchpadPages().isEmpty());
    }

    @Test
    public void wipeClearsInkAndTrimTailDropsEmptyTrailingSlices() {
        device.ensureReady();
        Board a = inkedScratchpadPage(10f);
        Board b = inkedScratchpadPage(20f);
        assertEquals(2, repo.scratchpadPages().size());

        repo.wipePage(a.id);
        assertTrue(a.isBlank());
        assertEquals("a wiped middle page stays", 2, repo.scratchpadPages().size());
        assertTrue(device.inkDir().toPath().resolve(NotebookPaper.sheetId(null) + ".zdl").toFile().exists());

        repo.wipePage(b.id);
        List<Board> pages = repo.scratchpadPages();
        // trimTail drops empty trailing slices; middle blank may remain or all go.
        assertTrue("only blank content remains", pages.stream().allMatch(Board::isBlank));
    }

    @Test
    public void notebookCreatePageThenInk() {
        BoardRepository.NotebookInfo notebook = repo.createNotebook("grows");
        assertTrue("pages() empty until ensure", repo.pages(notebook.id()).isEmpty());
        assertTrue(repo.ensureSheet(notebook.id(), Device.PAGE));
        assertTrue(repo.pages(notebook.id()).isEmpty());
        Board first = repo.createPage(notebook.id());
        assertNotNull(first);
        assertTrue(first.isBlank());

        TestInk.draw(first, 10f);
        device.tick();
        repo.saveInk(first);
        String sheetId = NotebookPaper.sheetId(notebook.id());
        assertNotNull(device.db.dao().notebookLog(sheetId));
        assertEquals(OutboxEntry.UPSERT, device.db.dao().outbox(OutboxEntry.LOG, sheetId).op);
        List<Board> pages = repo.pages(notebook.id());
        assertEquals(1, pages.size());
        assertSame(first, pages.get(0));
        assertTrue(repo.ensureSheet(null, Device.PAGE));
        assertTrue("the Scratchpad is untouched", repo.scratchpadPages().isEmpty());

        Board second = repo.createPage(notebook.id());
        TestInk.draw(second, 20f);
        device.tick();
        repo.saveInk(second);
        assertEquals(ids(first, second), ids(repo.pages(notebook.id())));
        assertEquals(2, repo.pages(notebook.id()).size());

        device.reopen();
        assertTrue(device.ensureReady(notebook.id()));
        List<Board> reopened = device.repo.pages(notebook.id());
        assertEquals(ids(first, second), ids(reopened));
    }

    @Test
    public void deletedOrUnknownNotebookHasNoPages() {
        BoardRepository.NotebookInfo notebook = repo.createNotebook("brief");
        assertTrue(repo.ensureSheet(notebook.id(), Device.PAGE));
        assertTrue(repo.pages(notebook.id()).isEmpty());
        repo.deleteNotebook(notebook.id());
        assertTrue(repo.pages(notebook.id()).isEmpty());
        assertTrue(repo.pages("no-such-notebook").isEmpty());
    }

    @Test
    public void wipeInsideNotebookClearsInk() {
        device.ensureReady();
        device.ensureReady(Notebook.COMEDY.uuid);
        Board a = inkedScratchpadPage(10f);
        repo.movePageToNotebook(a.id, Notebook.COMEDY.uuid);
        assertEquals(ids(a), ids(repo.notebookPages(Notebook.COMEDY.uuid)));
        repo.wipePage(a.id);
        // Ready paper trimTail drops a wiped last empty slice.
        List<Board> comedy = repo.notebookPages(Notebook.COMEDY.uuid);
        assertTrue(comedy.isEmpty() || comedy.stream().allMatch(Board::isBlank));
    }

    @Test
    public void reopenReloadsInkAndOrderFromDisk() {
        device.ensureReady();
        device.ensureReady(Notebook.ACTIONS_LIFE.uuid);
        Board a = inkedScratchpadPage(10f);
        List<com.zetteldraw.penpoc.InkRenderer.InkStroke> aInk =
                new ArrayList<>(TestInk.strokesOf(a));
        Board b = inkedScratchpadPage(20f);
        List<com.zetteldraw.penpoc.InkRenderer.InkStroke> bInk =
                new ArrayList<>(TestInk.strokesOf(b));
        Board c = inkedScratchpadPage(30f);
        repo.movePageToNotebook(c.id, Notebook.ACTIONS_LIFE.uuid);
        // Capture after Move — paper remaps Y onto the dest sheet origin.
        List<com.zetteldraw.penpoc.InkRenderer.InkStroke> cInk =
                new ArrayList<>(TestInk.strokesOf(repo.notebookPages(Notebook.ACTIONS_LIFE.uuid).get(0)));

        device.reopen();
        assertTrue(device.ensureReady());
        assertTrue(device.ensureReady(Notebook.ACTIONS_LIFE.uuid));
        RoomBoardRepository reopened = device.repo;
        List<Board> after = reopened.scratchpadPages();
        assertEquals(ids(a, b), ids(after).subList(0, 2));
        TestInk.assertSameInk(aInk, TestInk.strokesOf(after.get(0)));
        TestInk.assertSameInk(bInk, TestInk.strokesOf(after.get(1)));
        assertEquals(ids(c), ids(reopened.notebookPages(Notebook.ACTIONS_LIFE.uuid)));
        TestInk.assertSameInk(cInk,
                TestInk.strokesOf(reopened.notebookPages(Notebook.ACTIONS_LIFE.uuid).get(0)));
    }

    @Test
    public void mirrorsLogToDocuments() throws Exception {
        device.ensureReady();
        device.ensureReady(Notebook.JOURNAL.uuid);
        Board a = inkedScratchpadPage(10f);
        repo.movePageToNotebook(a.id, Notebook.JOURNAL.uuid);
        String sheetId = NotebookPaper.sheetId(Notebook.JOURNAL.uuid);
        File logCopy = new File(device.mirrorDir(), "ink/" + sheetId + ".zdl");
        assertTrue(logCopy.exists());
        assertTrue(logCopy.length() > 0);
    }

    private Board inkedScratchpadPage(float x) {
        if (!repo.isSheetReady(null)) {
            device.ensureReady();
        }
        List<Board> pages = repo.scratchpadPages();
        Board page = pages.isEmpty() || !pages.get(pages.size() - 1).isBlank()
                ? repo.createPage(null)
                : pages.get(pages.size() - 1);
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

    static List<String> ids(List<Board> boards) {
        return ids(boards.toArray(new Board[0]));
    }
}
