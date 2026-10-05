package com.zetteldraw.penpoc.data;

import com.onyx.android.sdk.data.note.TouchPoint;
import com.zetteldraw.penpoc.InkRenderer;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/**
 * One notebook's paper. A stroke is points on that paper: y starts at the top
 * and runs down, and the stroke has an id and no page. Pages are slices.
 * The dashed line is a mark at a slice edge. It does not cut a stroke.
 *
 * <p>The file is an append-only log. Pen-up, erase and lasso append a record.
 * They do not rewrite earlier bytes. The only cut is {@link #tear}.
 */
public final class NotebookPaper {
    public static final String SCRATCHPAD_ID = UUID.nameUUIDFromBytes(
            "zetteldraw:sheet:scratchpad".getBytes(StandardCharsets.UTF_8)).toString();

    private static final byte[] MAGIC = {'Z', 'D', 'L'};
    private static final int VERSION = 1;
    private static final int OP_APPEND = 1;
    private static final int OP_DELETE = 2;
    private static final int OP_TRANSLATE = 3;
    private static final int OP_TEAR = 4;
    private static final int OP_EXTEND = 5;
    private static final int TEAR_DELETE = 1;
    private static final int TEAR_WIPE = 2;

    /** Height of every slice added after the ones migration measured. Frozen. */
    public final int sliceHeight;
    private final ArrayList<Integer> heights = new ArrayList<>();
    private final ArrayList<String> sliceIds = new ArrayList<>();
    private final ArrayList<InkRenderer.InkStroke> strokes = new ArrayList<>();
    /** Bytes of the log so far. Appending a record only adds to the end. */
    private byte[] log;

    private NotebookPaper(int sliceHeight, byte[] log) {
        if (sliceHeight <= 0) {
            throw new IllegalArgumentException("slice height " + sliceHeight);
        }
        this.sliceHeight = sliceHeight;
        this.log = log;
    }

    public static String sheetId(String notebookId) {
        return notebookId == null ? SCRATCHPAD_ID : notebookId;
    }

    /**
     * The height a page is shown at today: {@code pageHeight} for anything
     * drawn since v10, and the old full-window height when earlier ink runs
     * past that. Matches the screen rule so migration does not move a point.
     */
    public static int measuredHeight(long createdAt, List<InkRenderer.InkStroke> strokes,
                                     int pageHeight, int legacyPageHeight, long shortPagesSince) {
        if (createdAt >= shortPagesSince || pageHeight <= 0) {
            return Math.max(1, pageHeight);
        }
        float bottom = 0f;
        if (strokes != null) {
            for (InkRenderer.InkStroke stroke : strokes) {
                bottom = Math.max(bottom, stroke.bounds.bottom + stroke.maxWidth);
            }
        }
        if (bottom <= pageHeight) {
            return pageHeight;
        }
        return Math.max(legacyPageHeight, (int) Math.ceil(bottom));
    }

    /** True when every migrated point still sits at {@code origin + local y}. */
    public static boolean readsBack(List<SourcePage> pages, NotebookPaper paper) {
        if (paper == null) {
            return false;
        }
        int seen = 0;
        float origin = 0f;
        for (SourcePage page : pages) {
            for (InkRenderer.InkStroke stroke : page.strokes) {
                InkRenderer.InkStroke got = paper.stroke(stroke.id);
                if (got == null || got.points.size() != stroke.points.size()) {
                    return false;
                }
                for (int i = 0; i < stroke.points.size(); i++) {
                    TouchPoint want = stroke.points.get(i);
                    TouchPoint have = got.points.get(i);
                    if (have.x != want.x || have.y != want.y + origin) {
                        return false;
                    }
                }
                seen++;
            }
            origin += page.height;
        }
        return seen == paper.strokes().size() && paper.sliceCount() == pages.size();
    }

    /** A new notebook: one frozen slice height, no ink. */
    public static NotebookPaper empty(int sliceHeight) {
        NotebookPaper paper = new NotebookPaper(sliceHeight, new byte[0]);
        paper.log = paper.headerBytes();
        return paper;
    }

