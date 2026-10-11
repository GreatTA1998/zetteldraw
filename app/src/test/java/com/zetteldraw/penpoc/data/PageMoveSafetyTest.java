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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Zero-tolerance invariants for Move / place-before: no page or stroke id may
 * vanish silently. Covers last-page, second-to-last, trailing-blank targets,
 * before-page-1, dense/sparse, and stale Board.sliceIndex after a prior tear.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class)
public class PageMoveSafetyTest {
    private static final int PAGE = 800;
    private static final int DENSE = 400;
    private static final int POINTS = 20;

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Device device;
    private RoomBoardRepository repo;
    private String book;

    @Before
    public void setUp() throws Exception {
        device = new Device("a", tmp.newFolder("a"), 3_000_000L);
        repo = device.repo;
        book = repo.createNotebook("safety-book").id;
        repo.ensureSheet(null, PAGE, PAGE, 0L);
        repo.ensureSheet(book, PAGE, PAGE, 0L);
    }

    @After
    public void tearDown() {
        device.close();
    }

    @Test
    public void sameNotebookPlaceBeforeKeepsEveryStrokeIdAndPageCount() {
        Board p1 = inkNearBottom(book, "a");
        Board p2 = inkNearBottom(book, "b");
        Board p3 = inkNearBottom(book, "c");
        Board p4 = inkNearBottom(book, "d");
        Set<String> strokeIds = strokeIdsOn(book);
        assertEquals(4, strokeIds.size());

        repo.placePageBefore(p3.id, p2.id);

        List<Board> after = repo.pages(book);
        assertEquals(5, after.size());
        assertEquals(p1.id, after.get(0).id);
        assertEquals(p3.id, after.get(1).id);
        assertEquals(p2.id, after.get(2).id);
        assertEquals(p4.id, after.get(3).id);
        assertTrue(after.get(4).isBlank());
        assertEquals(strokeIds, strokeIdsOn(book));
        assertAllSliceIdsPresent(book, p1.id, p2.id, p3.id, p4.id);
    }

    @Test
    public void placeLastPageBeforePageOneKeepsLastPage() {
        Board p1 = inkNearBottom(book, "a");
        Board p2 = inkNearBottom(book, "b");
        Board last = inkNearBottom(book, "last");
        Set<String> strokes = strokeIdsOn(book);

        repo.placePageBefore(last.id, p1.id);

        List<Board> after = repo.pages(book);
        assertEquals(4, after.size());
        assertEquals(last.id, after.get(0).id);
        assertEquals(p1.id, after.get(1).id);
        assertEquals(p2.id, after.get(2).id);
        assertTrue(after.get(3).isBlank());
        assertNotNull(after.get(0).paper.stroke("last"));
        assertEquals(strokes, strokeIdsOn(book));
    }

    @Test
    public void placeSecondToLastOntoTrailingBlankAppendsWithoutDroppingLast() {
        Board p1 = inkNearBottom(book, "a");
        Board secondLast = inkNearBottom(book, "b");
        Board last = inkNearBottom(book, "c");
        Board blank = repo.pages(book).get(repo.pages(book).size() - 1);
        assertTrue(blank.isBlank());

        repo.placePageBefore(secondLast.id, blank.id);

        List<Board> after = repo.pages(book);
        assertEquals(4, after.size());
        assertEquals(p1.id, after.get(0).id);
        assertEquals(last.id, after.get(1).id);
        assertEquals(secondLast.id, after.get(2).id);
        assertTrue(after.get(3).isBlank());
        assertNotNull(after.get(1).paper.stroke("c"));
        assertNotNull(after.get(2).paper.stroke("b"));
    }

    @Test
    public void crossNotebookPlaceBeforeRemovesSourceOnlyAfterDestHasPage() {
        String other = repo.createNotebook("dest").id;
        repo.ensureSheet(other, PAGE, PAGE, 0L);
        Board anchor = inkNearBottom(other, "anchor");
        Board source = inkNearBottom(null, "moved");
        String sourceId = source.id;
        Set<String> sourceStrokes = strokeIdsOn(null);

        repo.placePageBefore(sourceId, anchor.id);

        List<Board> scratch = repo.pages(null);
        assertTrue("source notebook must not keep the moved page id",
                scratch.stream().noneMatch(b -> sourceId.equals(b.id)));
        List<Board> dest = repo.pages(other);
        assertEquals(sourceId, dest.get(0).id);
        assertEquals(anchor.id, dest.get(1).id);
        assertTrue(dest.get(2).isBlank());
        for (String id : sourceStrokes) {
            assertNotNull("dest must hold source stroke " + id, dest.get(0).paper.stroke(id));
        }
    }

