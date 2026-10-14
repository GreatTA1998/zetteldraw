package com.zetteldraw.penpoc.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import com.zetteldraw.penpoc.Board;
import com.zetteldraw.penpoc.Notebook;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.List;

/**
 * Ready-gate: when a notebook_log exists, {@link RoomBoardRepository#pages} must
 * not fall back to stale Room board rows (the ~50→~23 collapse).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class)
public class SheetReadyPagesTest {
    private static final int PAGE = 400;

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Device device;
    private RoomBoardRepository repo;

    @Before
    public void setUp() throws Exception {
        device = new Device("ready-gate", tmp.newFolder("ready"), 3_000_000L);
        repo = device.repo;
    }

    @After
    public void tearDown() {
        device.close();
    }

    @Test
    public void pagesWithoutReadySheetReturnsEmptyWhenLogExists() throws Exception {
        repo.ensureSheet(null, PAGE, PAGE, 0L);
        Board blank = repo.scratchpadPages().get(0);
        NotebookPaper paper = blank.paper;
        // Grow well past a typical stale Room board-row count.
        for (int i = 0; i < 40; i++) {
            paper.appendStroke(TestInk.stroke(10f, i * PAGE + 20f, 8));
        }
        assertTrue(paper.sliceCount() >= 40);
        repo.saveInk(blank);
        for (int i = 0; i < 10 && repo.pendingWrites() > 0; i++) {
            Thread.sleep(20);
        }

        List<Board> full = repo.scratchpadPages();
        int fullCount = full.size();
        assertTrue("expected many pages including trailing blank, was " + fullCount, fullCount > 30);

        // Cold process: log on disk, no OpenSheet — must not project Room rows.
        device.reopen();
        repo = device.repo;
        assertFalse(repo.isSheetReady(null));
        List<Board> beforeEnsure = repo.scratchpadPages();
        assertEquals("log present ⇒ refuse legacy BoardEntity list", 0, beforeEnsure.size());

        assertTrue(repo.ensureSheet(null, PAGE, PAGE, 0L));
        assertTrue(repo.isSheetReady(null));
        List<Board> after = repo.scratchpadPages();
        assertEquals("Ready projection restores full slice list", fullCount, after.size());
    }

    @Test
    public void pagesRefusesLegacyWhenZdlExistsEvenWithoutNotebookLogRow() throws Exception {
        repo.ensureSheet(null, PAGE, PAGE, 0L);
        Board blank = repo.scratchpadPages().get(0);
        for (int i = 0; i < 12; i++) {
            blank.paper.appendStroke(TestInk.stroke(10f, i * PAGE + 20f, 8));
        }
        repo.saveInk(blank);
        for (int i = 0; i < 10 && repo.pendingWrites() > 0; i++) {
            Thread.sleep(20);
        }
        String sheetId = NotebookPaper.sheetId(null);
        repo.invalidateOpenSheet(null);
        device.db.dao().deleteNotebookLog(sheetId);
        assertFalse(repo.isSheetReady(null));
        assertEquals("on-disk .zdl ⇒ refuse BoardEntity pages()", 0, repo.scratchpadPages().size());
    }

    @Test
    public void switchNotebookDuringReadyDoesNotTruncateScratchpad() throws Exception {
        repo.ensureSheet(null, PAGE, PAGE, 0L);
        Board blank = repo.scratchpadPages().get(0);
        NotebookPaper paper = blank.paper;
        for (int i = 0; i < 35; i++) {
            paper.appendStroke(TestInk.stroke(10f, i * PAGE + 20f, 8));
        }
        paper.tearDelete(1);
        repo.saveInk(blank);
        for (int i = 0; i < 10 && repo.pendingWrites() > 0; i++) {
            Thread.sleep(20);
        }
        int scratchpadCount = repo.scratchpadPages().size();
        assertTrue(scratchpadCount > 30);

        assertTrue(repo.ensureSheet(Notebook.COMEDY.uuid, PAGE, PAGE, 0L));
        assertTrue(repo.pages(Notebook.COMEDY.uuid).size() >= 1);

        // Forget Scratchpad sheet (simulates cold return / lost cache), ensure again.
        repo.invalidateOpenSheet(null);
        assertFalse(repo.isSheetReady(null));
        assertEquals(0, repo.scratchpadPages().size());
        assertTrue(repo.ensureSheet(null, PAGE, PAGE, 0L));
        assertEquals(scratchpadCount, repo.scratchpadPages().size());
    }
}
