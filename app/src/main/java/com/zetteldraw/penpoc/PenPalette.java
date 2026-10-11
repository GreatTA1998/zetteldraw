package com.zetteldraw.penpoc;

import android.graphics.Color;

/**
 * Boox Notes–like pen swatches for the Go 7 Color II (Kaleido 3). Soft
 * “watercolor” pastels in the second row are ordinary ARGB colors drawn with
 * {@code STROKE_STYLE_FOUNTAIN}; the Onyx SDK has no separate watercolor brush
 * and no public stroke-stabilization setter.
 */
final class PenPalette {
    static final Swatch[] SWATCHES = {
            // Row 1: grayscale + labeled accents (WT / RD / GN / BL).
            new Swatch("Black", 0xFF000000, null),
            new Swatch("Dark gray", 0xFF3D3D3D, null),
            new Swatch("Gray", 0xFF7A7A7A, null),
            new Swatch("Light gray", 0xFFBDBDBD, null),
            new Swatch("White", 0xFFFFFFFF, "WT"),
            new Swatch("Red", 0xFFE53935, "RD"),
            new Swatch("Green", 0xFF43A047, "GN"),
            new Swatch("Blue", 0xFF1E88E5, "BL"),
            // Row 2: soft watercolors (Kaleido-friendly pastels + a few deeper inks).
            new Swatch("Mint", 0xFF9ED9C8, null),
            new Swatch("Blush", 0xFFE8B4C8, null),
            new Swatch("Sand", 0xFFE8D5A3, null),
            new Swatch("Lime", 0xFFC5DB8C, null),
            new Swatch("Olive", 0xFF5A6B3F, null),
            new Swatch("Plum", 0xFF8B6B9E, null),
            new Swatch("Slate", 0xFF6B8A9E, null),
            new Swatch("Clay", 0xFFC4785A, null),
    };

    static final int DEFAULT_COLOR = Color.BLACK;

    private PenPalette() {
    }

    static Swatch find(int color) {
        int want = color | 0xFF000000;
        for (Swatch swatch : SWATCHES) {
            if ((swatch.color | 0xFF000000) == want) {
                return swatch;
            }
        }
        return null;
    }

    static String labelFor(int color) {
        Swatch known = find(color);
        if (known != null) {
            return known.name + "(#" + String.format("%08X", color) + ")";
        }
        return "#" + String.format("%08X", color);
    }

    static final class Swatch {
        final String name;
        final int color;
        /** Short label drawn on the swatch (WT / RD / GN / BL), or null. */
        final String badge;

        Swatch(String name, int color, String badge) {
            this.name = name;
            this.color = color;
            this.badge = badge;
        }
    }
}
