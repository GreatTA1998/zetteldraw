package com.zetteldraw.penpoc.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import com.onyx.android.sdk.data.note.TouchPoint;
import com.zetteldraw.penpoc.Board;
import com.zetteldraw.penpoc.InkRenderer;

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
import java.util.UUID;

/**
 * One-sheet Move must tear the inked slice out of Scratchpad and append it as
 * the destination's newest inked page, before that notebook's trailing blank.
 * Dense pages used to vanish: tear removed the source, then per-stroke dest
 * appends copied the whole log each time and OOM'd / stalled mid-move.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class)
public class MovePageTearTest {
    private static final int PAGE = 800;
    /** Enough strokes that the old per-append log copy is quadratic and fatal. */
    private static final int DENSE_STROKES = 800;
    private static final int POINTS_EACH = 40;

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Device device;
    private RoomBoardRepository repo;
    private String doodles;

    @Before
    public void setUp() throws Exception {
        device = new Device("a", tmp.newFolder("a"), 2_000_000L);
        repo = device.repo;
        doodles = repo.createNotebook("doodles").id();
        repo.ensureSheet(null, PAGE, PAGE, 0L);
        repo.ensureSheet(doodles, PAGE, PAGE, 0L);
    }

    @After
    public void tearDown() {
        device.close();
    }

    @Test
    public void movingLastInkedScratchpadPageAppearsAsNewestInDoodles() {
        Board inked = inkNearBottom(null, "mark");
        assertEquals(0, inked.paper.lastInkedSlice());

        String pageId = inked.id;
        assertTrue(repo.movePageToNotebook(pageId, doodles));

        assertTrue(repo.scratchpadPages().isEmpty());

        List<Board> pages = repo.pages(doodles);
        assertEquals(1, pages.size());
        assertFalse(pages.get(0).isBlank());
        assertEquals("placePageBefore spine keeps slice id (links stay valid)",
                pageId, pages.get(0).id);
        assertNotNull(pages.get(0).paper.stroke("mark"));
        assertEquals("open lands on the moved page", 0, pages.get(0).paper.lastInkedSlice());
        assertEquals(0, openScrollIndex(pages));
    }

    @Test
    public void movingSecondLastWithTrailingBlankLeavesEarlierInkAndShowsMovedPageInDoodles() {
        Board first = inkNearBottom(null, "keep");
        Board second = inkNearBottom(null, "move");
        List<Board> before = repo.scratchpadPages();
        assertEquals(2, before.size());
        assertEquals(first.id, before.get(0).id);
        assertEquals(second.id, before.get(1).id);
        assertEquals(1, second.paper.lastInkedSlice());

        repo.movePageToNotebook(second.id, doodles);

        List<Board> scratch = repo.scratchpadPages();
        assertEquals(1, scratch.size());
        assertEquals(first.id, scratch.get(0).id);
        assertFalse(scratch.get(0).isBlank());
        assertNotNull(scratch.get(0).paper.stroke("keep"));
        assertNull(scratch.get(0).paper.stroke("move"));

        List<Board> pages = repo.pages(doodles);
        assertEquals(1, pages.size());
        assertFalse(pages.get(0).isBlank());
        assertNotNull(pages.get(0).paper.stroke("move"));
        assertEquals(0, pages.get(0).paper.lastInkedSlice());
        assertEquals(0, openScrollIndex(pages));
    }

    @Test
    public void tearMoveNearBottomDoesNotNameTheTrailingBlankAsLastInked() {
        NotebookPaper source = NotebookPaper.empty(PAGE);
        TestInk.appendCovering(source, nearBottom("gone", 0f), "s0");
        assertEquals("source last inked is the slice that holds the points",
                0, source.lastInkedSlice());

        NotebookPaper dest = NotebookPaper.empty(PAGE);
        TestInk.appendCovering(dest, TestInk.stroke(4f, 20f, 4), "d0");
        source.tearMoveAfter(0, dest, dest.sliceCount() - 1);

        assertNull(source.stroke("gone"));
        assertNotNull(dest.stroke("gone"));
        assertEquals("moved ink is the newest inked slice", 1, dest.lastInkedSlice());
        assertEquals("paper has no empty slice past that ink", 2, dest.sliceCount());
    }

    @Test
    public void densePageMovesAtomicallyOntoDoodles() throws Exception {
        Board blank = repo.createPage(null);
        String adopted = blank.id;
        for (int i = 0; i < DENSE_STROKES; i++) {
            TestInk.appendCovering(blank.paper, denseStroke("s" + i, 20f + (i % 50), 30f + (i % 20) * 35f),
                    i == 0 ? adopted : null);
        }
        repo.saveInk(blank);
        List<Board> before = repo.scratchpadPages();
        Board dense = before.get(0);
        assertEquals(DENSE_STROKES, dense.paper.strokes().size());
        assertEquals(DENSE_STROKES, dense.paper.touching(0).size());

        long started = System.nanoTime();
        repo.movePageToNotebook(dense.id, doodles);
        long ms = (System.nanoTime() - started) / 1_000_000L;
        assertTrue("dense move should finish without a main-thread stall (" + ms + " ms)",
                ms < 5_000);

        assertTrue(repo.scratchpadPages().isEmpty());

        List<Board> pages = repo.pages(doodles);
        assertEquals(1, pages.size());
        assertFalse(pages.get(0).isBlank());
        assertEquals(DENSE_STROKES, pages.get(0).paper.strokes().size());
        assertEquals(DENSE_STROKES, pages.get(0).paper.touching(0).size());
        assertEquals(0, pages.get(0).paper.lastInkedSlice());
        assertEquals(0, openScrollIndex(pages));

        // Survives a flush + reopen the way a crash after Move would.
        NotebookPaper replayed = NotebookPaper.replay(pages.get(0).paper.bytes());
        assertEquals(DENSE_STROKES, replayed.strokes().size());
        assertTrue(replayed.containsInk(0));
    }

    /** Stroke whose drawing pad crosses the next slice mark — the old lastInked trap. */
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

    private static InkRenderer.InkStroke denseStroke(String id, float x, float y) {
        ArrayList<TouchPoint> points = new ArrayList<>(POINTS_EACH);
        for (int i = 0; i < POINTS_EACH; i++) {
            points.add(new TouchPoint(x + i * 0.5f, y + (i % 7), 0.5f, 1f, 0, 0, 1_000L + i));
        }
        return InkRenderer.strokeFrom(id, points);
    }

    private static int openScrollIndex(List<Board> pages) {
        if (pages.isEmpty() || pages.get(0).paper == null) {
            return 0;
        }
        int index = pages.get(0).paper.lastInkedSlice();
        if (index < 0) {
            return 0;
        }
        if (index >= pages.size()) {
            index = pages.size() - 1;
        }
        while (index > 0 && pages.get(index).isBlank()) {
            index--;
        }
        return index;
    }
}
