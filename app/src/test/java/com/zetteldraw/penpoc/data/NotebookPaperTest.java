package com.zetteldraw.penpoc.data;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import com.onyx.android.sdk.data.note.TouchPoint;
import com.zetteldraw.penpoc.InkRenderer;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The old per-page model fails these: a crossing stroke was clipped to the
 * first page, a lasso could not move both sides in one shift, and a pull
 * could shelve one page of a line.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class)
public class NotebookPaperTest {
    private static TouchPoint point(float x, float y) {
        return new TouchPoint(x, y, 0.5f, 1f, 0, 0, 1_000L);
    }

    private static InkRenderer.InkStroke line(String id, float x, float y0, float y1) {
        ArrayList<TouchPoint> points = new ArrayList<>();
        points.add(point(x, y0));
        points.add(point(x, (y0 + y1) / 2f));
        points.add(point(x, y1));
        return InkRenderer.strokeFrom(id == null ? UUID.randomUUID().toString() : id, points);
    }

    @Test
    public void lastInkedSliceIsRememberedWithoutWalkingPages() throws Exception {
        NotebookPaper paper = NotebookPaper.empty(100);
        assertEquals(-1, paper.lastInkedSlice());
        paper.appendStroke(line(null, 4f, 10f, 20f));
        assertEquals(0, paper.lastInkedSlice());
        paper.appendStroke(line(null, 4f, 210f, 250f));
        assertEquals(2, paper.lastInkedSlice());
        assertEquals(3, paper.sliceCount());
        NotebookPaper again = NotebookPaper.replay(paper.bytes());
        assertEquals(2, again.lastInkedSlice());
        again.deleteIds(java.util.List.of(again.strokes().get(1).id));
        assertEquals(0, again.lastInkedSlice());
    }

    @Test
    public void aStrokeCrossingTwoSlicesIsOneId() throws Exception {
        NotebookPaper paper = NotebookPaper.empty(1420);
        InkRenderer.InkStroke stroke = line(null, 40f, 1400f, 1500f);
        paper.appendStroke(stroke);

        assertEquals(1, paper.strokes().size());
        assertEquals(stroke.id, paper.strokes().get(0).id);
        assertEquals(1400f, paper.strokes().get(0).points.get(0).y, 0.01f);
        assertEquals(1500f, paper.strokes().get(0).points.get(2).y, 0.01f);
        assertTrue(paper.sliceIndexAt(1400f) != paper.sliceIndexAt(1500f));

        NotebookPaper again = NotebookPaper.replay(paper.bytes());
        assertEquals(1, again.strokes().size());
        assertEquals(stroke.id, again.strokes().get(0).id);
        assertEquals(1500f, again.strokes().get(0).points.get(2).y, 0.01f);
    }

    @Test
    public void lassoDragAcrossTheMarkIsOneShiftAndUndoSubtractsIt() {
        NotebookPaper paper = NotebookPaper.empty(1420);
        InkRenderer.InkStroke stroke = line("stroke-1", 10f, 1400f, 1600f);
        paper.appendStroke(stroke);
        String id = paper.strokes().get(0).id;

        paper.translate(List.of(id), 25f, -30f);
        InkRenderer.InkStroke moved = paper.stroke(id);
        assertEquals(id, moved.id);
        assertEquals(1, paper.strokes().size());
        assertEquals(35f, moved.points.get(0).x, 0.01f);
        assertEquals(1370f, moved.points.get(0).y, 0.01f);
        assertEquals(1570f, moved.points.get(2).y, 0.01f);

        paper.translate(List.of(id), -25f, 30f);
        InkRenderer.InkStroke back = paper.stroke(id);
        assertEquals(id, back.id);
        assertEquals(10f, back.points.get(0).x, 0.01f);
        assertEquals(1400f, back.points.get(0).y, 0.01f);
        assertEquals(1600f, back.points.get(2).y, 0.01f);
    }

