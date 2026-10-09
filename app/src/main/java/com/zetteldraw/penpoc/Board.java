package com.zetteldraw.penpoc;

import com.zetteldraw.penpoc.data.NotebookPaper;

import java.util.ArrayList;
import java.util.UUID;

/**
 * One slice of a notebook, or a legacy page. When {@link #paper} is set, the
 * strokes live on the paper and this object is only the slice frame.
 */
public final class Board {
    public final String id;
    public final long createdAt;
    public final ArrayList<InkRenderer.InkStroke> strokes = new ArrayList<>();
    /** Shared paper for every slice of the notebook. Null on a legacy page. */
    public NotebookPaper paper;
    /** Index of this slice on {@link #paper}, or -1 when this board owns {@link #strokes}. */
    public int sliceIndex = -1;
    public float paperOrigin;
    /** Slice height in pixels. 0 when this is a legacy page. */
    public int slicePx;
    /** Sheet this slice belongs to. Null on a legacy page. */
    public String sheetId;

    public Board(String id, long createdAt) {
        this.id = id;
        this.createdAt = createdAt;
    }

    public static Board blank() {
        return new Board(UUID.randomUUID().toString(), System.currentTimeMillis());
    }

    public boolean isBlank() {
        if (paper != null && sliceIndex >= 0) {
            if (sliceIndex >= paper.sliceCount()) {
                return true;
            }
            return !paper.containsInk(sliceIndex);
        }
        return strokes.isEmpty();
    }
}
