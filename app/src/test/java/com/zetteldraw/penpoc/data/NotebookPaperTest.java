package com.zetteldraw.penpoc.data;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.Application;

import com.onyx.android.sdk.data.note.TouchPoint;
import com.zetteldraw.penpoc.InkRenderer;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
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
    public void replayStructureSeesExtendGrownSlicesWithoutStrokes() throws Exception {
        NotebookPaper paper = NotebookPaper.empty(100);
        paper.appendStroke(line(null, 4f, 10f, 20f));
        paper.appendStroke(line(null, 4f, 210f, 250f));
        NotebookPaper structure = NotebookPaper.replayStructure(paper.bytes());
        assertEquals(3, structure.sliceCount());
        assertTrue(structure.strokes().isEmpty());
        NotebookPaper ready = NotebookPaper.replay(paper.bytes());
        assertEquals(2, ready.strokes().size());
        assertEquals(3, ready.sliceCount());
    }

    @Test
    public void lastInkedSliceIsRememberedWithoutWalkingPages() throws Exception {
        NotebookPaper paper = NotebookPaper.empty(100);
        assertEquals(-1, paper.lastInkedSlice());
        TestInk.appendCovering(paper, line(null, 4f, 10f, 20f));
        assertEquals(0, paper.lastInkedSlice());
        TestInk.appendCovering(paper, line(null, 4f, 210f, 250f));
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
        TestInk.appendCovering(paper, stroke);

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
    public void strokeColorTrailerReplaysAsBlack() throws Exception {
        NotebookPaper paper = NotebookPaper.empty(200);
        ArrayList<TouchPoint> points = new ArrayList<>();
        points.add(new TouchPoint(10f, 20f, 0.5f, 1f, 0, 0, 1_000L));
        points.add(new TouchPoint(30f, 40f, 0.6f, 1f, 0, 0, 1_008L));
        InkRenderer.InkStroke colored = InkRenderer.strokeOwning(
                "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", points, 0xFF9ED9C8);
        TestInk.appendCovering(paper, colored);
        NotebookPaper again = NotebookPaper.replay(paper.bytes());
        assertEquals(android.graphics.Color.BLACK, again.strokes().get(0).color);
        assertEquals(colored.id, again.strokes().get(0).id);
    }

    /**
     * Slice placement for a selection sprite is paperOrigin − scroll. If a
     * board's stored origin drifts from the live log, seam-spanning ink looks
     * offset or doubled. Origins must track {@link NotebookPaper#origin(int)}.
     */
    @Test
    public void sliceOriginsMatchCumulativeHeightsForSeamAlignment() {
        NotebookPaper paper = NotebookPaper.empty(1420);
        TestInk.appendCovering(paper, line("a", 10f, 100f, 200f));
        TestInk.appendCovering(paper, line("b", 10f, 1500f, 1600f));
        assertEquals(2, paper.sliceCount());
        assertEquals(0f, paper.origin(0), 0f);
        assertEquals(1420f, paper.origin(1), 0f);
        assertEquals(2840f, paper.origin(2), 0f);
        // Screen y of a paper point is origin-local in the slice bitmap plus
        // (paperOrigin − scroll). Both sides of the mark share that rule.
        float scroll = 700f;
        float paperY = 1500f;
        float surfaceFromSprite = paperY - scroll;
        float surfaceFromBase = (paper.origin(1) - scroll) + (paperY - paper.origin(1));
        assertEquals("sprite and punched slice base must agree at the seam",
                surfaceFromSprite, surfaceFromBase, 0.001f);
    }

    @Test
    public void lassoDragAcrossTheMarkIsOneShiftAndUndoSubtractsIt() {
        NotebookPaper paper = NotebookPaper.empty(1420);
        InkRenderer.InkStroke stroke = line("stroke-1", 10f, 1400f, 1600f);
        TestInk.appendCovering(paper, stroke);
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
        TestInk.appendCovering(paper, line("cross", 8f, 1400f, 1600f));
        TestInk.appendCovering(paper, line("above", 8f, 100f, 200f));
        assertEquals("pen-up does not cut a crossing stroke", 2, paper.strokes().size());

        NotebookPaper.Tear deleted = paper.tearDelete(0);
        assertEquals(1, deleted.newIds.size());
        assertNull("the inside piece left with the slice", paper.stroke(deleted.newIds.get(0)));
        InkRenderer.InkStroke stayed = paper.stroke("cross");
        assertEquals("the outside piece keeps the id", "cross", stayed.id);
        assertTrue(stayed.points.get(0).y < 1420f);
        assertTrue("delete closes the gap", stayed.points.get(stayed.points.size() - 1).y < 1420f);

        NotebookPaper wiped = NotebookPaper.empty(1420);
        TestInk.appendCovering(wiped, line("cross", 8f, 1300f, 1500f));
        TestInk.appendCovering(wiped, line("below", 8f, 1500f, 1600f));
        float belowBefore = wiped.stroke("below").points.get(0).y;
        NotebookPaper.Tear wipe = wiped.tearWipe(0);
        assertEquals(1, wipe.newIds.size());
        assertEquals("wipe does not move the page below", belowBefore, wiped.stroke("below").points.get(0).y, 0.01f);
        assertEquals("cross", wiped.stroke("cross").id);
    }

    @Test
    public void moveCutsOnlyTheCrossingStrokeAndKeepsIdsOnTheRest() {
        NotebookPaper source = NotebookPaper.empty(1420);
        TestInk.appendCovering(source, line("cross", 4f, 1400f, 1500f));
        TestInk.appendCovering(source, line("low", 4f, 2000f, 2100f));
        NotebookPaper dest = NotebookPaper.empty(1420);
        TestInk.appendCovering(dest, line("already", 1f, 10f, 20f));

        // Id-preserving Move spine (same as placePageBefore → tearMove*).
        NotebookPaper.Tear moved = source.tearMoveAfter(0, dest, dest.sliceCount() - 1);
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
        TestInk.appendCovering(local, line("cross", 3f, 1400f, 1800f));
        assertEquals(3, local.stroke("cross").points.size());

        NotebookPaper remote = NotebookPaper.empty(1680);
        TestInk.appendCovering(remote, line("other", 9f, 10f, 40f));
        TestInk.appendCovering(remote, line("second", 9f, 1700f, 1900f));

        NotebookPaper pulled = NotebookPaper.replay(remote.bytes());
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
        TestInk.appendCovering(paper, line("first", 1f, 10f, 20f));
        byte[] before = paper.bytes();
        TestInk.appendCovering(paper, line("second", 1f, 30f, 40f));
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
        TestInk.appendCovering(paper, line("cross", 5f, 1410f, 1430f));
        TestInk.appendCovering(paper, line("other", 5f, 10f, 20f));
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
            TestInk.appendCovering(paper, stroke);
            if (i < 120) {
                move.add(stroke.id);
            }
        }
        // Far pages: more strokes so refreshLastInked would be costly if it walked points.
        for (int i = 0; i < 400; i++) {
            float y0 = 5000f + i * 4f;
            TestInk.appendCovering(paper, line("far" + i, 4f, y0, y0 + 20f));
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

    /**
     * v70 routed {@code trimTail} through an empty Y-index during {@code replay},
     * so any log with a tear collapsed to 0 slices and Scratchpad opened as 1/1.
     * Replay after tear must keep the same slice count and stroke ids as live paper.
     */
    @Test
    public void replayAfterTearKeepsSliceCountMatchingLivePaper() throws Exception {
        NotebookPaper paper = NotebookPaper.empty(400);
        TestInk.appendCovering(paper, line("p0", 10f, 20f, 40f));
        TestInk.appendCovering(paper, line("p1", 10f, 420f, 440f));
        TestInk.appendCovering(paper, line("p2", 10f, 820f, 840f));
        assertEquals(3, paper.sliceCount());
        paper.tearDelete(1);
        assertEquals("live tear leaves inked slices + no empty tail", 2, paper.sliceCount());
        assertEquals("p0", paper.stroke("p0").id);
        assertEquals("p2", paper.stroke("p2").id);
        assertNull(paper.stroke("p1"));

        NotebookPaper again = NotebookPaper.replay(paper.bytes());
        assertEquals("replay must not strip slices via trimTail", paper.sliceCount(), again.sliceCount());
        assertEquals(paper.strokes().size(), again.strokes().size());
        assertEquals("p0", again.stroke("p0").id);
        assertEquals("p2", again.stroke("p2").id);
        assertTrue(again.containsInk(0));
        assertTrue(again.containsInk(1));
    }

    /** Truncated header id must throw IOException — not BufferUnderflowException. */
    @Test
    public void replayTruncatedSliceIdThrowsIoException() {
        // empty() has sliceCount=0 (12-byte header). Build a 1-slice header then chop the id.
        ByteBuffer buf = ByteBuffer.allocate(64).order(ByteOrder.BIG_ENDIAN);
        buf.put((byte) 'Z').put((byte) 'D').put((byte) 'L').put((byte) 1);
        buf.putInt(200);
        buf.putInt(1);
        buf.putInt(200);
        buf.putShort((short) 36);
        buf.put(new byte[]{1, 2, 3, 4}); // claim 36-byte id, write 4
        byte[] truncated = Arrays.copyOf(buf.array(), buf.position());
        try {
            NotebookPaper.replay(truncated);
            fail("expected IOException");
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("corrupt")
                    || e.getMessage().contains("truncated"));
        }
    }

    /**
     * Corrupt APPEND with an absurd point count must heal by keeping the good
     * prefix — not OOM and not fail the whole notebook.
     */
    @Test
    public void replayAbsurdStrokePointCountKeepsPrefix() throws Exception {
        NotebookPaper paper = NotebookPaper.empty(200);
        paper.appendStroke(line("keep-me", 4f, 10f, 20f));
        byte[] base = paper.bytes();
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(payload);
        byte[] id = "deadbeef-dead-dead-dead-deadbeefdead".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        out.writeShort(id.length);
        out.write(id);
        out.writeInt(50_000_000);
        out.writeLong(1_000L);
        byte[] body = payload.toByteArray();
        ByteBuffer record = ByteBuffer.allocate(5 + body.length).order(ByteOrder.BIG_ENDIAN);
        record.put((byte) 1);
        record.putInt(body.length);
        record.put(body);
        byte[] corrupt = new byte[base.length + record.position()];
        System.arraycopy(base, 0, corrupt, 0, base.length);
        System.arraycopy(record.array(), 0, corrupt, base.length, record.position());
        NotebookPaper healed = NotebookPaper.replay(corrupt);
        assertEquals(1, healed.strokes().size());
        assertEquals("keep-me", healed.strokes().get(0).id);
        assertTrue(healed.bytes().length <= base.length);
    }
}
