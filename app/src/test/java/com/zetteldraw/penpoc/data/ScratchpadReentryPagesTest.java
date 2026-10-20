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
 * Scratchpad multi-page open → other notebook → back must keep page count &gt; 1
 * and ink. Locks the v70 replay/trimTail collapse that showed permanent 1/1.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class)
public class ScratchpadReentryPagesTest {
    private static final int PAGE = 400;

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Device device;
    private RoomBoardRepository repo;

    @Before
    public void setUp() throws Exception {
        device = new Device("reentry", tmp.newFolder("reentry"), 2_000_000L);
        repo = device.repo;
    }

    @After
    public void tearDown() {
        device.close();
    }

    @Test
    public void openScratchpadMultiPageSwitchNotebookAndBackKeepsPagesAndInk() throws Exception {
        repo.ensureSheet(null, PAGE);
        if (repo.scratchpadPages().isEmpty()) {
            repo.createPage(null);
        }
        Board blank = repo.scratchpadPages().get(0);
        NotebookPaper paper = blank.paper;
        TestInk.appendCovering(paper, TestInk.stroke(10f, 20f, 8), blank.id);
        TestInk.appendCovering(paper, TestInk.stroke(10f, PAGE + 20f, 8));
        TestInk.appendCovering(paper, TestInk.stroke(10f, 2 * PAGE + 20f, 8));
        assertEquals(3, paper.sliceCount());
        // A tear in the log is what v70's empty yIndex + trimTail destroyed on replay.
        paper.tearDelete(1);
        repo.saveInk(blank);
        for (int i = 0; i < 5 && repo.pendingWrites() > 0; i++) {
            Thread.sleep(20);
        }

        List<Board> before = repo.scratchpadPages();
        assertTrue("inked pages after tear (empty trailing slices may remain)", before.size() > 1);
        int inkedBefore = inked(before);
        assertTrue(inkedBefore >= 2);

        // Cold open of the same on-disk log (no OpenSheet), then visit another notebook.
        device.reopen();
        repo = device.repo;
        repo.ensureSheet(null, PAGE);
        if (repo.scratchpadPages().isEmpty()) {
            repo.createPage(null);
        }
        List<Board> opened = repo.scratchpadPages();
        assertTrue("cold ensureSheet must not collapse to 1/1", opened.size() > 1);
        assertEquals(inkedBefore, inked(opened));
        assertFalse(opened.get(0).isBlank());

        repo.ensureSheet(Notebook.COMEDY.uuid, PAGE);
        // Bare ensureSheet = empty Ready OK (no auto blank).
        assertEquals(0, repo.pages(Notebook.COMEDY.uuid).size());

        List<Board> back = repo.scratchpadPages();
        assertTrue("return from another notebook keeps multi-page Scratchpad", back.size() > 1);
        assertEquals(inkedBefore, inked(back));
        assertFalse("ink still present on return", back.get(0).isBlank());
    }

    private static int inked(List<Board> pages) {
        int n = 0;
        for (Board page : pages) {
            if (!page.isBlank()) {
                n++;
            }
        }
        return n;
    }
}