    /**
     * Build a log from page-local strokes. Each page contributes its real
     * height, so a 1420 page followed by a 1680 page does not move a point.
     * The caller leaves the trailing blank and hidden conflict copies out.
     */
    public static NotebookPaper migrate(List<SourcePage> pages, int newSliceHeight) {
        int frozen = newSliceHeight;
        if (pages != null) {
            Integer shared = null;
            boolean uniform = true;
            for (SourcePage page : pages) {
                if (page == null || page.height <= 0) {
                    throw new IllegalArgumentException("page height");
                }
                if (shared == null) {
                    shared = page.height;
                } else if (shared != page.height) {
                    uniform = false;
                }
            }
            if (uniform && shared != null) {
                frozen = shared;
            }
        }
        NotebookPaper paper = new NotebookPaper(frozen, new byte[0]);
        if (pages != null) {
            for (SourcePage page : pages) {
                paper.heights.add(page.height);
                paper.sliceIds.add(page.id);
            }
        }
        paper.log = paper.headerBytes();
        if (pages == null) {
            return paper;
        }
        float origin = 0f;
        for (SourcePage page : pages) {
            for (InkRenderer.InkStroke stroke : page.strokes) {
                paper.appendStroke(shiftY(stroke, origin));
            }
            origin += page.height;
        }
        return paper;
    }