    @Test
    public void tearIsTheOnlySplit() {
        NotebookPaper paper = NotebookPaper.empty(1420);
        paper.appendStroke(line("cross", 8f, 1400f, 1600f));
        paper.appendStroke(line("above", 8f, 100f, 200f));
        assertEquals("pen-up does not cut a crossing stroke", 2, paper.strokes().size());

        NotebookPaper.Tear deleted = paper.tearDelete(0);
        assertEquals(1, deleted.newIds.size());
        assertNull("the inside piece left with the slice", paper.stroke(deleted.newIds.get(0)));
        InkRenderer.InkStroke stayed = paper.stroke("cross");
        assertEquals("the outside piece keeps the id", "cross", stayed.id);
        assertTrue(stayed.points.get(0).y < 1420f);
        assertTrue("delete closes the gap", stayed.points.get(stayed.points.size() - 1).y < 1420f);

        NotebookPaper wiped = NotebookPaper.empty(1420);
        wiped.appendStroke(line("cross", 8f, 1300f, 1500f));
        wiped.appendStroke(line("below", 8f, 1500f, 1600f));
        float belowBefore = wiped.stroke("below").points.get(0).y;
        NotebookPaper.Tear wipe = wiped.tearWipe(0);
        assertEquals(1, wipe.newIds.size());
        assertEquals("wipe does not move the page below", belowBefore, wiped.stroke("below").points.get(0).y, 0.01f);
        assertEquals("cross", wiped.stroke("cross").id);
    }

    @Test
    public void moveCutsOnlyTheCrossingStrokeAndKeepsIdsOnTheRest() {
        NotebookPaper source = NotebookPaper.empty(1420);
        source.appendStroke(line("cross", 4f, 1400f, 1500f));
        source.appendStroke(line("low", 4f, 2000f, 2100f));
        NotebookPaper dest = NotebookPaper.empty(1420);
        dest.appendStroke(line("already", 1f, 10f, 20f));

        NotebookPaper.Tear moved = source.tearMove(0, dest);
        assertEquals(1, moved.newIds.size());
        assertNotEquals("cross", moved.newIds.get(0));
        assertEquals("cross", source.stroke("cross").id);
        assertTrue(source.stroke("low").points.get(0).y < 1420f);
        boolean foundNew = false;
        for (InkRenderer.InkStroke stroke : dest.strokes()) {
            if (stroke.id.equals(moved.newIds.get(0))) {
                foundNew = true;
            }
        }
        assertTrue(foundNew);
        assertEquals("already", dest.strokes().get(0).id);
    }

    @Test
    public void aPullReplacesTheWholeLogAndCannotLeaveHalfAStroke() throws Exception {
        NotebookPaper local = NotebookPaper.empty(1420);
        local.appendStroke(line("cross", 3f, 1400f, 1800f));
        assertEquals(3, local.stroke("cross").points.size());

        NotebookPaper remote = NotebookPaper.empty(1680);
        remote.appendStroke(line("other", 9f, 10f, 40f));
        remote.appendStroke(line("second", 9f, 1700f, 1900f));

        NotebookPaper pulled = NotebookPaper.replace(remote.bytes());
        assertNull(pulled.stroke("cross"));
        assertEquals(2, pulled.strokes().size());
        assertEquals(3, pulled.stroke("second").points.size());
        assertEquals(1700f, pulled.stroke("second").points.get(0).y, 0.01f);
        assertEquals(1900f, pulled.stroke("second").points.get(2).y, 0.01f);
        assertEquals(1680, pulled.sliceHeight);
    }

