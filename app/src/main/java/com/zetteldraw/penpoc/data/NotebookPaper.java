package com.zetteldraw.penpoc.data;

import com.onyx.android.sdk.data.note.TouchPoint;
import com.zetteldraw.penpoc.InkRenderer;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
    /**
     * Highest slice that holds ink, or -1. Updated while the log is applied
     * and when a stroke moves, so opening a notebook does not walk the pages.
     */
    private int lastInked = -1;

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
            for (InkRenderer.InkStroke stroke : page.strokes()) {
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
            origin += page.height();
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
     * The caller leaves hidden conflict copies out (and any legacy soft blank).
     */
    public static NotebookPaper migrate(List<SourcePage> pages, int newSliceHeight) {
        int frozen = newSliceHeight;
        if (pages != null) {
            Integer shared = null;
            boolean uniform = true;
            for (SourcePage page : pages) {
                if (page == null || page.height() <= 0) {
                    throw new IllegalArgumentException("page height");
                }
                if (shared == null) {
                    shared = page.height();
                } else if (shared != page.height()) {
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
                paper.heights.add(page.height());
                paper.sliceIds.add(page.id());
            }
        }
        paper.log = paper.headerBytes();
        if (pages == null) {
            return paper;
        }
        float origin = 0f;
        for (SourcePage page : pages) {
            for (InkRenderer.InkStroke stroke : page.strokes()) {
                paper.appendStroke(shiftY(stroke, origin));
            }
            origin += page.height();
        }
        return paper;
    }

    public static NotebookPaper replay(byte[] bytes) throws IOException {
        return replayOps(bytes, true);
    }

    /**
     * Slices-only paper: header + {@code EXTEND}/{@code TEAR} (skip stroke ops).
     * For tests / tooling. The shared UI open path waits for full {@link #replay}
     * (Ready) before showing any page chrome.
     */
    public static NotebookPaper replayStructure(byte[] bytes) throws IOException {
        return replayOps(bytes, false);
    }

    private static NotebookPaper replayOps(byte[] bytes, boolean withStrokes) throws IOException {
        if (bytes == null || bytes.length < 12
                || bytes[0] != MAGIC[0] || bytes[1] != MAGIC[1] || bytes[2] != MAGIC[2]) {
            throw new IOException("not a notebook ink log");
        }
        if (bytes[3] != VERSION) {
            throw new IOException("unsupported notebook log " + bytes[3]);
        }
        try {
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
                try {
                    if (withStrokes) {
                        paper.applyOp(op, ByteBuffer.wrap(bytes, start, len));
                    } else if (op == OP_EXTEND || op == OP_TEAR) {
                        paper.applyStructureOp(op, ByteBuffer.wrap(bytes, start, len));
                    }
                    // APPEND / DELETE / TRANSLATE skipped in structure pass.
                } catch (IOException | BufferUnderflowException | NegativeArraySizeException e) {
                    // Heal-in-place: keep the good prefix, drop this op and the rest.
                    // Do not fail the whole notebook — hundreds of pages may already be Ready.
                    break;
                }
                in.position(start + len);
                good = in.position();
            }
            if (withStrokes) {
                paper.log = Arrays.copyOf(bytes, good);
            } else {
                paper.log = paper.headerBytes();
            }
            return paper;
        } catch (BufferUnderflowException e) {
            // Header itself is unreadable — caller may quarantine + empty Ready.
            throw new IOException("corrupt notebook ink log", e);
        }
    }

    /** Structure-only EXTEND / TEAR — no stroke materialization. */
    private void applyStructureOp(int op, ByteBuffer in) throws IOException {
        switch (op) {
            case OP_EXTEND: {
                String id = readId(in);
                int height = in.getInt();
                if (height <= 0) {
                    throw new IOException("bad extend height");
                }
                heights.add(height);
                sliceIds.add(id);
                break;
            }
            case OP_TEAR: {
                int kind = in.get() & 0xff;
                int slice = in.getInt();
                readIds(in); // recorded new ids — unused without strokes
                if (slice < 0 || slice >= heights.size()) {
                    break;
                }
                if (kind == TEAR_DELETE) {
                    heights.remove(slice);
                    sliceIds.remove(slice);
                }
                break;
            }
            default:
                break;
        }
    }

    public byte[] bytes() {
        return Arrays.copyOf(log, log.length);
    }

    public int sliceCount() {
        return heights.size();
    }

    /** Highest slice index that holds ink, or -1 when the sheet is blank. O(1). */
    public int lastInkedSlice() {
        return lastInked;
    }

    public int heightAt(int index) {
        return heights.get(index);
    }

    public String sliceId(int index) {
        return sliceIds.get(index);
    }

    /**
     * Index of the slice with this id, or -1. Prefer this over a cached
     * {@code Board.sliceIndex}: after delete/reorder that field can point at
     * a neighbor, and Move would tear the wrong page.
     */
    public int indexOfSliceId(String id) {
        if (id == null) {
            return -1;
        }
        for (int i = 0; i < sliceIds.size(); i++) {
            if (id.equals(sliceIds.get(i))) {
                return i;
            }
        }
        return -1;
    }

    /** Stable fingerprint of every stroke id currently on this paper. */
    public Set<String> strokeIdSet() {
        HashSet<String> ids = new HashSet<>(strokes.size() * 2);
        for (InkRenderer.InkStroke stroke : strokes) {
            if (stroke != null && stroke.id != null) {
                ids.add(stroke.id);
            }
        }
        return ids;
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

    /**
     * True when a point of some stroke lies on this slice. The pad around a
     * stroke's bounds is for drawing and hit-testing; it is not ink.
     */
    public boolean containsInk(int slice) {
        if (slice < 0 || slice >= heights.size()) {
            return false;
        }
        float top = origin(slice);
        float bottom = top + heights.get(slice);
        for (InkRenderer.InkStroke stroke : strokes) {
            if (stroke.bounds == null || stroke.bounds.bottom < top || stroke.bounds.top >= bottom) {
                continue;
            }
            for (TouchPoint point : stroke.points) {
                if (point.y >= top && point.y < bottom) {
                    return true;
                }
            }
        }
        return false;
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

    /**
     * The slice that holds the lowest point. A point on a mark belongs to the
     * slice that starts there, the same rule as {@link #sliceIndexAt}. The pad
     * around the bounds is not a point, so it does not mark the next slice.
     */
    private void noteStroke(InkRenderer.InkStroke stroke) {
        if (stroke == null || stroke.points == null || stroke.points.isEmpty() || heights.isEmpty()) {
            return;
        }
        float y = maxY(stroke);
        if (y < 0f) {
            return;
        }
        int index = sliceIndexAt(y);
        if (index >= heights.size()) {
            index = heights.size() - 1;
        }
        if (index > lastInked) {
            lastInked = index;
        }
    }

    private void refreshLastInked() {
        lastInked = -1;
        for (InkRenderer.InkStroke stroke : strokes) {
            noteStroke(stroke);
        }
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

    /**
     * Pen-up. One stroke, however many slices it crosses. Widths stay as measured.
     * Does not grow new slices — pages are created only via {@link #appendBlankPage}.
     */
    public void appendStroke(InkRenderer.InkStroke stroke) {
        if (stroke == null || stroke.points.isEmpty()) {
            return;
        }
        strokes.add(stroke);
        noteStroke(stroke);
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
            refreshLastInked();
        }
    }

    /**
     * Lasso release. One shift for every selected stroke, ids unchanged.
     * Undo is {@code translate(ids, -dx, -dy)}. Does not grow new slices.
     */
    public void translate(Collection<String> ids, float dx, float dy) {
        if (ids == null || ids.isEmpty() || (dx == 0f && dy == 0f)) {
            return;
        }
        HashSet<String> want = new HashSet<>(ids);
        boolean any = false;
        for (InkRenderer.InkStroke stroke : strokes) {
            if (want.contains(stroke.id)) {
                any = true;
                break;
            }
        }
        if (!any) {
            return;
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
            refreshLastInked();
        }
    }

    /**
     * Explicit new page (Create new page). Appends one empty slice at frozen
     * {@link #sliceHeight}; returns its id. Never used for pen-up / soft tails.
     */
    public String appendBlankPage() {
        return appendBlankPage(UUID.randomUUID().toString());
    }

    /** Same as {@link #appendBlankPage()} with a caller-chosen id (tests / migrate). */
    public String appendBlankPage(String id) {
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException("slice id");
        }
        extend(id, sliceHeight);
        return id;
    }

    public Tear tearDelete(int slice) {
        return tear(slice, TEAR_DELETE);
    }

    public Tear tearWipe(int slice) {
        return tear(slice, TEAR_WIPE);
    }

    /**
     * Same-notebook reorder must not drop a page or a stroke id. Empty
     * trailing slices are real pages (Create new page) and must stay.
     *
     * <p>Used by same-paper {@link #tearMoveInsert} (Reorder / place-before spine).
     * Not on the undo stack — history is stroke edits, not slice order.
     */
    private void assertReorderPreserved(int slicesBefore, Set<String> strokeIdsBefore,
                                        String movedId) {
        if (indexOfSliceId(movedId) < 0) {
            throw new IllegalStateException("reorder dropped moved page " + movedId);
        }
        Set<String> after = strokeIdSet();
        if (!after.containsAll(strokeIdsBefore)) {
            HashSet<String> missing = new HashSet<>(strokeIdsBefore);
            missing.removeAll(after);
            throw new IllegalStateException("reorder dropped strokes " + missing);
        }
        if (heights.size() != slicesBefore) {
            throw new IllegalStateException("reorder changed slice count unexpectedly");
        }
    }

    /** Rebuild the log as header plus one APPEND per current stroke. */
    private void rewriteLogFromStrokes() {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try {
            raw.write(headerBytes());
            for (InkRenderer.InkStroke stroke : strokes) {
                raw.write(record(OP_APPEND, strokePayload(stroke)));
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        log = raw.toByteArray();
    }

    /** Insert an empty slice at {@code index} and shift ink at/below it down. */
    private void openGap(int index, int height, String id) {
        shiftStrokesFrom(origin(index) - 0.01f, height);
        heights.add(index, height);
        sliceIds.add(index, id);
    }

    /**
     * After a cut: open the gap and drop taken strokes shifted from
     * {@code oldOrigin}. One place spine for cross Move and same-nb Reorder.
     */
    private void installTaken(int insertAt, int height, String id, float oldOrigin, Tear cut) {
        openGap(insertAt, height, id);
        float shift = origin(insertAt) - oldOrigin;
        for (InkRenderer.InkStroke piece : cut.taken) {
            InkRenderer.InkStroke moved = shiftY(piece, shift);
            strokes.add(moved);
            noteStroke(moved);
        }
        rewriteLogFromStrokes();
        refreshLastInked();
    }

    /**
     * Tear {@code slice} out of this paper and insert it on
     * {@code destination} immediately after {@code afterSlice}.
     * {@code afterSlice == -1} inserts at the top (empty destination).
     * Keeps the source slice id so the page identity survives the move.
     * Same {@code destination} as {@code this} reorders in place (no undo tear).
     *
     * <p>Atomic for dense pages: destination is rewritten in one pass; on
     * failure both papers are left unchanged. This is the only cross-paper
     * Move primitive ({@link #tearMoveBefore} shares {@link #tearMoveInsert}).
     */
    public Tear tearMoveAfter(int slice, NotebookPaper destination, int afterSlice) {
        return tearMoveInsert(slice, destination, afterSlice + 1);
    }

    /**
     * Tear {@code slice} out of this paper and insert it on
     * {@code destination} immediately before {@code beforeSlice}.
     * {@code beforeSlice == 0} makes it the new first page.
     * Keeps the source slice id so the page identity survives the move.
     * Same {@code destination} as {@code this} reorders in place (no undo tear).
     * Bounds / null checks live in {@link #tearMoveInsert}.
     */
    public Tear tearMoveBefore(int slice, NotebookPaper destination, int beforeSlice) {
        return tearMoveInsert(slice, destination, beforeSlice);
    }

    /**
     * Tear {@code slice} and insert it at {@code insertAt} on {@code destination}.
     * {@code insertAt == destination.sliceCount()} appends as newest.
     *
     * <p>Same paper = in-place reorder (no undo tear; {@code insertAt} is pre-cut).
     * Cross paper = id-preserving Move. One cut → {@link #installTaken} spine.
     */
    private Tear tearMoveInsert(int slice, NotebookPaper destination, int insertAt) {
        if (destination == null) {
            throw new IllegalArgumentException("destination");
        }
        if (slice < 0 || slice >= heights.size()) {
            return new Tear();
        }
        if (insertAt < 0 || insertAt > destination.heights.size()) {
            return new Tear();
        }
        boolean same = destination == this;
        if (same) {
            // insertAt is pre-cut. Already there → no-op (no undo entry).
            if (insertAt == slice || insertAt == slice + 1) {
                return new Tear();
            }
            if (slice < insertAt) {
                insertAt--;
            }
        }
        String id = sliceIds.get(slice);
        int height = heights.get(slice);
        float oldOrigin = origin(slice);
        int slicesBefore = heights.size();
        Set<String> strokeIdsBefore = same ? strokeIdSet() : null;
        int destStrokeCountBefore = destination.strokes.size();
        int destSlicesBefore = destination.heights.size();
        // Same-paper: one snap is source+dest. Cross: snap dest; source rolls back via taken.
        byte[] destLogBefore = Arrays.copyOf(destination.log, destination.log.length);
        ArrayList<Integer> destHeightsBefore = new ArrayList<>(destination.heights);
        ArrayList<String> destIdsBefore = new ArrayList<>(destination.sliceIds);
        ArrayList<InkRenderer.InkStroke> destStrokesBefore = new ArrayList<>(destination.strokes);
        int destLastInkedBefore = destination.lastInked;
        Set<String> takenIds = new HashSet<>();
        Tear cut = cut(slice, null);
        for (InkRenderer.InkStroke piece : cut.taken) {
            if (piece != null && piece.id != null) {
                takenIds.add(piece.id);
            }
        }
        if (same) {
            closeGap(slice);
            if (insertAt < 0) {
                insertAt = 0;
            }
            if (insertAt > heights.size()) {
                insertAt = heights.size();
            }
        }
        try {
            destination.installTaken(insertAt, height, id, oldOrigin, cut);
            if (same) {
                assertReorderPreserved(slicesBefore, strokeIdsBefore, id);
            } else if (destination.indexOfSliceId(id) < 0) {
                throw new IllegalStateException("dest rewrite dropped moved page " + id);
            } else if (destination.strokes.size() < destStrokeCountBefore + takenIds.size()) {
                throw new IllegalStateException("dest rewrite shrank ink unexpectedly");
            } else if (!destination.strokeIdSet().containsAll(takenIds)) {
                throw new IllegalStateException("dest rewrite missing taken stroke ids");
            } else if (destination.heights.size() < destSlicesBefore + 1) {
                throw new IllegalStateException("dest rewrite lost a slice");
            }
        } catch (Throwable failed) {
            destination.log = destLogBefore;
            destination.heights.clear();
            destination.heights.addAll(destHeightsBefore);
            destination.sliceIds.clear();
            destination.sliceIds.addAll(destIdsBefore);
            destination.strokes.clear();
            destination.strokes.addAll(destStrokesBefore);
            destination.lastInked = destLastInkedBefore;
            if (!same) {
                // Prefer both notebooks intact over dropping a dense page mid-move.
                strokes.addAll(cut.taken);
                refreshLastInked();
            }
            if (failed instanceof RuntimeException) {
                throw (RuntimeException) failed;
            }
            throw new IllegalStateException(failed);
        }
        if (!same) {
            closeGap(slice);
            rememberTear(slice, TEAR_DELETE, cut);
            refreshLastInked();
            return cut;
        }
        // Reorder: no OP_TEAR — history is stroke edits, not slice order.
        return new Tear();
    }

    private Tear tear(int slice, int kind) {
        Tear cut = cut(slice, null);
        if (kind != TEAR_WIPE) {
            closeGap(slice);
        }
        rememberTear(slice, kind, cut);
        // Wipe keeps the slice (pages under it do not move). Empty trailing
        // slices are real Create-new-page blanks — never auto-trim.
        refreshLastInked();
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
     * Cut every stroke against the slice — one geometry spine for live tear
     * and log replay. Outside pieces stay. The first outside piece in draw
     * order keeps the id. Every other piece gets a new id: minted when
     * {@code recordedNewIds} is null (live), else consumed from that list
     * (replay). This is the only place a new id is born.
     *
     * <p>Taken strokes leave the paper either way; live also fills
     * {@link Tear#taken}/{@link Tear#stayed} for Move/Reorder callers.
     */
    private Tear cut(int slice, List<String> recordedNewIds) {
        float top = origin(slice);
        float bottom = top + heights.get(slice);
        boolean live = recordedNewIds == null;
        Tear result = new Tear();
        int cursor = 0;
        ArrayList<InkRenderer.InkStroke> next = new ArrayList<>();
        for (InkRenderer.InkStroke stroke : strokes) {
            if (stroke.bounds.bottom < top || stroke.bounds.top >= bottom) {
                next.add(stroke);
                continue;
            }
            if (whollyInside(stroke, top, bottom)) {
                if (live) {
                    result.taken.add(stroke);
                }
                continue;
            }
            List<Piece> pieces = split(stroke, top, bottom);
            boolean keptId = false;
            for (Piece piece : pieces) {
                String id;
                if (!piece.inside && !keptId) {
                    id = stroke.id;
                    keptId = true;
                } else if (live) {
                    id = UUID.randomUUID().toString();
                    result.newIds.add(id);
                } else {
                    id = cursor < recordedNewIds.size()
                            ? recordedNewIds.get(cursor++)
                            : UUID.randomUUID().toString();
                }
                InkRenderer.InkStroke born = InkRenderer.strokeWithWidths(
                        id, piece.points, piece.widths, stroke.color);
                if (piece.inside) {
                    if (live) {
                        result.taken.add(born);
                    }
                } else {
                    next.add(born);
                    if (live) {
                        result.stayed.add(born);
                    }
                }
            }
        }
        strokes.clear();
        strokes.addAll(next);
        return result;
    }

    /** True when every point of {@code stroke} lies in {@code [top, bottom)}. */
    private static boolean whollyInside(InkRenderer.InkStroke stroke, float top, float bottom) {
        for (TouchPoint point : stroke.points) {
            if (point.y < top || point.y >= bottom) {
                return false;
            }
        }
        return true;
    }

    private void closeGap(int slice) {
        float height = heights.get(slice);
        shiftStrokesFrom(origin(slice) + height - 0.01f, -height);
        heights.remove(slice);
        sliceIds.remove(slice);
    }

    /** Shift every stroke that touches at/below {@code y} by {@code dy}. */
    private void shiftStrokesFrom(float y, float dy) {
        for (int i = 0; i < strokes.size(); i++) {
            InkRenderer.InkStroke stroke = strokes.get(i);
            for (TouchPoint point : stroke.points) {
                if (point.y >= y) {
                    strokes.set(i, shiftY(stroke, dy));
                    break;
                }
            }
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
            case OP_APPEND: {
                InkRenderer.InkStroke added = readStroke(in);
                strokes.add(added);
                noteStroke(added);
                break;
            }
            case OP_DELETE:
                HashSet<String> gone = new HashSet<>(readIds(in));
                strokes.removeIf(stroke -> gone.contains(stroke.id));
                refreshLastInked();
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
                refreshLastInked();
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
                refreshLastInked();
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

    /** Replay path: same cut geometry; new ids are the ones the original tear wrote down. */
    private void applyTear(int slice, int kind, List<String> newIds) {
        cut(slice, newIds);
        if (kind != TEAR_WIPE) {
            closeGap(slice);
        }
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

    /**
     * Lowest ink y. Uses the cached bounds (pad stripped) so a lasso translate
     * does not walk every point of every stroke when refreshing {@link #lastInked}.
     */
    private static float maxY(InkRenderer.InkStroke stroke) {
        if (stroke == null || stroke.bounds == null) {
            return Float.NEGATIVE_INFINITY;
        }
        return stroke.bounds.bottom - (stroke.maxWidth * 0.5f + 2f);
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
            // Optional trailer kept for format compat; always black (color UI removed).
            out.writeInt(android.graphics.Color.BLACK);
            out.flush();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return raw.toByteArray();
    }

    /** Bytes per point in the stroke payload (x,y,pressure,size,tiltX,tiltY,dt,width). */
    private static final int STROKE_POINT_BYTES = 28;

    private static InkRenderer.InkStroke readStroke(ByteBuffer in) throws IOException {
        String id = readId(in);
        if (in.remaining() < 12) {
            throw new IOException("truncated stroke");
        }
        int n = in.getInt();
        // Refuse absurd counts before allocating — corrupt len can OOM the process.
        if (n < 0 || n > in.remaining() / STROKE_POINT_BYTES) {
            throw new IOException("bad stroke point count " + n);
        }
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
        if (in.remaining() >= 4) {
            in.getInt(); // skip legacy ARGB trailer
        }
        return InkRenderer.strokeWithWidths(id, points, widths, android.graphics.Color.BLACK);
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

    private static String readId(ByteBuffer in) throws IOException {
        if (in.remaining() < 2) {
            throw new IOException("truncated id");
        }
        int n = in.getShort() & 0xffff;
        if (n > in.remaining()) {
            throw new IOException("truncated id");
        }
        byte[] bytes = new byte[n];
        in.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static List<String> readIds(ByteBuffer in) throws IOException {
        if (in.remaining() < 4) {
            throw new IOException("truncated id list");
        }
        int n = in.getInt();
        if (n < 0 || n > 100_000) {
            throw new IOException("bad id count " + n);
        }
        ArrayList<String> ids = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            ids.add(readId(in));
        }
        return ids;
    }

    /** One page file's ink, already in that page's own coordinates, and the height it was drawn at. */
    public record SourcePage(String id, int height, List<InkRenderer.InkStroke> strokes) {
        public SourcePage {
            strokes = strokes == null ? List.of() : strokes;
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
