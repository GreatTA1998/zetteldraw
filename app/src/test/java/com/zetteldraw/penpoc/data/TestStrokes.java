package com.zetteldraw.penpoc.data;

import com.zetteldraw.penpoc.InkRenderer;

/** Public access to {@link TestInk} for tests outside this package. */
public final class TestStrokes {
    private TestStrokes() {
    }

    public static InkRenderer.InkStroke stroke(float x, float y) {
        return TestInk.stroke(x, y, 10);
    }
}
