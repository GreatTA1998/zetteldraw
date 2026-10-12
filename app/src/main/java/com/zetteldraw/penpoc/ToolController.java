package com.zetteldraw.penpoc;

import android.view.View;
import android.widget.ImageButton;

/**
 * Single owner of the active drawing tool.
 *
 * <p>Chrome underline, {@link PageInkView#tool()}, and the TouchHelper stroke style
 * (via {@link PageInkView#setTool}) all change through {@link #setTool}. There is no
 * separate "button selected" flag that can drift from the ink tool enum.
 */
final class ToolController {
    interface Host {
        void styleToolButton(ImageButton button, boolean selected);

        /** Hints / lasso paste chrome after the tool enum and buttons are in sync. */
        void onAfterToolChanged(PageInkView.Tool tool);
    }

    private final PageInkView ink;
    private final Host host;
    private ImageButton penButton;
    private ImageButton eraserButton;
    private ImageButton lassoButton;
    private View toolbar;

    ToolController(PageInkView ink, Host host) {
        this.ink = ink;
        this.host = host;
    }

    void bind(ImageButton pen, ImageButton eraser, ImageButton lasso, View toolbar) {
        this.penButton = pen;
        this.eraserButton = eraser;
        this.lassoButton = lasso;
        this.toolbar = toolbar;
    }

    /** Authoritative selected tool — same value chrome and {@link PageInkView} share. */
    PageInkView.Tool tool() {
        return ink.tool();
    }

    /**
     * The only entry that changes the active tool, underline chrome, and
     * TouchHelper stroke style (via {@link PageInkView#setTool}). Do not style
     * tool buttons or call {@link PageInkView#setTool} from elsewhere.
     *
     * <p>Order: ink enum → chrome from that enum → host side effects → chrome
     * again so a hint/menu callback cannot leave the underline stale.
     */
    void setTool(PageInkView.Tool next) {
        ink.setTool(next);
        syncChrome();
        host.onAfterToolChanged(tool());
        syncChrome();
    }

    /**
     * Re-push the current tool to TouchHelper without changing selection.
     * Used when Pen is re-tapped so a stuck lasso dash cannot linger.
     */
    void reassert() {
        ink.setTool(tool());
        syncChrome();
    }

    /** Restyle buttons from {@link PageInkView#tool()} (e.g. after a test-only ink change). */
    void syncChrome() {
        PageInkView.Tool selected = ink.tool();
        if (penButton == null) {
            return;
        }
        host.styleToolButton(penButton, selected == PageInkView.Tool.PEN);
        host.styleToolButton(eraserButton, selected == PageInkView.Tool.ERASER);
        host.styleToolButton(lassoButton, selected == PageInkView.Tool.LASSO);
        // Style changes can briefly disable raw drawing; keep the toolbar pressable.
        penButton.setEnabled(true);
        penButton.setClickable(true);
        eraserButton.setEnabled(true);
        eraserButton.setClickable(true);
        lassoButton.setEnabled(true);
        lassoButton.setClickable(true);
        penButton.invalidate();
        eraserButton.invalidate();
        lassoButton.invalidate();
        if (toolbar != null) {
            toolbar.invalidate();
            toolbar.postInvalidate();
        }
    }
}
