package com.zetteldraw.penpoc.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
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
 * Same-notebook Reorder places a page after the one on screen. Dense pages
 * rewrite the log once; a failure rolls the paper back.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class)
public class PageReorderTest {
    private static final int PAGE = 800;
    private static final int DENSE_STROKES = 800;
    private static final int POINTS_EACH = 40;

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Device device;
    private RoomBoardRepository repo;
    private String notebook;

    @Before
    public void setUp() throws Exception {
        device = new Device("a", tmp.newFolder("a"), 2_000_000L);
        repo = device.repo;
        notebook = repo.createNotebook("reorder-book").id;
        repo.ensureSheet(notebook, PAGE, PAGE, 0L);
    }

    @After
    public void tearDown() {
        device.close();
    }

    @Test
    public void reorderAfterPageTwoOfFourYieldsOneThreeTwoFour() {
        Board p1 = inkNearBottom(notebook, "a");
        Board p2 = inkNearBottom(notebook, "b");
        Board p3 = inkNearBottom(notebook, "c");
        Board p4 = inkNearBottom(notebook, "d");
        List<Board> before = repo.pages(notebook);
        assertEquals(5, before.size());
        assertEquals(p1.id, before.get(0).id);
        assertEquals(p2.id, before.get(1).id);
        assertEquals(p3.id, before.get(2).id);
        assertEquals(p4.id, before.get(3).id);

        // Place page 2 after page 3 → 1,3,2,4 (+ blank).
        repo.reorderPageAfter(p2.id, p3.id);

        List<Board> after = repo.pages(notebook);
        assertEquals(5, after.size());
        assertEquals(p1.id, after.get(0).id);
        assertEquals(p3.id, after.get(1).id);
        assertEquals(p2.id, after.get(2).id);
        assertEquals(p4.id, after.get(3).id);
        assertTrue(after.get(4).isBlank());
        assertNotNull(after.get(0).paper.stroke("a"));
        assertNotNull(after.get(1).paper.stroke("c"));
        assertNotNull(after.get(2).paper.stroke("b"));
        assertNotNull(after.get(3).paper.stroke("d"));
    }

    @Test
    public void crossNotebookReorderIsRefused() {
        String other = repo.createNotebook("other").id;
        repo.ensureSheet(other, PAGE, PAGE, 0L);
        Board source = inkNearBottom(notebook, "keep");
        Board foreign = inkNearBottom(other, "there");

        repo.reorderPageAfter(source.id, foreign.id);

        List<Board> pages = repo.pages(notebook);
        assertEquals(source.id, pages.get(0).id);
        assertNotNull(pages.get(0).paper.stroke("keep"));
        assertEquals(foreign.id, repo.pages(other).get(0).id);
    }

    @Test
    public void placePageBeforeMovesAcrossNotebooksAndKeepsId() {
        String other = repo.createNotebook("other").id;
        repo.ensureSheet(other, PAGE, PAGE, 0L);
        Board first = inkNearBottom(other, "stay");
        Board second = inkNearBottom(other, "anchor");
        Board source = inkNearBottom(notebook, "moved");

        repo.placePageBefore(source.id, first.id);

        List<Board> src = repo.pages(notebook);
        assertEquals(1, src.size());
        assertTrue(src.get(0).isBlank());
        List<Board> dest = repo.pages(other);
        assertEquals(4, dest.size());
        assertEquals(source.id, dest.get(0).id);
        assertEquals(first.id, dest.get(1).id);
        assertEquals(second.id, dest.get(2).id);
        assertTrue(dest.get(3).isBlank());
        assertNotNull(dest.get(0).paper.stroke("moved"));
        assertNotNull(dest.get(1).paper.stroke("stay"));
    }

    @Test
    public void placePageBeforePageOneBecomesNewFirst() {
        Board p1 = inkNearBottom(notebook, "a");
        Board p2 = inkNearBottom(notebook, "b");
        Board p3 = inkNearBottom(notebook, "c");

        repo.placePageBefore(p3.id, p1.id);

        List<Board> after = repo.pages(notebook);
        assertEquals(p3.id, after.get(0).id);
        assertEquals(p1.id, after.get(1).id);
        assertEquals(p2.id, after.get(2).id);
        assertTrue(after.get(3).isBlank());
    }