    @Test
    public void migrationOfMixedPageHeightsDoesNotMoveExistingPoints() throws Exception {
        String first = UUID.randomUUID().toString();
        String second = UUID.randomUUID().toString();
        InkRenderer.InkStroke onShort = line("a", 12f, 100f, 400f);
        InkRenderer.InkStroke onTall = line("b", 18f, 50f, 1600f);
        NotebookPaper paper = NotebookPaper.migrate(List.of(
                new NotebookPaper.SourcePage(first, 1420, List.of(onShort)),
                new NotebookPaper.SourcePage(second, 1680, List.of(onTall))), 1420);

        assertEquals(100f, paper.stroke("a").points.get(0).y, 0.01f);
        assertEquals(400f, paper.stroke("a").points.get(2).y, 0.01f);
        assertEquals("the tall page starts after 1420, not after 1680",
                1420f + 50f, paper.stroke("b").points.get(0).y, 0.01f);
        assertEquals(1420f + 1600f, paper.stroke("b").points.get(2).y, 0.01f);
        assertEquals(1420, paper.heightAt(0));
        assertEquals(1680, paper.heightAt(1));

        NotebookPaper read = NotebookPaper.replay(paper.bytes());
        assertEquals(paper.stroke("a").points.get(0).y, read.stroke("a").points.get(0).y, 0f);
        assertEquals(paper.stroke("b").points.get(0).y, read.stroke("b").points.get(0).y, 0f);
        assertEquals(paper.stroke("b").points.get(2).y, read.stroke("b").points.get(2).y, 0f);
        assertEquals("a", read.stroke("a").id);
        assertEquals("b", read.stroke("b").id);
    }

    @Test
    public void appendDoesNotRewriteEarlierBytes() throws Exception {
        NotebookPaper paper = NotebookPaper.empty(1420);
        paper.appendStroke(line("first", 1f, 10f, 20f));
        byte[] before = paper.bytes();
        paper.appendStroke(line("second", 1f, 30f, 40f));
        byte[] after = paper.bytes();
        assertTrue(after.length > before.length);
        byte[] prefix = new byte[before.length];
        System.arraycopy(after, 0, prefix, 0, before.length);
        assertArrayEquals(before, prefix);

        NotebookPaper read = NotebookPaper.replay(after);
        assertEquals(2, read.strokes().size());
        assertEquals("first", read.strokes().get(0).id);
        assertEquals("second", read.strokes().get(1).id);
    }

    @Test
    public void eraserDeletesTheWholeCrossingStroke() {
        NotebookPaper paper = NotebookPaper.empty(1420);
        paper.appendStroke(line("cross", 5f, 1410f, 1430f));
        paper.appendStroke(line("other", 5f, 10f, 20f));
        paper.deleteIds(List.of("cross"));
        assertNull(paper.stroke("cross"));
        assertEquals("other", paper.stroke("other").id);
    }

    @Test
    public void denseLassoTranslateStaysFastAndKeepsLastInked() {
        NotebookPaper paper = NotebookPaper.empty(1420);
        ArrayList<String> move = new ArrayList<>();
        for (int i = 0; i < 800; i++) {
            float y0 = 20f + (i % 40) * 30f;
            InkRenderer.InkStroke stroke = line("s" + i, 8f + (i % 10), y0, y0 + 40f);
            paper.appendStroke(stroke);
            if (i < 120) {
                move.add(stroke.id);
            }
        }
        // Far pages: more strokes so refreshLastInked would be costly if it walked points.
        for (int i = 0; i < 400; i++) {
            float y0 = 5000f + i * 4f;
            paper.appendStroke(line("far" + i, 4f, y0, y0 + 20f));
        }
        int lastBefore = paper.lastInkedSlice();

        long t0 = System.nanoTime();
        paper.translate(move, 12f, 18f);
        long ms = (System.nanoTime() - t0) / 1_000_000L;

        assertTrue("dense translate took " + ms + "ms", ms < 80);
        assertEquals(20f + 18f, paper.stroke("s0").points.get(0).y, 0.01f);
        assertEquals(lastBefore, paper.lastInkedSlice());
        assertEquals(8f + 12f, paper.stroke("s0").points.get(0).x, 0.01f);
        assertEquals(20f, paper.stroke("s200").points.get(0).y, 0.01f);
    }
}
