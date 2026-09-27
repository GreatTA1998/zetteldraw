package com.zetteldraw.penpoc.data;

import com.onyx.android.sdk.data.note.TouchPoint;
import com.zetteldraw.penpoc.InkRenderer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
 *   per stroke: [v2+: i64 idMostSig, i64 idLeastSig] i32 pointCount, i64 firstTimestamp
 *     per point: f32 x, f32 y, f32 pressure, f32 size, i16 tiltX, i16 tiltY, i32 dtMillis
 * }
 * </pre>
 *
 * Widths are not stored; {@link InkRenderer#strokeFrom} recomputes them on load.
 * Version 1 files have no stroke ids; they get ids derived from the stroke's
 * index and points, so every device decoding the same file agrees on them.
 */
public final class InkCodec {
    public static final int VERSION = 2;
    private static final int VERSION_NO_IDS = 1;
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
                UUID id = uuidOf(stroke.id);
                out.writeLong(id.getMostSignificantBits());
                out.writeLong(id.getLeastSignificantBits());
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
        int version = data[3];
        if (version != VERSION && version != VERSION_NO_IDS) {
            throw new IOException("unsupported ink version " + version);
        }
        boolean hasIds = version >= VERSION;
        DataInputStream in = new DataInputStream(new InflaterInputStream(
                new ByteArrayInputStream(data, 4, data.length - 4)));
        try {
            int strokeCount = in.readInt();
            ArrayList<InkRenderer.InkStroke> strokes = new ArrayList<>(Math.max(0, strokeCount));
            for (int s = 0; s < strokeCount; s++) {
                String id = hasIds ? new UUID(in.readLong(), in.readLong()).toString() : null;
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
                    strokes.add(InkRenderer.strokeFrom(id != null ? id : legacyId(s, points), points));
                }
            }
            return strokes;
        } finally {
            in.close();
        }
    }

    static String legacyId(int index, List<TouchPoint> points) {
        ByteBuffer buffer = ByteBuffer.allocate(4 + points.size() * 16);
        buffer.putInt(index);
        for (TouchPoint p : points) {
            buffer.putFloat(p.x);
            buffer.putFloat(p.y);
            buffer.putLong(p.timestamp);
        }
        return UUID.nameUUIDFromBytes(buffer.array()).toString();
    }

    private static UUID uuidOf(String id) {
        try {
            return UUID.fromString(id);
        } catch (RuntimeException e) {
            return UUID.nameUUIDFromBytes(String.valueOf(id).getBytes(StandardCharsets.UTF_8));
        }
    }
}