    @Test
    public void crossNotebookPlaceOntoTrailingBlankKeepsLastSourcePageNeighbors() {
        String other = repo.createNotebook("dest-blank").id;
        repo.ensureSheet(other, PAGE, PAGE, 0L);
        inkNearBottom(other, "stay");
        Board first = inkNearBottom(null, "keep");
        Board last = inkNearBottom(null, "tail");
        Board destBlank = repo.pages(other).get(repo.pages(other).size() - 1);

        repo.placePageBefore(last.id, destBlank.id);

        List<Board> scratch = repo.pages(null);
        assertEquals(2, scratch.size());
        assertEquals(first.id, scratch.get(0).id);
        assertTrue(scratch.get(1).isBlank());
        assertNotNull(scratch.get(0).paper.stroke("keep"));
        List<Board> dest = repo.pages(other);
        assertEquals(last.id, dest.get(dest.size() - 2).id);
        assertNotNull(dest.get(dest.size() - 2).paper.stroke("tail"));
    }

    @Test
    public void staleSliceIndexStillMovesTheRequestedPageNotItsNeighbor() {
        String other = repo.createNotebook("stale-dest").id;
        repo.ensureSheet(other, PAGE, PAGE, 0L);
        Board anchor = inkNearBottom(other, "anchor");

        Board s1 = inkNearBottom(null, "s1");
        Board s2 = inkNearBottom(null, "s2");
        Board s3 = inkNearBottom(null, "s3");
        assertEquals(0, s1.sliceIndex);
        assertEquals(1, s2.sliceIndex);
        assertEquals(2, s3.sliceIndex);
        repo.deletePage(s1.id);
        // Cache still holds s2.sliceIndex == 1, but paper is now [s2, s3].
        // Old code tore paper[1] (s3) and the last/neighbor page vanished.
        assertEquals("stale sliceIndex still says 1", 1, s2.sliceIndex);
        assertEquals(s2.id, s2.paper.sliceId(0));
        assertEquals(s3.id, s2.paper.sliceId(1));

        repo.placePageBefore(s2.id, anchor.id);

        List<Board> scratch = repo.pages(null);
        assertTrue("s3 must remain on scratchpad",
                scratch.stream().anyMatch(b -> s3.id.equals(b.id)));
        assertTrue("s2 must leave scratchpad",
                scratch.stream().noneMatch(b -> s2.id.equals(b.id)));
        List<Board> dest = repo.pages(other);
        assertTrue("s2 must arrive on dest",
                dest.stream().anyMatch(b -> s2.id.equals(b.id)));
        assertTrue("s3 must not be stolen onto dest",
                dest.stream().noneMatch(b -> s3.id.equals(b.id)));
        assertNotNull(find(dest, s2.id).paper.stroke("s2"));
        assertNotNull(find(scratch, s3.id).paper.stroke("s3"));
    }

    @Test
    public void denseLastPagePlaceBeforeKeepsEveryStroke() {
        Board early = inkNearBottom(book, "early");
        Board blank = repo.pages(book).get(repo.pages(book).size() - 1);
        String adopted = blank.id;
        float origin = blank.paperOrigin;
        for (int i = 0; i < DENSE; i++) {
            blank.paper.appendStroke(
                    denseStroke("d" + i, 12f + (i % 40), origin + 40f + (i % 15) * 40f),
                    i == 0 ? adopted : null);
        }
        repo.saveInk(blank);
        Board denseLast = repo.pages(book).get(repo.pages(book).size() - 2);
        assertEquals(denseLast.id, adopted);
        assertEquals(DENSE, denseLast.paper.touching(denseLast.sliceIndex).size());

        repo.placePageBefore(denseLast.id, early.id);

        List<Board> after = repo.pages(book);
        assertEquals(denseLast.id, after.get(0).id);
        assertEquals(early.id, after.get(1).id);
        assertEquals(DENSE, after.get(0).paper.touching(0).size());
        for (int i = 0; i < DENSE; i++) {
            assertNotNull(after.get(0).paper.stroke("d" + i));
        }
        assertNotNull(after.get(1).paper.stroke("early"));
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

    private Set<String> strokeIdsOn(String notebookId) {
        List<Board> pages = repo.pages(notebookId);
        HashSet<String> ids = new HashSet<>();
        if (pages.isEmpty() || pages.get(0).paper == null) {
            return ids;
        }
        for (InkRenderer.InkStroke stroke : pages.get(0).paper.strokes()) {
            ids.add(stroke.id);
        }
        return ids;
    }

    private void assertAllSliceIdsPresent(String notebookId, String... ids) {
        HashSet<String> have = new HashSet<>();
        for (Board page : repo.pages(notebookId)) {
            if (!page.isBlank()) {
                have.add(page.id);
            }
        }
        for (String id : ids) {
            assertTrue("missing page " + id, have.contains(id));
        }
    }

    private static Board find(List<Board> pages, String id) {
        for (Board page : pages) {
            if (page.id.equals(id)) {
                return page;
            }
        }
        throw new AssertionError("missing " + id);
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
        ArrayList<TouchPoint> points = new ArrayList<>(POINTS);
        for (int i = 0; i < POINTS; i++) {
            points.add(new TouchPoint(x + i * 0.5f, y + (i % 7), 0.5f, 1f, 0, 0, 1_000L + i));
        }
        return InkRenderer.strokeFrom(id, points);
    }
}