    @Test
    public void densePlacePageBeforeKeepsEveryStroke() throws Exception {
        String other = repo.createNotebook("dense-dest").id;
        repo.ensureSheet(other, PAGE, PAGE, 0L);
        Board anchor = inkNearBottom(other, "anchor");
        Board blank = repo.pages(notebook).get(0);
        String adopted = blank.id;
        for (int i = 0; i < DENSE_STROKES; i++) {
            blank.paper.appendStroke(denseStroke("s" + i, 20f + (i % 50), 30f + (i % 20) * 35f),
                    i == 0 ? adopted : null);
        }
        repo.saveInk(blank);
        Board dense = repo.pages(notebook).get(0);
        assertEquals(DENSE_STROKES, dense.paper.touching(0).size());

        long started = System.nanoTime();
        repo.placePageBefore(dense.id, anchor.id);
        long ms = (System.nanoTime() - started) / 1_000_000L;
        assertTrue("dense place-before should finish without a stall (" + ms + " ms)", ms < 5_000);

        List<Board> dest = repo.pages(other);
        assertEquals(dense.id, dest.get(0).id);
        assertEquals(DENSE_STROKES, dest.get(0).paper.touching(0).size());
        NotebookPaper replayed = NotebookPaper.replay(dest.get(0).paper.bytes());
        assertTrue(replayed.containsInk(0));
    }

    @Test
    public void reorderOntoSelfIsNoOp() {
        Board only = inkNearBottom(notebook, "solo");
        repo.reorderPageAfter(only.id, only.id);
        assertEquals(only.id, repo.pages(notebook).get(0).id);
        assertNotNull(repo.pages(notebook).get(0).paper.stroke("solo"));
    }

    @Test
    public void densePageReorderKeepsEveryStroke() throws Exception {
        Board blank = repo.pages(notebook).get(0);
        String adopted = blank.id;
        for (int i = 0; i < DENSE_STROKES; i++) {
            blank.paper.appendStroke(denseStroke("s" + i, 20f + (i % 50), 30f + (i % 20) * 35f),
                    i == 0 ? adopted : null);
        }
        repo.saveInk(blank);
        Board second = inkNearBottom(notebook, "anchor");
        Board dense = repo.pages(notebook).get(0);
        assertEquals(DENSE_STROKES, dense.paper.touching(0).size());

        repo.reorderPageAfter(dense.id, second.id);

        List<Board> pages = repo.pages(notebook);
        assertEquals(dense.id, pages.get(1).id);
        assertEquals(DENSE_STROKES, pages.get(1).paper.touching(1).size());
        assertEquals(DENSE_STROKES + 1, pages.get(1).paper.strokes().size());
        NotebookPaper replayed = NotebookPaper.replay(pages.get(1).paper.bytes());
        assertEquals(DENSE_STROKES + 1, replayed.strokes().size());
        assertTrue(replayed.containsInk(1));
    }

    @Test
    public void reorderSameIndexIsNoOp() {
        NotebookPaper paper = NotebookPaper.empty(PAGE);
        paper.appendStroke(nearBottom("a", 0f), "s0");
        paper.appendStroke(nearBottom("b", PAGE), "s1");
        paper.appendStroke(nearBottom("c", 2f * PAGE), "s2");
        byte[] logBefore = paper.bytes();
        paper.reorderAfter(1, 1);
        assertEquals(logBefore.length, paper.bytes().length);
        assertEquals("s0", paper.sliceId(0));
        assertEquals("s1", paper.sliceId(1));
        assertEquals("s2", paper.sliceId(2));
        assertNotNull(paper.stroke("a"));
        assertNotNull(paper.stroke("b"));
        assertNotNull(paper.stroke("c"));
    }

    private Board inkNearBottom(String notebookId, String strokeId) {
        List<Board> pages = repo.pages(notebookId);
        Board blank = pages.get(pages.size() - 1);
        assertTrue(blank.isBlank());
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
}