    public static NotebookPaper replay(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length < 12
                || bytes[0] != MAGIC[0] || bytes[1] != MAGIC[1] || bytes[2] != MAGIC[2]) {
            throw new IOException("not a notebook ink log");
        }
        if (bytes[3] != VERSION) {
            throw new IOException("unsupported notebook log " + bytes[3]);
        }
        ByteBuffer in = ByteBuffer.wrap(bytes);
        in.position(4);
        int sliceHeight = in.getInt();
        int sliceCount = in.getInt();
        if (sliceCount < 0 || sliceCount > 100_000) {
            throw new IOException("bad slice count " + sliceCount);
        }
        NotebookPaper paper = new NotebookPaper(sliceHeight, bytes);
        for (int i = 0; i < sliceCount; i++) {
            int height = in.getInt();
            if (height <= 0) {
                throw new IOException("bad slice height");
            }
            paper.heights.add(height);
            paper.sliceIds.add(readId(in));
        }
        int good = in.position();
        while (in.remaining() > 0) {
            if (in.remaining() < 5) {
                // A crash mid-append leaves a short tail. Earlier records stay whole.
                break;
            }
            int op = in.get() & 0xff;
            int len = in.getInt();
            if (len < 0 || len > in.remaining()) {
                break;
            }
            int start = in.position();
            paper.applyOp(op, ByteBuffer.wrap(bytes, start, len));
            in.position(start + len);
            good = in.position();
        }
        paper.log = Arrays.copyOf(bytes, good);
        return paper;
    }

    /**
     * A pull installs one whole log. It does not look at the strokes already
     * here, so it cannot leave half of one of them behind.
     */
    public static NotebookPaper replace(byte[] incoming) throws IOException {
        return replay(incoming);
    }

    public byte[] bytes() {
        return Arrays.copyOf(log, log.length);
    }

    public int sliceCount() {
        return heights.size();
    }

    public int heightAt(int index) {
        return heights.get(index);
    }

    public String sliceId(int index) {
        return sliceIds.get(index);
    }

    public float origin(int index) {
        float y = 0f;
        int n = Math.min(index, heights.size());
        for (int i = 0; i < n; i++) {
            y += heights.get(i);
        }
        if (index > heights.size()) {
            y += (float) (index - heights.size()) * sliceHeight;
        }
        return y;
    }

    public List<InkRenderer.InkStroke> strokes() {
        return strokes;
    }

    public InkRenderer.InkStroke stroke(String id) {
        int index = indexOf(id);
        return index < 0 ? null : strokes.get(index);
    }

    /** Strokes whose bounds meet this slice. The same object, not a copy. */
    public List<InkRenderer.InkStroke> touching(int slice) {
        ArrayList<InkRenderer.InkStroke> hit = new ArrayList<>();
        if (slice < 0) {
            return hit;
        }
        float top = origin(slice);
        float bottom = top + (slice < heights.size() ? heights.get(slice) : sliceHeight);
        for (InkRenderer.InkStroke stroke : strokes) {
            if (stroke.bounds.bottom >= top && stroke.bounds.top < bottom) {
                hit.add(stroke);
            }
        }
        return hit;
    }

    public int sliceIndexAt(float paperY) {
        if (paperY < 0f) {
            return -1;
        }
        float y = 0f;
        for (int i = 0; i < heights.size(); i++) {
            float next = y + heights.get(i);
            if (paperY < next) {
                return i;
            }
            y = next;
        }
        if (sliceHeight <= 0) {
            return -1;
        }
        return heights.size() + (int) ((paperY - y) / sliceHeight);
    }

    /** Pen-up. One stroke, however many slices it crosses. Widths stay as measured. */
    public void appendStroke(InkRenderer.InkStroke stroke) {
        appendStroke(stroke, null);
    }

    /**
     * {@code adoptId} is the unsaved trailing blank's id, used for the first
     * slice this stroke grows into so that slice keeps the id the screen already has.
     */
    public void appendStroke(InkRenderer.InkStroke stroke, String adoptId) {
        if (stroke == null || stroke.points.isEmpty()) {
            return;
        }
        float bottom = maxY(stroke);
        while (bottom >= origin(heights.size())) {
            String id = adoptId != null ? adoptId : UUID.randomUUID().toString();
            adoptId = null;
            extend(id, sliceHeight);
        }
        strokes.add(stroke);
        append(record(OP_APPEND, strokePayload(stroke)));
    }

    /** Eraser: the whole stroke, on every slice it crosses. */
    public void deleteIds(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        ArrayList<String> gone = new ArrayList<>();
        HashSet<String> want = new HashSet<>(ids);
        strokes.removeIf(stroke -> {
            if (want.contains(stroke.id)) {
                gone.add(stroke.id);
                return true;
            }
            return false;
        });
        if (!gone.isEmpty()) {
            append(record(OP_DELETE, idsPayload(gone)));
        }
    }

    /**
     * Lasso release. One shift for every selected stroke, ids unchanged.
     * Undo is {@code translate(ids, -dx, -dy)}.
     */
    public void translate(Collection<String> ids, float dx, float dy) {
        translate(ids, dx, dy, null);
    }

    /**
     * {@code adoptId} names the first slice this drag grows into, so the
     * trailing blank the screen already shows keeps its id.
     */
    public void translate(Collection<String> ids, float dx, float dy, String adoptId) {
        if (ids == null || ids.isEmpty() || (dx == 0f && dy == 0f)) {
            return;
        }
        HashSet<String> want = new HashSet<>(ids);
        float bottom = 0f;
        boolean any = false;
        for (InkRenderer.InkStroke stroke : strokes) {
            if (want.contains(stroke.id)) {
                any = true;
                bottom = Math.max(bottom, maxY(stroke) + dy);
            }
        }
        if (!any) {
            return;
        }
        while (bottom >= origin(heights.size())) {
            String id = adoptId != null ? adoptId : UUID.randomUUID().toString();
            adoptId = null;
            extend(id, sliceHeight);
        }
        ArrayList<String> moved = new ArrayList<>();
        for (int i = 0; i < strokes.size(); i++) {
            InkRenderer.InkStroke stroke = strokes.get(i);
            if (want.contains(stroke.id)) {
                strokes.set(i, stroke.translated(dx, dy));
                moved.add(stroke.id);
            }
        }
        if (!moved.isEmpty()) {
            ByteArrayOutputStream raw = new ByteArrayOutputStream();
            try {
                DataOutputStream out = new DataOutputStream(raw);
                writeIds(out, moved);
                out.writeFloat(dx);
                out.writeFloat(dy);
                out.flush();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            append(record(OP_TRANSLATE, raw.toByteArray()));
        }
    }

    public Tear tearDelete(int slice) {
        return tear(slice, TEAR_DELETE);
    }

    public Tear tearWipe(int slice) {
        return tear(slice, TEAR_WIPE);
    }

    /**
     * The inside of the slice, already shifted so it lands as one piece at
     * the bottom of {@code destination}. The source then closes the gap.
     */
    public Tear tearMove(int slice, NotebookPaper destination) {
        if (destination == null) {
            throw new IllegalArgumentException("destination");
        }
        Tear cut = cut(slice, false);
        float shift = destination.origin(destination.sliceCount()) - origin(slice);
        for (InkRenderer.InkStroke piece : cut.taken) {
            destination.appendStroke(shiftY(piece, shift));
        }
        closeGap(slice);
        rememberTear(slice, TEAR_DELETE, cut);
        trimTail();
        return cut;
    }

    private Tear tear(int slice, int kind) {
        boolean wipe = kind == TEAR_WIPE;
        Tear cut = cut(slice, wipe);
        if (!wipe) {
            closeGap(slice);
        }
        rememberTear(slice, kind, cut);
        // A wiped slice stays, so pages under it do not move. An empty tail does not.
        trimTail();
        return cut;
    }

    private void rememberTear(int slice, int kind, Tear cut) {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try {
            DataOutputStream out = new DataOutputStream(raw);
            out.writeByte(kind);
            out.writeInt(slice);
            writeIds(out, cut.newIds);
            out.flush();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        append(record(OP_TEAR, raw.toByteArray()));
    }

    /**
     * Cut every stroke against the slice. Outside pieces stay. The first
     * outside piece in draw order keeps the id. Every other piece gets a new
     * id. This is the only place a new id is born.
     */
    private Tear cut(int slice, boolean wipe) {
        float top = origin(slice);
        float bottom = top + heights.get(slice);
        Tear result = new Tear();
        ArrayList<InkRenderer.InkStroke> next = new ArrayList<>();
        for (InkRenderer.InkStroke stroke : strokes) {
            if (stroke.bounds.bottom < top || stroke.bounds.top >= bottom) {
                next.add(stroke);
                continue;
            }
            boolean whollyInside = true;
            for (TouchPoint point : stroke.points) {
                if (point.y < top || point.y >= bottom) {
                    whollyInside = false;
                    break;
                }
            }
            if (whollyInside) {
                result.taken.add(stroke);
                continue;
            }
            List<Piece> pieces = split(stroke, top, bottom);
            boolean keptId = false;
            for (Piece piece : pieces) {
                boolean inside = piece.inside;
                String id;
                if (!inside && !keptId) {
                    id = stroke.id;
                    keptId = true;
                } else {
                    id = UUID.randomUUID().toString();
                    result.newIds.add(id);
                }
                InkRenderer.InkStroke born = InkRenderer.strokeWithWidths(id, piece.points, piece.widths);
                if (inside) {
                    result.taken.add(born);
                } else {
                    next.add(born);
                    result.stayed.add(born);
                }
            }
        }
        strokes.clear();
        strokes.addAll(next);
        if (!wipe) {
            // taken strokes leave with the slice; they are not left on the paper
        }
        return result;
    }

    private void closeGap(int slice) {
        float top = origin(slice);
        float height = heights.get(slice);
        float bottom = top + height;
        for (int i = 0; i < strokes.size(); i++) {
            InkRenderer.InkStroke stroke = strokes.get(i);
            boolean below = false;
            for (TouchPoint point : stroke.points) {
                if (point.y >= bottom - 0.01f) {
                    below = true;
                    break;
                }
            }
            if (below) {
                strokes.set(i, shiftY(stroke, -height));
            }
        }
        heights.remove(slice);
        sliceIds.remove(slice);
    }

    private void trimTail() {
        while (!heights.isEmpty() && touching(heights.size() - 1).isEmpty()) {
            heights.remove(heights.size() - 1);
            sliceIds.remove(sliceIds.size() - 1);
        }
    }

    private void extend(String id, int height) {
        heights.add(height);
        sliceIds.add(id);
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try {
            DataOutputStream out = new DataOutputStream(raw);
            writeId(out, id);
            out.writeInt(height);
            out.flush();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        append(record(OP_EXTEND, raw.toByteArray()));
    }

    private void applyOp(int op, ByteBuffer in) throws IOException {
        switch (op) {
            case OP_APPEND:
                strokes.add(readStroke(in));
                break;
            case OP_DELETE:
                HashSet<String> gone = new HashSet<>(readIds(in));
                strokes.removeIf(stroke -> gone.contains(stroke.id));
                break;
            case OP_TRANSLATE: {
                List<String> ids = readIds(in);
                float dx = in.getFloat();
                float dy = in.getFloat();
                HashSet<String> want = new HashSet<>(ids);
                for (int i = 0; i < strokes.size(); i++) {
                    if (want.contains(strokes.get(i).id)) {
                        strokes.set(i, strokes.get(i).translated(dx, dy));
                    }
                }
                break;
            }
            case OP_TEAR: {
                int kind = in.get() & 0xff;
                int slice = in.getInt();
                List<String> newIds = readIds(in);
                if (slice < 0 || slice >= heights.size()) {
                    break;
                }
                applyTear(slice, kind, newIds);
                break;
            }
            case OP_EXTEND: {
                String id = readId(in);
                int height = in.getInt();
                heights.add(height);
                sliceIds.add(id);
                break;
            }
            default:
                throw new IOException("bad log op " + op);
        }
    }

    /** Replay path: the new ids are the ones the original tear wrote down. */
    private void applyTear(int slice, int kind, List<String> newIds) {
        float top = origin(slice);
        float bottom = top + heights.get(slice);
        int cursor = 0;
        ArrayList<InkRenderer.InkStroke> next = new ArrayList<>();
        for (InkRenderer.InkStroke stroke : strokes) {
            if (stroke.bounds.bottom < top || stroke.bounds.top >= bottom) {
                next.add(stroke);
                continue;
            }
            boolean whollyInside = true;
            for (TouchPoint point : stroke.points) {
                if (point.y < top || point.y >= bottom) {
                    whollyInside = false;
                    break;
                }
            }
            if (whollyInside) {
                continue;
            }
            List<Piece> pieces = split(stroke, top, bottom);
            boolean keptId = false;
            for (Piece piece : pieces) {
                String id;
                if (!piece.inside && !keptId) {
                    id = stroke.id;
                    keptId = true;
                } else {
                    id = cursor < newIds.size() ? newIds.get(cursor++) : UUID.randomUUID().toString();
                }
                InkRenderer.InkStroke born = InkRenderer.strokeWithWidths(id, piece.points, piece.widths);
                if (!piece.inside) {
                    next.add(born);
                }
            }
        }
        strokes.clear();
        strokes.addAll(next);
        if (kind != TEAR_WIPE) {
            closeGap(slice);
        }
        trimTail();
    }

    private static List<Piece> split(InkRenderer.InkStroke stroke, float top, float bottom) {
        ArrayList<TouchPoint> points = stroke.points;
        float[] widths = stroke.widthCopy();
        ArrayList<Piece> pieces = new ArrayList<>();
        ArrayList<TouchPoint> cur = new ArrayList<>();
        ArrayList<Float> curW = new ArrayList<>();
        int side = sideOf(points.get(0).y, top, bottom);
        cur.add(new TouchPoint(points.get(0)));
        curW.add(widths[0]);
        for (int i = 1; i < points.size(); i++) {
            TouchPoint point = points.get(i);
            int next = sideOf(point.y, top, bottom);
            if (next == side) {
                cur.add(new TouchPoint(point));
                curW.add(widths[i]);
                continue;
            }
            float[] edges = edgesBetween(side, next, top, bottom);
            TouchPoint prev = points.get(i - 1);
            float prevW = widths[i - 1];
            int walk = side;
            for (float edge : edges) {
                float span = point.y - prev.y;
                float t = span == 0f ? 0f : (edge - prev.y) / span;
                Lerped at = lerp(prev, point, prevW, widths[i], t);
                cur.add(at.point);
                curW.add(at.width);
                pieces.add(finish(cur, curW, walk == 0));
                cur = new ArrayList<>();
                curW = new ArrayList<>();
                cur.add(new TouchPoint(at.point));
                curW.add(at.width);
                walk = walk + (next > side ? 1 : -1);
            }
            cur.add(new TouchPoint(point));
            curW.add(widths[i]);
            side = next;
        }
        if (!cur.isEmpty()) {
            pieces.add(finish(cur, curW, side == 0));
        }
        return pieces;
    }

    private static float maxY(InkRenderer.InkStroke stroke) {
        float y = Float.NEGATIVE_INFINITY;
        for (TouchPoint point : stroke.points) {
            if (point.y > y) {
                y = point.y;
            }
        }
        return y;
    }

    private static Piece finish(ArrayList<TouchPoint> points, ArrayList<Float> widths, boolean inside) {
        float[] w = new float[widths.size()];
        for (int i = 0; i < w.length; i++) {
            w[i] = widths.get(i);
        }
        Piece piece = new Piece();
        piece.points = points;
        piece.widths = w;
        piece.inside = inside;
        return piece;
    }

    private static int sideOf(float y, float top, float bottom) {
        if (y < top) {
            return -1;
        }
        if (y >= bottom) {
            return 1;
        }
        return 0;
    }

    /** Edges crossed going from {@code from} to {@code to}, top before bottom when descending is reversed. */
    private static float[] edgesBetween(int from, int to, float top, float bottom) {
        if (from == to) {
            return new float[0];
        }
        if (from < 0 && to > 0) {
            return new float[]{top, bottom};
        }
        if (from > 0 && to < 0) {
            return new float[]{bottom, top};
        }
        if ((from < 0 && to == 0) || (from == 0 && to < 0)) {
            return new float[]{top};
        }
        return new float[]{bottom};
    }

    private static Lerped lerp(TouchPoint a, TouchPoint b, float wa, float wb, float t) {
        if (t < 0f) {
            t = 0f;
        } else if (t > 1f) {
            t = 1f;
        }
        Lerped out = new Lerped();
        out.point = new TouchPoint(
                a.x + (b.x - a.x) * t,
                a.y + (b.y - a.y) * t,
                a.pressure + (b.pressure - a.pressure) * t,
                a.size + (b.size - a.size) * t,
                (short) Math.round(a.tiltX + (b.tiltX - a.tiltX) * t),
                (short) Math.round(a.tiltY + (b.tiltY - a.tiltY) * t),
                a.timestamp + (long) ((b.timestamp - a.timestamp) * t));
        out.width = wa + (wb - wa) * t;
        return out;
    }

    private static InkRenderer.InkStroke shiftY(InkRenderer.InkStroke stroke, float dy) {
        return dy == 0f ? stroke : stroke.translated(0f, dy);
    }

    private int indexOf(String id) {
        for (int i = 0; i < strokes.size(); i++) {
            if (strokes.get(i).id.equals(id)) {
                return i;
            }
        }
        return -1;
    }

    private byte[] headerBytes() {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try {
            DataOutputStream out = new DataOutputStream(raw);
            out.write(MAGIC);
            out.writeByte(VERSION);
            out.writeInt(sliceHeight);
            out.writeInt(heights.size());
            for (int i = 0; i < heights.size(); i++) {
                out.writeInt(heights.get(i));
                writeId(out, sliceIds.get(i));
            }
            out.flush();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return raw.toByteArray();
    }

    private void append(byte[] record) {
        byte[] next = Arrays.copyOf(log, log.length + record.length);
        System.arraycopy(record, 0, next, log.length, record.length);
        log = next;
    }

    private static byte[] record(int op, byte[] payload) {
        ByteArrayOutputStream raw = new ByteArrayOutputStream(5 + payload.length);
        try {
            DataOutputStream out = new DataOutputStream(raw);
            out.writeByte(op);
            out.writeInt(payload.length);
            out.write(payload);
            out.flush();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return raw.toByteArray();
    }

    private static byte[] strokePayload(InkRenderer.InkStroke stroke) {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try {
            DataOutputStream out = new DataOutputStream(raw);
            writeId(out, stroke.id);
            List<TouchPoint> points = stroke.points;
            float[] widths = stroke.widthCopy();
            out.writeInt(points.size());
            long t0 = points.get(0).timestamp;
            out.writeLong(t0);
            for (int i = 0; i < points.size(); i++) {
                TouchPoint p = points.get(i);
                out.writeFloat(p.x);
                out.writeFloat(p.y);
                out.writeFloat(p.pressure);
                out.writeFloat(p.size);
                out.writeShort(p.tiltX);
                out.writeShort(p.tiltY);
                out.writeInt((int) (p.timestamp - t0));
                out.writeFloat(widths[i]);
            }
            out.flush();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return raw.toByteArray();
    }

    private static InkRenderer.InkStroke readStroke(ByteBuffer in) {
        String id = readId(in);
        int n = in.getInt();
        long t0 = in.getLong();
        ArrayList<TouchPoint> points = new ArrayList<>(n);
        float[] widths = new float[n];
        for (int i = 0; i < n; i++) {
            float x = in.getFloat();
            float y = in.getFloat();
            float pressure = in.getFloat();
            float size = in.getFloat();
            short tiltX = in.getShort();
            short tiltY = in.getShort();
            long t = t0 + in.getInt();
            widths[i] = in.getFloat();
            points.add(new TouchPoint(x, y, pressure, size, tiltX, tiltY, t));
        }
        return InkRenderer.strokeWithWidths(id, points, widths);
    }

    private static byte[] idsPayload(List<String> ids) {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try {
            DataOutputStream out = new DataOutputStream(raw);
            writeIds(out, ids);
            out.flush();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return raw.toByteArray();
    }

    private static void writeIds(DataOutputStream out, List<String> ids) throws IOException {
        out.writeInt(ids.size());
        for (String id : ids) {
            writeId(out, id);
        }
    }

    private static void writeId(DataOutputStream out, String id) throws IOException {
        byte[] bytes = id.getBytes(StandardCharsets.UTF_8);
        out.writeShort(bytes.length);
        out.write(bytes);
    }

    private static String readId(ByteBuffer in) {
        int n = in.getShort() & 0xffff;
        byte[] bytes = new byte[n];
        in.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static List<String> readIds(ByteBuffer in) {
        int n = in.getInt();
        ArrayList<String> ids = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            ids.add(readId(in));
        }
        return ids;
    }

    /** One page file's ink, already in that page's own coordinates, and the height it was drawn at. */
    public static final class SourcePage {
        public final String id;
        public final int height;
        public final List<InkRenderer.InkStroke> strokes;

        public SourcePage(String id, int height, List<InkRenderer.InkStroke> strokes) {
            this.id = id;
            this.height = height;
            this.strokes = strokes == null ? List.of() : strokes;
        }
    }

    /** What a tear did. {@link #newIds} is empty unless a stroke crossed the edge. */
    public static final class Tear {
        public final ArrayList<InkRenderer.InkStroke> taken = new ArrayList<>();
        public final ArrayList<InkRenderer.InkStroke> stayed = new ArrayList<>();
        public final ArrayList<String> newIds = new ArrayList<>();
    }

    private static final class Piece {
        ArrayList<TouchPoint> points;
        float[] widths;
        boolean inside;
    }

    private static final class Lerped {
        TouchPoint point;
        float width;
    }
}
