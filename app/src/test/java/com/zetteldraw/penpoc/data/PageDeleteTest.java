package com.zetteldraw.penpoc.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import com.zetteldraw.penpoc.Board;
import com.zetteldraw.penpoc.data.db.BoardEntity;
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
    }

    @After
    public void tearDown() {
        a.close();
        b.close();
    }

    @Test
    public void deleteTombstonesThePageAndTheTombstoneSyncs() {
        Board first = drawnScratchpadPage(a, 10f);
        Board second = drawnScratchpadPage(a, 20f);
        relay(a, b);
        assertEquals(3, b.repo.scratchpadPages().size());

        a.tick();
        a.repo.deletePage(first.id);
        BoardEntity row = a.db.dao().board(first.id);
        assertNotNull("kept as a tombstone, not removed", row.deletedAt);
        assertNull(row.inkHash);
        assertEquals(OutboxEntry.DELETE, a.db.dao().outbox(OutboxEntry.BOARD, first.id).op);
        assertEquals(List.of(second.id), inkedIds(a.repo.scratchpadPages()));

        relay(a, b);
        assertEquals(0, a.repo.outboxSize());
        assertNotNull(b.db.dao().board(first.id).deletedAt);
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
        assertEquals(2, pages.size());
        assertTrue(pages.get(1).isBlank());
        assertNotNull(a.db.dao().board(page.id).deletedAt);

        Board trailing = pages.get(1);
        a.repo.deletePage(trailing.id);
        pages = a.repo.scratchpadPages();
        assertEquals("the trailing blank never goes away", 2, pages.size());
        assertTrue(pages.get(1).isBlank());
    }

    @Test
    public void deletingTheOnlyInkedPageLeavesOneBlankPage() {
        Board only = drawnScratchpadPage(a, 10f);
        a.repo.deletePage(only.id);
        List<Board> pages = a.repo.scratchpadPages();
        assertEquals(1, pages.size());
        assertTrue(pages.get(0).isBlank());
        assertNotEquals(only.id, pages.get(0).id);
    }

    @Test
    public void notebookPagesDeleteWithoutTouchingTheScratchpad() {
        Board page = drawnScratchpadPage(a, 10f);
        BoardRepository.NotebookInfo notebook = a.repo.createNotebook("gone");
        a.repo.movePageToNotebook(page.id, notebook.id);
        Board next = a.repo.notebookPages(notebook.id).get(1);
        TestInk.draw(next, 30f);
        a.repo.saveInk(next);
        a.repo.wipePage(page.id);
        assertEquals(3, a.repo.notebookPages(notebook.id).size());

        a.repo.deletePage(page.id);
        List<Board> pages = a.repo.notebookPages(notebook.id);
        assertEquals(RoomBoardRepositoryTest.ids(next), RoomBoardRepositoryTest.ids(pages).subList(0, 1));
        assertEquals("the notebook keeps its trailing blank", 2, pages.size());
        assertTrue(pages.get(1).isBlank());
        assertEquals(1, a.repo.scratchpadPages().size());
        assertEquals(OutboxEntry.DELETE, a.db.dao().outbox(OutboxEntry.BOARD, page.id).op);
    }

    private static Board drawnScratchpadPage(Device d, float y) {
        List<Board> pages = d.repo.scratchpadPages();
        Board page = pages.get(pages.size() - 1);
        TestInk.draw(page, y);
        d.tick();
        d.repo.saveInk(page);
        return page;
    }

    /** Push {@code from}'s outbox and pull it into {@code to}, as the server would relay it. */
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
        for (BoardEntity board : batch.boards) {
            BoardEntity copy = board.copy();
            copy.rev = ++rev;
            page.boards.add(copy);
            results.add(new SyncStore.PushResult(OutboxEntry.BOARD, board.id, SyncStore.PushResult.APPLIED, copy.rev));
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
