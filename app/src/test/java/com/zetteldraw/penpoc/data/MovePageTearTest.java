package com.zetteldraw.penpoc.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

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
        doodles = repo.createNotebook("doodles").id;
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

        repo.movePageToNotebook(inked.id, doodles);

        List<Board> scratch = repo.scratchpadPages();
        assertEquals(1, scratch.size());
        assertTrue("Scratchpad keeps only its trailing blank", scratch.get(0).isBlank());
        assertNull(scratch.get(0).paper.stroke("mark"));

        List<Board> pages = repo.pages(doodles);
        assertEquals("inked page plus trailing blank", 2, pages.size());
        assertFalse(pages.get(0).isBlank());
        assertTrue(pages.get(1).isBlank());
        assertNotNull(pages.get(0).paper.stroke("mark"));
        assertEquals("open lands on the moved page", 0, pages.get(0).paper.lastInkedSlice());
        assertEquals(0, openScrollIndex(pages));
    }

    @Test
    public void movingSecondLastWithTrailingBlankLeavesEarlierInkAndShowsMovedPageInDoodles() {
        Board first = inkNearBottom(null, "keep");
        Board second = inkNearBottom(null, "move");
        List<Board> before = repo.scratchpadPages();
        assertEquals("two inked slices plus trailing blank", 3, before.size());
        assertEquals(first.id, before.get(0).id);
        assertEquals(second.id, before.get(1).id);
        assertTrue(before.get(2).isBlank());
        assertEquals(1, second.paper.lastInkedSlice());

        repo.movePageToNotebook(second.id, doodles);

        List<Board> scratch = repo.scratchpadPages();
        assertEquals(2, scratch.size());
        assertEquals(first.id, scratch.get(0).id);
        assertFalse(scratch.get(0).isBlank());
        assertTrue(scratch.get(1).isBlank());
        assertNotNull(scratch.get(0).paper.stroke("keep"));
        assertNull(scratch.get(0).paper.stroke("move"));

        List<Board> pages = repo.pages(doodles);
        assertEquals(2, pages.size());
        assertFalse(pages.get(0).isBlank());
        assertTrue(pages.get(1).isBlank());
        assertNotNull(pages.get(0).paper.stroke("move"));
        assertEquals(0, pages.get(0).paper.lastInkedSlice());
        assertEquals(0, openScrollIndex(pages));
    }

    @Test
    public void tearMoveNearBottomDoesNotNameTheTrailingBlankAsLastInked() {
        NotebookPaper source = NotebookPaper.empty(PAGE);
        source.appendStroke(nearBottom("gone", 0f), "s0");
        assertEquals("source last inked is the slice that holds the points",
                0, source.lastInkedSlice());

        NotebookPaper dest = NotebookPaper.empty(PAGE);
        dest.appendStroke(TestInk.stroke(4f, 20f, 4), "d0");
        source.tearMove(0, dest);

        assertNull(source.stroke("gone"));
        assertNotNull(dest.stroke("gone"));
        assertEquals("moved ink is the newest inked slice", 1, dest.lastInkedSlice());
        assertEquals("paper has no empty slice past that ink", 2, dest.sliceCount());
    }

    @Test
    public void densePageMovesAtomicallyOntoDoodles() throws Exception {
        Board blank = repo.scratchpadPages().get(0);
        String adopted = blank.id;
        for (int i = 0; i < DENSE_STROKES; i++) {
            blank.paper.appendStroke(denseStroke("s" + i, 20f + (i % 50), 30f + (i % 20) * 35f),
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

        List<Board> scratch = repo.scratchpadPages();
        assertEquals(1, scratch.size());
        assertTrue(scratch.get(0).isBlank());
        assertEquals(0, scratch.get(0).paper.strokes().size());

        List<Board> pages = repo.pages(doodles);
        assertEquals(2, pages.size());
        assertFalse(pages.get(0).isBlank());
        assertTrue(pages.get(1).isBlank());
        assertEquals(DENSE_STROKES, pages.get(0).paper.strokes().size());
        assertEquals(DENSE_STROKES, pages.get(0).paper.touching(0).size());
        assertEquals(0, pages.get(0).paper.lastInkedSlice());
        assertEquals(0, openScrollIndex(pages));

        // Survives a flush + reopen the way a crash after Move would.
        NotebookPaper replayed = NotebookPaper.replay(pages.get(0).paper.bytes());
        assertEquals(DENSE_STROKES, replayed.strokes().size());
        assertTrue(replayed.containsInk(0));
    }

    /**
     * Destination append that throws mid-batch must leave that paper unchanged,
     * and tearMove must put the taken strokes back on the source.
     */
    @Test
    public void appendMovedFailureRollsBackDestinationAndTearMoveRestoresSource() throws Exception {
        NotebookPaper dest = NotebookPaper.empty(PAGE);
        dest.appendStroke(denseStroke("keep", 2f, 15f), "slice0");
        int destCount = dest.strokes().size();
        byte[] destBytes = dest.bytes();
        int destSlices = dest.sliceCount();

        ArrayList<InkRenderer.InkStroke> hostile = new ArrayList<>();
        hostile.add(denseStroke("ok", 1f, 1f));
        hostile.add(null);
        try {
            dest.appendMoved(hostile, 0f);
            fail("expected failure on null stroke");
        } catch (RuntimeException expected) {
            assertEquals("destination strokes unchanged", destCount, dest.strokes().size());
            assertEquals(destSlices, dest.sliceCount());
            assertEquals(destBytes.length, dest.bytes().length);
            assertNotNull(dest.stroke("keep"));
        }

        NotebookPaper source = NotebookPaper.empty(PAGE);
        for (int i = 0; i < 30; i++) {
            source.appendStroke(denseStroke("s" + i, 12f, 50f + i), i == 0 ? "src" : null);
        }
        int sourceCount = source.strokes().size();
        NotebookPaper dest2 = NotebookPaper.replay(destBytes);
        NotebookPaper.Tear cut = invokeCut(source, 0);
        assertEquals("cut already removed the slice from the source", 0, source.strokes().size());
        try {
            ArrayList<InkRenderer.InkStroke> bad = new ArrayList<>(cut.taken);
            bad.add(null);
            dest2.appendMoved(bad, 0f);
            fail("expected failure");
        } catch (RuntimeException e) {
            source.strokes().addAll(cut.taken);
        }
        assertEquals("source ink restored after a failed dest append", sourceCount, source.strokes().size());
        assertEquals(destCount, dest2.strokes().size());
        assertNotNull(dest2.stroke("keep"));
        for (int i = 0; i < sourceCount; i++) {
            assertNotNull(source.stroke("s" + i));
        }
    }

    /** Stroke whose drawing pad crosses the next slice mark — the old lastInked trap. */
    private Board inkNearBottom(String notebookId, String strokeId) {
        List<Board> pages = repo.pages(notebookId);
        Board blank = pages.get(pages.size() - 1);
        assertTrue(blank.isBlank());
        // Paper coordinates: the trailing blank starts at paperOrigin.
        blank.paper.appendStroke(nearBottom(strokeId, blank.paperOrigin), blank.id);
        repo.saveInk(blank);
        List<Board> after = repo.pages(notebookId);
        Board inked = after.get(after.size() - 2);
        assertFalse(inked.isBlank());
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

    /** Package cut via tearWipe on a throwaway clone is messy; use reflection. */
    private static NotebookPaper.Tear invokeCut(NotebookPaper paper, int slice) throws Exception {
        java.lang.reflect.Method cut = NotebookPaper.class.getDeclaredMethod("cut", int.class, boolean.class);
        cut.setAccessible(true);
        return (NotebookPaper.Tear) cut.invoke(paper, slice, false);
    }
}
