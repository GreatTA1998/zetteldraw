package com.zetteldraw.penpoc.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

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
import java.util.ArrayList;
import java.util.List;
import java.util.zip.DeflaterOutputStream;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class)
public class InkCodecTest {
    @Test
    public void roundTripsEveryPointField() throws IOException {
        List<InkRenderer.InkStroke> strokes = new ArrayList<>();
        strokes.add(TestInk.stroke(10f, 20f, 40));
        strokes.add(TestInk.stroke(300f, 400f, 1));
        byte[] bytes = InkCodec.encode(strokes);
        TestInk.assertSameInk(strokes, InkCodec.decode(bytes));
        assertEquals(bytes.length, InkCodec.encode(InkCodec.decode(bytes)).length);
    }

    @Test
    public void isCompact() {
        List<InkRenderer.InkStroke> strokes = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            strokes.add(TestInk.stroke(i * 10f, i * 5f, 100));
        }
        int bytes = InkCodec.encode(strokes).length;
        assertTrue("2000 points in " + bytes + " bytes", bytes < 2000 * 28);
    }

    @Test
    public void roundTripsStrokeIds() throws IOException {
        List<InkRenderer.InkStroke> strokes = new ArrayList<>();
        strokes.add(TestInk.stroke(10f, 20f, 5));
        strokes.add(TestInk.stroke(30f, 40f, 5));
        List<InkRenderer.InkStroke> decoded = InkCodec.decode(InkCodec.encode(strokes));
        assertEquals(strokes.get(0).id, decoded.get(0).id);
        assertEquals(strokes.get(1).id, decoded.get(1).id);
        assertNotEquals(decoded.get(0).id, decoded.get(1).id);
    }

    @Test
    public void readsVersionOneFilesWithStableDerivedIds() throws IOException {
        List<InkRenderer.InkStroke> strokes = new ArrayList<>();
        strokes.add(TestInk.stroke(10f, 20f, 6));
        strokes.add(TestInk.stroke(10f, 20f, 6));
        byte[] v1 = encodeV1(strokes);

        List<InkRenderer.InkStroke> first = InkCodec.decode(v1);
        List<InkRenderer.InkStroke> again = InkCodec.decode(v1);

        TestInk.assertSameInk(strokes, first);
        assertEquals("same file, same ids on every device", first.get(0).id, again.get(0).id);
        assertEquals(first.get(1).id, again.get(1).id);
        assertNotEquals("identical strokes still get distinct ids", first.get(0).id, first.get(1).id);
        assertEquals(InkCodec.VERSION, InkCodec.encode(first)[3]);
        List<InkRenderer.InkStroke> upgraded = InkCodec.decode(InkCodec.encode(first));
        assertEquals(first.get(0).id, upgraded.get(0).id);
    }

    @Test
    public void movedStrokeKeepsIdThroughTheFile() throws IOException {
        InkRenderer.InkStroke stroke = TestInk.stroke(10f, 20f, 4);
        List<InkRenderer.InkStroke> moved = new ArrayList<>();
        moved.add(stroke.translated(100f, 50f));
        InkRenderer.InkStroke decoded = InkCodec.decode(InkCodec.encode(moved)).get(0);
        assertEquals(stroke.id, decoded.id);
        assertEquals(110f, decoded.points.get(0).x, 0f);
        assertEquals(70f, decoded.points.get(0).y, 0f);
    }

    /** The v1 layout exactly as v7 wrote it: no stroke ids. */
    private static byte[] encodeV1(List<InkRenderer.InkStroke> strokes) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write(new byte[]{'Z', 'D', 'I', 1});
        DataOutputStream out = new DataOutputStream(new DeflaterOutputStream(bytes));
        out.writeInt(strokes.size());
        for (InkRenderer.InkStroke stroke : strokes) {
            List<TouchPoint> points = stroke.points;
            out.writeInt(points.size());
            long t0 = points.get(0).timestamp;
            out.writeLong(t0);
            for (TouchPoint p : points) {
                out.writeFloat(p.x);
                out.writeFloat(p.y);
                out.writeFloat(p.pressure);
                out.writeFloat(p.size);
                out.writeShort(p.tiltX);
                out.writeShort(p.tiltY);
                out.writeInt((int) (p.timestamp - t0));
            }
        }
        out.close();
        return bytes.toByteArray();
    }

    @Test
    public void rejectsForeignFiles() {
        assertThrows(IOException.class, () -> InkCodec.decode(new byte[]{1, 2, 3, 4, 5}));
    }

    /** A page the size of a real notebook page, through the inflate-once decoder. */
    @Test
    public void fastPathRoundTripsALargeVersionTwoPage() throws IOException {
        List<InkRenderer.InkStroke> strokes = largePage(40, 300);
        byte[] bytes = InkCodec.encode(strokes);
        assertTrue("encoded " + bytes.length, bytes.length > 40_000);
        long started = System.nanoTime();
        List<InkRenderer.InkStroke> decoded = InkCodec.decode(bytes);
        long ms = (System.nanoTime() - started) / 1_000_000L;
        assertTrue("decode took " + ms + " ms for " + bytes.length + " bytes", ms < 1_000);
        TestInk.assertSameInk(strokes, decoded);
        assertEquals(strokes.get(0).id, decoded.get(0).id);
        assertEquals(strokes.get(strokes.size() - 1).id, decoded.get(decoded.size() - 1).id);
        assertEquals(InkCodec.VERSION, bytes[3]);
    }

    /** v1 files (no stroke ids) take the same inflate-once path. */
    @Test
    public void fastPathRoundTripsALargeVersionOnePage() throws IOException {
        List<InkRenderer.InkStroke> strokes = largePage(40, 300);
        byte[] bytes = encodeV1(strokes);
        assertEquals(1, bytes[3]);
        assertTrue("encoded " + bytes.length, bytes.length > 40_000);
        long started = System.nanoTime();
        List<InkRenderer.InkStroke> decoded = InkCodec.decode(bytes);
        long ms = (System.nanoTime() - started) / 1_000_000L;
        assertTrue("v1 decode took " + ms + " ms for " + bytes.length + " bytes", ms < 1_000);
        TestInk.assertSameInk(strokes, decoded);
        List<InkRenderer.InkStroke> again = InkCodec.decode(bytes);
        assertEquals(decoded.get(0).id, again.get(0).id);
        assertEquals(decoded.get(decoded.size() - 1).id, again.get(decoded.size() - 1).id);
        assertNotEquals(decoded.get(0).id, decoded.get(1).id);
    }

    @Test
    public void rejectsCorruptInput() throws IOException {
        assertThrows(IOException.class, () -> InkCodec.decode(null));
        assertThrows(IOException.class, () -> InkCodec.decode(new byte[0]));
        assertThrows(IOException.class, () -> InkCodec.decode(new byte[]{'Z', 'D', 'I'}));
        assertThrows(IOException.class, () -> InkCodec.decode(new byte[]{'Z', 'D', 'I', 9, 1, 2, 3, 4}));
        assertThrows(IOException.class, () -> InkCodec.decode(new byte[]{'Z', 'D', 'I', 2, 0, 1, 2, 3, 4, 5}));
        byte[] v2 = InkCodec.encode(java.util.Collections.singletonList(TestInk.stroke(1f, 2f, 8)));
        assertThrows(IOException.class, () -> InkCodec.decode(java.util.Arrays.copyOf(v2, v2.length / 2)));
        assertThrows(IOException.class, () -> InkCodec.decode(deflated(2, out -> out.writeInt(-3))));
        assertThrows(IOException.class, () -> InkCodec.decode(deflated(2, out -> {
            out.writeInt(1);
            out.writeLong(0);
            out.writeLong(0);
            out.writeInt(Integer.MAX_VALUE);
            out.writeLong(0);
        })));
        assertThrows(IOException.class, () -> InkCodec.decode(deflated(1, out -> {
            out.writeInt(1);
            out.writeInt(4);
            out.writeLong(0);
            out.writeFloat(1f);
        })));
    }

    private static List<InkRenderer.InkStroke> largePage(int strokes, int points) {
        List<InkRenderer.InkStroke> page = new ArrayList<>();
        for (int s = 0; s < strokes; s++) {
            page.add(TestInk.stroke(s * 8f, s * 3f, points));
        }
        return page;
    }

    private static byte[] deflated(int version, Payload body) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write(new byte[]{'Z', 'D', 'I', (byte) version});
        DataOutputStream out = new DataOutputStream(new DeflaterOutputStream(bytes));
        body.write(out);
        out.close();
        return bytes.toByteArray();
    }

    private interface Payload {
        void write(DataOutputStream out) throws IOException;
    }
}
