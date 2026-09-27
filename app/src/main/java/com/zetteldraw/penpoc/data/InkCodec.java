package com.zetteldraw.penpoc.data;

import com.onyx.android.sdk.data.note.TouchPoint;
import com.zetteldraw.penpoc.InkRenderer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * One compact stroke file per board ({@code .zdi}).
 *
 * <pre>
 * "ZDI" u8 version
 * deflate {
 *   i32 strokeCount
 *   per stroke: i32 pointCount, i64 firstTimestamp
 *     per point: f32 x, f32 y, f32 pressure, f32 size, i16 tiltX, i16 tiltY, i32 dtMillis
 * }
 * </pre>
 *
 * Widths are not stored; {@link InkRenderer#strokeFrom} recomputes them on load.
 */
public final class InkCodec {
    public static final int VERSION = 1;
    private static final byte[] MAGIC = {'Z', 'D', 'I'};

    private InkCodec() {
    }

    public static byte[] encode(List<InkRenderer.InkStroke> strokes) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            bytes.write(MAGIC);
            bytes.write(VERSION);
            Deflater deflater = new Deflater(Deflater.BEST_SPEED);
            DataOutputStream out = new DataOutputStream(new DeflaterOutputStream(bytes, deflater));
            out.writeInt(strokes.size());
            for (InkRenderer.InkStroke stroke : strokes) {
                List<TouchPoint> points = stroke.points;
                out.writeInt(points.size());
                long t0 = points.isEmpty() ? 0L : points.get(0).timestamp;
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
            deflater.end();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static List<InkRenderer.InkStroke> decode(byte[] data) throws IOException {
        if (data == null || data.length < 4 || data[0] != MAGIC[0] || data[1] != MAGIC[1] || data[2] != MAGIC[2]) {
            throw new IOException("not a zetteldraw ink file");
        }
        if (data[3] != VERSION) {
            throw new IOException("unsupported ink version " + data[3]);
        }
        DataInputStream in = new DataInputStream(new InflaterInputStream(
                new ByteArrayInputStream(data, 4, data.length - 4)));
        try {
            int strokeCount = in.readInt();
            ArrayList<InkRenderer.InkStroke> strokes = new ArrayList<>(Math.max(0, strokeCount));
            for (int s = 0; s < strokeCount; s++) {
                int n = in.readInt();
                long t0 = in.readLong();
                ArrayList<TouchPoint> points = new ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    float x = in.readFloat();
                    float y = in.readFloat();
                    float pressure = in.readFloat();
                    float size = in.readFloat();
                    short tiltX = in.readShort();
                    short tiltY = in.readShort();
                    long t = t0 + in.readInt();
                    points.add(new TouchPoint(x, y, pressure, size, tiltX, tiltY, t));
                }
                if (!points.isEmpty()) {
                    strokes.add(InkRenderer.strokeFrom(points));
                }
            }
            return strokes;
        } finally {
            in.close();
        }
    }
}
