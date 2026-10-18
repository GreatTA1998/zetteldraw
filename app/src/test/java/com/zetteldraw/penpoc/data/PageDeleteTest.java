package com.zetteldraw.penpoc.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import com.zetteldraw.penpoc.Board;
import com.zetteldraw.penpoc.data.db.NotebookEntity;
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
import java.util.List;

/**
 * Ready-path delete removes a paper slice and flushes the notebook log.
 * Sync of the delete is the log outbox (not a BoardEntity tombstone).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class)
public class PageDeleteTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Device a;
    private Device b;
    private long rev;

    @Before
    public void setUp() throws Exception {
        a = new Device("a", tmp.newFolder("a"), 1_800_000_000_000L);
        b = new Device("b", tmp.newFolder("b"), 1_800_000_500_000L);
        a.ensureReady();
        b.ensureReady();
    }

    @After
    public void tearDown() {
        a.close();
        b.close();
    }

    @Test
    public void deleteRemovesSliceAndLogSyncs() {
        Board first = drawnScratchpadPage(a, 10f);
        Board second = drawnScratchpadPage(a, 20f);
        relay(a, b);
        b.ensureReady();
        assertEquals(2, b.repo.scratchpadPages().size());

        a.tick();
        String firstId = first.id;
        a.repo.deletePage(firstId);
        assertTrue(a.repo.scratchpadPages().get(0).paper.indexOfSliceId(firstId) < 0);
        String sheetId = NotebookPaper.sheetId(null);
        assertNotNull(a.db.dao().outbox(OutboxEntry.LOG, sheetId));
        assertEquals(List.of(second.id), inkedIds(a.repo.scratchpadPages()));

        relay(a, b);
        b.ensureReady();
        assertEquals(0, a.repo.outboxSize());
        assertEquals(List.of(second.id), inkedIds(b.repo.scratchpadPages()));
    }

    @Test
    public void blankPagesCanBeDeletedAndTheScratchpadKeepsItsTrailingBlank() {
        Board page = drawnScratchpadPage(a, 10f);
        drawnScratchpadPage(a, 20f);
        a.repo.wipePage(page.id);
        List<Board> pages = a.repo.scratchpadPages();
        assertTrue("wiped page stays as a blank in the middle", pages.get(0).isBlank());
        assertEquals(page.id, pages.get(0).id);

        a.repo.deletePage(page.id);
        pages = a.repo.scratchpadPages();
        assertEquals(1, pages.size());
        assertFalse(pages.get(0).isBlank());
    }

    @Test
    public void deletingTheOnlyInkedPageLeavesOneBlankPage() {
        Board only = drawnScratchpadPage(a, 10f);
        assertEquals(1, a.repo.scratchpadPages().size());
        a.repo.deletePage(only.id);
        List<Board> pages = a.repo.scratchpadPages();
        assertTrue(pages.isEmpty());
    }

    @Test
    public void notebookPagesDeleteWithoutTouchingTheScratchpad() {
        Board page = drawnScratchpadPage(a, 10f);
        BoardRepository.NotebookInfo notebook = a.repo.createNotebook("gone");
        a.ensureReady(notebook.id());
        a.repo.movePageToNotebook(page.id, notebook.id());
        Board next = a.repo.createPage(notebook.id());
        TestInk.draw(next, 30f);
        a.repo.saveInk(next);
        a.repo.wipePage(page.id);
        assertEquals(2, a.repo.notebookPages(notebook.id()).size());

        a.repo.deletePage(page.id);
        List<Board> pages = a.repo.notebookPages(notebook.id());
        assertEquals(RoomBoardRepositoryTest.ids(next), RoomBoardRepositoryTest.ids(pages));
        assertTrue(a.repo.scratchpadPages().isEmpty());
        assertNotNull(a.db.dao().outbox(OutboxEntry.LOG, NotebookPaper.sheetId(notebook.id())));
    }

    private static Board drawnScratchpadPage(Device d, float y) {
        if (!d.repo.isSheetReady(null)) {
            d.ensureReady();
        }
        List<Board> pages = d.repo.scratchpadPages();
        Board page = pages.isEmpty() || !pages.get(pages.size() - 1).isBlank()
                ? d.repo.createPage(null)
                : pages.get(pages.size() - 1);
        TestInk.draw(page, y);
        d.tick();
        d.repo.saveInk(page);
        return page;
    }

    /** Push {@code from}'s outbox and pull it into {@code to}, including notebook logs. */
    private void relay(Device from, Device to) {
        SyncStore.PushBatch batch = from.repo.pendingPush(100);
        SyncStore.PullPage page = new SyncStore.PullPage();
        List<SyncStore.PushResult> results = new ArrayList<>();
        for (NotebookEntity n : batch.notebooks) {
            NotebookEntity copy = n.copy();
            copy.rev = ++rev;
            page.notebooks.add(copy);
            results.add(new SyncStore.PushResult(OutboxEntry.NOTEBOOK, n.id, SyncStore.PushResult.APPLIED, copy.rev));
        }
        for (NotebookLogEntity log : batch.logs) {
            NotebookLogEntity copy = log.copy();
            copy.rev = ++rev;
            page.logs.add(copy);
            results.add(new SyncStore.PushResult(OutboxEntry.LOG, log.id, SyncStore.PushResult.APPLIED, copy.rev));
        }
        page.blobs.putAll(batch.blobs);
        page.cursor = rev;
        try {
            to.repo.applyPull(page);
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
        from.repo.applyPushResults(batch, results);
    }

    private static List<String> inkedIds(List<Board> pages) {
        List<String> ids = new ArrayList<>();
        for (Board page : pages) {
            if (!page.isBlank()) {
                ids.add(page.id);
            }
        }
        return ids;
    }
}
