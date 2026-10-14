package com.zetteldraw.penpoc;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.DashPathEffect;
import android.graphics.Insets;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.text.InputType;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.zetteldraw.penpoc.data.BoardRepository;
import com.zetteldraw.penpoc.data.RoomBoardRepository;
import com.zetteldraw.penpoc.data.ZettelData;
import com.zetteldraw.penpoc.data.db.ZettelDatabase;
import com.zetteldraw.penpoc.sync.GoogleAuth;
import com.zetteldraw.penpoc.sync.SyncClient;
import com.zetteldraw.penpoc.sync.SyncConfig;
import com.zetteldraw.penpoc.sync.SyncEngine;
import com.zetteldraw.penpoc.sync.SyncScheduler;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Top bar: Scratchpad | top-level notebooks | +. A selected notebook with
 * children opens another bar of only those children. Each collection is a
 * continuous vertical scroll of its own pages.
 */
public final class CanvasActivity extends Activity {
    private static final long SCROLL_SETTLE_MS = 160;
    private static final String SCRATCHPAD = "scratchpad";
    private static final String PREFS = "canvas";
    /** Pages created before this device first ran v10 may keep the old full-window height. */
    private static final String SHORT_PAGES_SINCE = "short_pages_since";
    /**
     * Per-collection vertical scroll (prefs key {@code scroll_} + id). Survives
     * process death. First open (no key) still jumps to last page / last inked.
     * Fingerprint keys ({@code scrollPages_} / {@code scrollPage_}) must match
     * the Ready sheet on restore. See notebook-navigation.md: Ready-gate;
     * remember via {@link #effectiveScrollY()}, never a ScrollView that has not
     * yet applied {@link #pendingScrollY}.
     */
    private static final String PREF_SCROLL_PREFIX = "scroll_";
    private static final String PREF_SCROLL_PAGES_PREFIX = "scrollPages_";
    private static final String PREF_SCROLL_PAGE_PREFIX = "scrollPage_";

    private BoardRepository repository;
    private String collectionId = SCRATCHPAD;
    /** Last opened notebook; null until one is chosen or when none exist. */
    private String notebookId;
    /** Scroll Y remembered per Scratchpad / notebook id for this process. */
    private final HashMap<String, Integer> scrollByCollection = new HashMap<>();

    private FrameLayout root;
    private FrameLayout drawingArea;
    /** In-window menu or form (Move, notebook menu, name, delete confirm). */
    private FrameLayout overlay;
    /** Blocks double-tap Prepare transfer while a pass is already running. */
    private final AtomicBoolean prepareTransferInFlight = new AtomicBoolean(false);
    /** Bumped each time Settings opens so a stale Prepare callback cannot mutate old views. */
    private int settingsGeneration;
    private LinearLayout topBar;
    /**
     * Bottom strip for Link / Reorder. Floated on {@link #root},
     * not parented into the notebook bars, so it stays separate from the top nav.
     * Move and Place under use the shared notebook hierarchy menu instead.
     */
    private LinearLayout modeBar;
    private TextView modeText;
    private Button modeConfirm;
    /**
     * Notes-like floating pill above the lasso selection (Cut / Copy / Delete,
     * optional Paste). Parent is {@link #root} (same overlay layer as
     * {@link #modeBar}) so it paints above the Onyx SurfaceView.
     */
    private LinearLayout lassoMenu;
    private Button lassoCutButton;
    private Button lassoCopyButton;
    private Button lassoDeleteButton;
    private Button lassoPasteButton;
    /** Page the Link control was pressed on. Null when linking is closed. */
    private String linkSourceId;
    /** "notebook k" captured from the slot on screen. Not looked up again. */
    private String linkSourceRef;
    /** Page the Reorder control was pressed on. Null when reorder is closed. */
    private String reorderSourceId;
    private String reorderSourceRef;
    /** Notebook id of the reorder source ({@code null} = Scratchpad). */
    private String reorderSourceNotebookId;
    private View toolbar;
    private PageInkView inkView;
    private PageScroller scroller;
    private LinearLayout pageColumn;
    private HorizontalScrollView notebookScroll;
    private LinearLayout notebookStrip;
    private ImageButton scratchpadTab;
    private Button addButton;
    private ImageButton penButton;
    private ImageButton eraserButton;
    private ImageButton lassoButton;
    private ToolController tools;
    private ImageButton settingsButton;
    private ImageButton undoButton;
    private ImageButton redoButton;
    private TextView toolHint;
    private final Runnable clearHint = () -> toolHint.setText("");
    private final ArrayList<NotebookTab> notebookTabs = new ArrayList<>();
    private final ArrayList<PageSlot> slots = new ArrayList<>();
    /** Top of each slot, plus the column bottom. Zero stride means the heights differ. */
    private int[] slotTops = new int[0];
    private int slotStride;

    /** Height of a page: the drawing area minus the gap, so a whole page and its controls fit on screen. */
    private volatile int pageHeight;
    /** Full-window page height of v4–v9; old pages whose ink reaches below {@link #pageHeight} keep it. */
    private volatile int legacyPageHeight;
    private long shortPagesSince;
    private int pageGap;
    private boolean scrolling;
    private boolean pagesLoaded;
    private int pendingScrollY = -1;
    private static final long FIRST_LOAD_DEADLINE_MS = 1_000;
    private static final long LOAD_DEADLINE_MAX_MS = 8_000;
    private static final String TAG = "zd-canvas";
    /** Times a drawing-area resize threw the page list away. Link must not move this. */
    static final java.util.concurrent.atomic.AtomicInteger pageReflows = new java.util.concurrent.atomic.AtomicInteger();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable remoteChanged = this::onRemoteChange;
    /** The page list on screen, for re-laying it out when the page height changes. */
    private List<Board> currentPages;
    /** A loaded list waiting for the drawing area to have a size. */
    private Loaded awaitingLayout;
    private Integer pendingKeepScroll;
    private List<BoardRepository.NotebookInfo> shownNotebooks;
    /** Selected notebook and its ancestors, root first. Empty on the Scratchpad. */
    private final ArrayList<String> trail = new ArrayList<>();
    /** One bar per selected notebook that has children, under the top bar. */
    private LinearLayout lowerBars;
    private final ArrayList<View> childBars = new ArrayList<>();
    /** Bumped by every page-list load; a background load whose number is stale is dropped. */
    private int loadGeneration;
    private int shownGeneration;
    private boolean firstListShown;
    private final Runnable scrollSettled = this::onScrollSettled;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LaunchLog.mark("screen created" + (savedInstanceState != null ? " (restored)" : ""));
        UiClick.warm(this);
        InkRenderer.applyBaseWidthMm(getResources().getDisplayMetrics());
        repository = ZettelData.repository(this);
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        shortPagesSince = prefs.getLong(SHORT_PAGES_SINCE, 0L);
        if (shortPagesSince == 0L) {
            shortPagesSince = System.currentTimeMillis();
            prefs.edit().putLong(SHORT_PAGES_SINCE, shortPagesSince).apply();
        }
        pageGap = dp(24);
        // Restored after inkView is created below.

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.WHITE);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        root.addView(column, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        lowerBars = new LinearLayout(this);
        lowerBars.setOrientation(LinearLayout.VERTICAL);
        lowerBars.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> updateExcludeRects());
        topBar = buildTopBar();
        column.addView(topBar, matchWrap());
        column.addView(rule(), ruleLp());
        column.addView(lowerBars, matchWrap());
        toolbar = buildToolbar();
        column.addView(toolbar, matchWrap());
        column.addView(rule(), ruleLp());

        drawingArea = new FrameLayout(this);
        column.addView(drawingArea, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // Floating mode chip overlays the window; it is not inserted into the top column.
        modeBar = buildModeBar();
        FrameLayout.LayoutParams modeLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        modeLp.gravity = Gravity.BOTTOM;
        modeLp.leftMargin = dp(10);
        modeLp.rightMargin = dp(10);
        modeLp.bottomMargin = dp(12);
        root.addView(modeBar, modeLp);
        lassoMenu = buildLassoMenu();
        FrameLayout.LayoutParams lassoLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        lassoLp.gravity = Gravity.TOP | Gravity.START;
        root.addView(lassoMenu, lassoLp);

        inkView = new PageInkView(this);
        tools = new ToolController(inkView, new ToolController.Host() {
            @Override
            public void styleToolButton(ImageButton button, boolean selected) {
                styleTool(button, selected);
            }

            @Override
            public void onAfterToolChanged(PageInkView.Tool tool) {
                afterToolChanged(tool);
            }
        });
        inkView.setListener(new PageInkView.Listener() {
            @Override
            public void onPageChanged(Board page) {
                repository.saveInk(page);
                PageSlot slot = slotFor(page.id);
                if (slot != null) {
                    slot.syncEnabled();
                }
                // Erase or undo can empty the last page, redo can fill it: the trailing blank follows.
                boolean last = !slots.isEmpty() && slots.get(slots.size() - 1) == slot;
                if (slot != null && !loading() && (page.isBlank() || last)
                        && pagesIn(collectionId).size() != slots.size()) {
                    reloadPages(effectiveScrollY());
                }
            }

            @Override
            public void onPageBecameNonEmpty(Board page) {
                // The first stroke stored the trailing blank, and the repository added a new one.
                if (slotFor(page.id) != null && !loading() && pagesIn(collectionId).size() != slots.size()) {
                    reloadPages(effectiveScrollY());
                }
            }

            @Override
            public void onLassoSelected(int selected) {
                if (selected == 0) {
                    showHint(getString(R.string.lasso_nothing), true);
                    hideLassoMenu();
                } else {
                    showHint(getResources().getQuantityString(R.plurals.lasso_selected, selected, selected), false);
                    showLassoMenu();
                }
            }

            @Override
            public void onLassoMoved(Lasso.Move move) {
                showHint("", false);
                // Selection stays active after a move (Notes): keep the floating
                // menu and drag handles until tap-outside / dismiss / tool change.
                // Stay on Lasso — do not force Pen (v59).
                if (inkView != null && inkView.hasSelection()) {
                    positionLassoMenu();
                } else {
                    hideLassoMenu();
                }
            }

            @Override
            public void onLassoCancelled() {
                showHint("", false);
                hideLassoMenu();
            }

            @Override
            public void onHistoryChanged() {
                runOnUiThread(CanvasActivity.this::syncHistory);
            }

            @Override
            public void onSelectionBoundsChanged() {
                positionLassoMenu();
            }
        });
        drawingArea.addView(inkView, matchMatch());

        scroller = new PageScroller(this);
        scroller.setStylusTarget(inkView);
        scroller.setListener(this::onScrolled);
        scroller.setPasteLongPressListener(this::onPasteLongPress);
        pageColumn = new LinearLayout(this);
        pageColumn.setOrientation(LinearLayout.VERTICAL);
        pageColumn.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (pendingScrollY < 0) {
                updateExcludeRects();
                return;
            }
            int target = pendingScrollY;
            // Keep pending until scrollTo runs so leave/reflow still see the
            // intended Y (ScrollView may read 0 until then).
            scroller.post(() -> {
                if (pendingScrollY != target) {
                    return; // superseded by a newer layoutPages target
                }
                scroller.scrollTo(0, target);
                pendingScrollY = -1;
                inkView.setContentScrollY(scroller.getScrollY());
                refreshModeBar();
                updateExcludeRects();
            });
        });
        scroller.addView(pageColumn, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT));
        drawingArea.addView(scroller, matchMatch());

        drawingArea.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> onDrawingAreaLaidOut());

        // API 35 enforces edge-to-edge. The theme opts out so the fullscreen window stays
        // the pre-35 size. If that opt-out is ignored, pad the root by the system bars so
        // the notebook chrome is not covered and page height still matches the visible area.
        if (Build.VERSION.SDK_INT >= 35) {
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return WindowInsets.CONSUMED;
            });
        }

        setContentView(root);
        repository.addRemoteChangeListener(remoteChanged);
        tools.bind(penButton, eraserButton, lassoButton, toolbar);
        tools.setTool(PageInkView.Tool.PEN);
        syncHistory();
        syncNav();
        inkView.setLive(true);
        // Unconditionally, before any layout: the list is shown as soon as the drawing area has a size.
        openCollection(collectionId);
    }

    /**
     * Page height follows the drawing area: recomputed on every size change,
     * not fixed by the first layout. Sizes too small to hold a page are ignored.
     */
    private void onDrawingAreaLaidOut() {
        int area = drawingArea.getHeight();
        int window = root.getHeight();
        if (area <= pageGap * 2 || window <= 0) {
            return;
        }
        int next = Math.min(window, area - pageGap);
        // The mode strip floats on root; it does not shrink the drawing area.
        if (next == pageHeight && window == legacyPageHeight) {
            return;
        }
        boolean first = pageHeight == 0;
        pageHeight = next;
        legacyPageHeight = window;
        main.post(() -> {
            if (isDestroyed()) {
                return;
            }
            if (awaitingLayout != null) {
                Loaded loaded = awaitingLayout;
                awaitingLayout = null;
                pageReflows.incrementAndGet();
                // Same open intent (jump / restore / first-open); do not substitute scroller Y.
                startLoad(loaded.id, loaded.keepScroll, false);
            } else if (!first && currentPages != null && !loading()) {
                pageReflows.incrementAndGet();
                // Hold place across a size change; pending target wins over ScrollView lag.
                startLoad(collectionId, effectiveScrollY(), false);
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        inkView.resumeLive();
    }

    @Override
    protected void onPause() {
        rememberOpenScroll();
        inkView.pauseLive();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        repository.removeRemoteChangeListener(remoteChanged);
        scroller.removeCallbacks(scrollSettled);
        toolHint.removeCallbacks(clearHint);
        inkView.close();
        super.onDestroy();
    }

    /**
     * Scratchpad pinned left; the notebook tabs scroll, with + after the last
     * one. Long-press a tab for its Rename / Delete menu.
     */
    private LinearLayout buildTopBar() {
        LinearLayout bar = row(Gravity.CENTER_VERTICAL);
        scratchpadTab = iconButton(R.drawable.ic_scratchpad, R.string.scratchpad);
        onTap(scratchpadTab, v -> openScratchpad());
        onLongTap(scratchpadTab, v -> {
            showLaunchLog();
            return true;
        });
        bar.addView(scratchpadTab, iconLp(0));
        bar.addView(barDivider(), barDividerLp());

        notebookScroll = new HorizontalScrollView(this);
        notebookScroll.setHorizontalScrollBarEnabled(false);
        notebookStrip = new LinearLayout(this);
        notebookStrip.setOrientation(LinearLayout.HORIZONTAL);
        notebookStrip.setGravity(Gravity.CENTER_VERTICAL);
        notebookScroll.addView(notebookStrip, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        bar.addView(notebookScroll, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        addButton = tinyButton(getString(R.string.add_notebook), 22);
        addButton.setContentDescription(getString(R.string.new_notebook));
        addButton.setPadding(dp(8), 0, dp(8), dp(2));
        onTap(addButton, v -> showNameForm(null, addButton));
        rebuildNotebookTabs(null);
        return bar;
    }

    private View barDivider() {
        return rule();
    }

    private LinearLayout.LayoutParams barDividerLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(1), dp(32));
        lp.leftMargin = dp(8);
        lp.rightMargin = dp(8);
        return lp;
    }

    private View buildToolbar() {
        LinearLayout toolbar = row(Gravity.CENTER_VERTICAL | Gravity.END);
        toolHint = new TextView(this);
        toolHint.setTextColor(Color.DKGRAY);
        toolHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        toolHint.setSingleLine(true);
        toolHint.setEllipsize(TextUtils.TruncateAt.END);
        toolbar.addView(toolHint, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        undoButton = iconButton(R.drawable.ic_undo, R.string.undo);
        redoButton = iconButton(R.drawable.ic_redo, R.string.redo);
        penButton = iconButton(R.drawable.ic_tool_pen, R.string.pen);
        eraserButton = iconButton(R.drawable.ic_tool_eraser, R.string.eraser);
        lassoButton = iconButton(R.drawable.ic_tool_lasso, R.string.lasso);
        onTap(undoButton, v -> {
            if (!inkView.undo()) {
                showHint(getString(R.string.undo_nothing), true);
            }
        });
        onTap(redoButton, v -> {
            if (!inkView.redo()) {
                showHint(getString(R.string.redo_nothing), true);
            }
        });
        onTap(penButton, v -> {
            dismissOverlay();
            if (tools.tool() == PageInkView.Tool.PEN) {
                // Re-push fountain so a stuck lasso dash cannot outlive the Pen chrome.
                tools.reassert();
            } else {
                tools.setTool(PageInkView.Tool.PEN);
            }
        });
        onTap(eraserButton, v -> {
            dismissOverlay();
            tools.setTool(PageInkView.Tool.ERASER);
        });
        onTap(lassoButton, v -> {
            dismissOverlay();
            tools.setTool(PageInkView.Tool.LASSO);
        });
        settingsButton = iconButton(R.drawable.ic_settings, R.string.settings);
        onTap(settingsButton, v -> showSettings());
        toolbar.addView(undoButton, iconLp(6));
        toolbar.addView(redoButton, iconLp(4));
        toolbar.addView(rule(), barDividerLp());
        toolbar.addView(penButton, iconLp(0));
        toolbar.addView(eraserButton, iconLp(6));
        toolbar.addView(lassoButton, iconLp(6));
        toolbar.addView(rule(), barDividerLp());
        toolbar.addView(settingsButton, iconLp(0));
        return toolbar;
    }

    private ImageButton iconButton(int icon, int label) {
        ImageButton button = new ImageButton(this);
        button.setImageResource(icon);
        button.setContentDescription(getString(label));
        button.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
        button.setPadding(dp(10), dp(10), dp(10), dp(10));
        button.setMinimumWidth(dp(48));
        button.setMinimumHeight(dp(48));
        styleTool(button, false);
        return button;
    }

    private LinearLayout.LayoutParams iconLp(int leftMarginDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(48), dp(48));
        lp.leftMargin = dp(leftMarginDp);
        return lp;
    }

    /**
     * Borderless icon; the active one gets a 2 dp black underline.
     * Always assign a drawable (never null) so e-ink clears the previous
     * underline when selection moves.
     */
    private void styleTool(ImageButton button, boolean selected) {
        button.setBackground(new Underline(dp(2), dp(10), selected));
        button.setImageTintList(ColorStateList.valueOf(Color.BLACK));
        button.setSelected(selected);
    }

    private void syncHistory() {
        syncAction(undoButton, inkView.canUndo());
        syncAction(redoButton, inkView.canRedo());
    }

    private static void syncAction(ImageButton button, boolean enabled) {
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1f : 0.3f);
    }

    private void showHint(String text, boolean brief) {
        toolHint.removeCallbacks(clearHint);
        toolHint.setText(text);
        if (brief) {
            toolHint.postDelayed(clearHint, 2500);
        }
    }

    private void rebuildNotebookTabs() {
        rebuildNotebookTabs(repository.notebooks());
    }

    /** {@code notebooks} null: not loaded yet, so only + shows. */
    private void rebuildNotebookTabs(List<BoardRepository.NotebookInfo> notebooks) {
        if (notebooks != null && sameNotebooks(notebooks)) {
            return;
        }
        shownNotebooks = notebooks == null ? null : new ArrayList<>(notebooks);
        pruneTrail();
        rebuildBars();
        if (shownNotebooks != null && !SCRATCHPAD.equals(collectionId)) {
            BoardRepository.NotebookInfo open = notebookById(collectionId);
            if (open != null && (trail.isEmpty() || !trail.get(trail.size() - 1).equals(collectionId))) {
                openNotebook(collectionId);
            }
        }
    }

    private boolean sameNotebooks(List<BoardRepository.NotebookInfo> notebooks) {
        if (shownNotebooks == null || shownNotebooks.size() != notebooks.size()) {
            return false;
        }
        for (int i = 0; i < notebooks.size(); i++) {
            BoardRepository.NotebookInfo a = shownNotebooks.get(i);
            BoardRepository.NotebookInfo b = notebooks.get(i);
            if (!a.id.equals(b.id) || !a.title.equals(b.title) || !Objects.equals(a.parentId, b.parentId)) {
                return false;
            }
        }
        return true;
    }

    /** Drops the trail at the first notebook that is gone or no longer under the one above it. */
    private void pruneTrail() {
        if (shownNotebooks == null) {
            return;
        }
        int keep = 0;
        String parent = null;
        while (keep < trail.size()) {
            BoardRepository.NotebookInfo info = notebookById(trail.get(keep));
            if (info == null || !Objects.equals(info.parentId, parent)) {
                break;
            }
            parent = info.id;
            keep++;
        }
        if (keep < trail.size()) {
            trail.subList(keep, trail.size()).clear();
        }
    }

    private void rebuildBars() {
        notebookStrip.removeAllViews();
        notebookTabs.clear();
        lowerBars.removeAllViews();
        childBars.clear();
        List<BoardRepository.NotebookInfo> top = childrenOf(null);
        if (shownNotebooks != null && top.isEmpty()) {
            TextView hint = new TextView(this);
            hint.setText(R.string.no_notebooks_bar);
            hint.setTextColor(Color.BLACK);
            hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            hint.setPadding(dp(4), 0, dp(4), 0);
            notebookStrip.addView(hint, wrap());
        }
        fillStrip(notebookStrip, top, addButton, 0);
        if (shownNotebooks != null) {
            for (int depth = 0; depth < trail.size(); depth++) {
                String parentId = trail.get(depth);
                List<BoardRepository.NotebookInfo> kids = childrenOf(parentId);
                if (kids.isEmpty()) {
                    continue;
                }
                if (lowerBars.getChildCount() > 0) {
                    lowerBars.addView(rule(), ruleLp());
                }
                LinearLayout bar = row(Gravity.CENTER_VERTICAL);
                bar.setContentDescription(getString(R.string.child_notebooks));
                HorizontalScrollView scroll = new HorizontalScrollView(this);
                scroll.setHorizontalScrollBarEnabled(false);
                LinearLayout strip = new LinearLayout(this);
                strip.setOrientation(LinearLayout.HORIZONTAL);
                strip.setGravity(Gravity.CENTER_VERTICAL);
                Button plus = tinyButton(getString(R.string.add_notebook), 22);
                plus.setContentDescription(getString(R.string.new_notebook));
                plus.setPadding(dp(8), 0, dp(8), dp(2));
                String createUnder = parentId;
                onTap(plus, v -> showNameForm(null, plus, createUnder));
                fillStrip(strip, kids, plus, depth + 1);
                scroll.addView(strip, new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT));
                bar.addView(scroll, new LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
                lowerBars.addView(bar, matchWrap());
                childBars.add(bar);
            }
        }
        styleTabs();
    }

    private void fillStrip(LinearLayout strip, List<BoardRepository.NotebookInfo> notebooks, Button plus, int depth) {
        for (BoardRepository.NotebookInfo each : notebooks) {
            NotebookTab tab = new NotebookTab(this, each, depth);
            LinearLayout.LayoutParams lp = wrap();
            if (strip.getChildCount() > 0) {
                lp.leftMargin = dp(4);
            }
            strip.addView(tab, lp);
            notebookTabs.add(tab);
        }
        LinearLayout.LayoutParams addLp = wrap();
        addLp.leftMargin = dp(4);
        strip.addView(plus, addLp);
    }

    private List<BoardRepository.NotebookInfo> childrenOf(String parentId) {
        ArrayList<BoardRepository.NotebookInfo> kids = new ArrayList<>();
        if (shownNotebooks == null) {
            return kids;
        }
        for (BoardRepository.NotebookInfo each : shownNotebooks) {
            if (Objects.equals(each.parentId, parentId)) {
                kids.add(each);
            }
        }
        return kids;
    }

    private BoardRepository.NotebookInfo notebookById(String id) {
        if (shownNotebooks == null || id == null) {
            return null;
        }
        for (BoardRepository.NotebookInfo each : shownNotebooks) {
            if (each.id.equals(id)) {
                return each;
            }
        }
        return null;
    }

    /** Parents that currently show a child bar, in trail order. */
    private ArrayList<String> barParents(List<String> chain) {
        ArrayList<String> out = new ArrayList<>();
        for (String id : chain) {
            if (!childrenOf(id).isEmpty()) {
                out.add(id);
            }
        }
        return out;
    }

    /**
     * Once per sync pass: notebooks and the open list are reloaded off the
     * main thread, keeping the scroll; the pen is not held for it.
     */
    private void onRemoteChange() {
        if (isDestroyed()) {
            return;
        }
        // A pull replaces a notebook's log whole. Local undo must not splice it.
        inkView.clearHistory();
        Integer keep = loading() ? pendingKeepScroll : Integer.valueOf(effectiveScrollY());
        startLoad(collectionId, keep, false);
    }

    /** Reopens the last notebook, else the first; the Scratchpad when none are left. */
    private void openNotebooks() {
        openNotebooks(repository.notebooks());
    }

    private void openNotebooks(List<BoardRepository.NotebookInfo> notebooks) {
        String target = null;
        for (BoardRepository.NotebookInfo each : notebooks) {
            if (each.id.equals(notebookId)) {
                target = each.id;
            }
        }
        if (target == null && !notebooks.isEmpty()) {
            target = notebooks.get(0).id;
        }
        if (target == null) {
            notebookId = null;
            openCollection(SCRATCHPAD);
        } else {
            openNotebook(target);
        }
    }

    private void openScratchpad() {
        openScratchpad(null);
    }

    private void openScratchpad(Integer keepScroll) {
        boolean hadBars = !childBars.isEmpty();
        trail.clear();
        if (hadBars) {
            rebuildBars();
        } else {
            styleTabs();
        }
        openCollection(SCRATCHPAD, keepScroll);
    }

    private void openNotebook(String id) {
        openNotebook(id, null);
    }

    private void openNotebook(String id, Integer keepScroll) {
        ArrayList<String> next = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();
        boolean refreshed = false;
        String cursor = id;
        while (cursor != null && seen.add(cursor)) {
            BoardRepository.NotebookInfo info = notebookById(cursor);
            if (info == null && !refreshed) {
                shownNotebooks = repository.notebooks();
                refreshed = true;
                info = notebookById(cursor);
            }
            if (info == null) {
                break;
            }
            next.add(0, info.id);
            cursor = info.parentId;
        }
        if (next.isEmpty()) {
            openNotebooks();
            return;
        }
        boolean sameBars = !refreshed && barParents(trail).equals(barParents(next));
        trail.clear();
        trail.addAll(next);
        notebookId = id;
        if (sameBars) {
            styleTabs();
        } else {
            rebuildBars();
        }
        openCollection(id, keepScroll);
        NotebookTab selected = tabFor(id);
        if (selected != null && selected.getParent() instanceof View
                && selected.getParent().getParent() instanceof HorizontalScrollView) {
            HorizontalScrollView strip = (HorizontalScrollView) selected.getParent().getParent();
            strip.post(() -> revealTab(strip, selected, true));
        }
    }

    /** Scrolls the strip only as far as needed to show the whole tab. */
    private void revealTab(HorizontalScrollView strip, NotebookTab tab, boolean smooth) {
        int viewport = strip.getWidth();
        if (viewport <= 0) {
            return;
        }
        int x = strip.getScrollX();
        int target = x;
        if (tab.getLeft() < x) {
            target = Math.max(0, tab.getLeft() - dp(24));
        } else if (tab.getRight() > x + viewport) {
            target = tab.getRight() - viewport + dp(24);
        }
        if (target == x) {
            return;
        }
        if (smooth) {
            strip.smoothScrollTo(target, 0);
        } else {
            strip.scrollTo(target, 0);
        }
    }

    private NotebookTab tabFor(String id) {
        for (NotebookTab tab : notebookTabs) {
            if (tab.id.equals(id)) {
                return tab;
            }
        }
        return null;
    }

    private String titleOf(String id) {
        for (BoardRepository.NotebookInfo each : repository.notebooks()) {
            if (each.id.equals(id)) {
                return each.title;
            }
        }
        return "";
    }

    private void openCollection(String id) {
        openCollection(id, null);
    }

    private void openCollection(String id, Integer keepScroll) {
        dismissOverlay();
        if (!id.equals(collectionId)) {
            inkView.clearHistory();
        }
        // Persist the collection we leave using the intended Y (pending apply wins).
        // Only when that collection was Ready and shown — never from a partial load.
        if (pagesLoaded && !loading() && collectionShownReady()) {
            rememberScroll(collectionId, effectiveScrollY(), fingerprintOf(currentPages, effectiveScrollY()));
        }
        pagesLoaded = true;
        collectionId = id;
        syncNav();
        // The tab is marked now; its pages load off the main thread, so a tap never waits on
        // storage. The old pages stay up with the pen held until the new ones replace them.
        // A non-null keepScroll (a page link / hold-place reload) wins over remembered
        // scroll and the first-open last-page jump. See notebook-navigation.md.
        startLoad(id, keepScroll, true);
    }

    /** Saves the open list's scroll so a later return (or process death) can restore it. */
    private void rememberOpenScroll() {
        if (!pagesLoaded || loading() || collectionId == null || !collectionShownReady()) {
            return;
        }
        rememberScroll(collectionId, effectiveScrollY(), fingerprintOf(currentPages, effectiveScrollY()));
    }

    private void rememberScroll(String id, int scrollY) {
        rememberScroll(id, scrollY, fingerprintOf(currentPages, scrollY));
    }

    private void rememberScroll(String id, int scrollY, ScrollFingerprint fingerprint) {
        if (id == null) {
            return;
        }
        int y = Math.max(0, scrollY);
        scrollByCollection.put(id, y);
        SharedPreferences.Editor edit = getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putInt(PREF_SCROLL_PREFIX + id, y);
        if (fingerprint != null && fingerprint.pageCount > 0 && fingerprint.pageId != null) {
            edit.putInt(PREF_SCROLL_PAGES_PREFIX + id, fingerprint.pageCount)
                    .putString(PREF_SCROLL_PAGE_PREFIX + id, fingerprint.pageId);
        }
        edit.apply();
    }

    /**
     * Scroll for a collection that was opened before in this install. Null means
     * never visited: the first open still jumps to last page / last inked.
     */
    private Integer rememberedScroll(String id) {
        Integer memory = scrollByCollection.get(id);
        if (memory != null) {
            return memory;
        }
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String key = PREF_SCROLL_PREFIX + id;
        if (!prefs.contains(key)) {
            return null;
        }
        int saved = Math.max(0, prefs.getInt(key, 0));
        scrollByCollection.put(id, saved);
        return saved;
    }

    /** True when the on-screen list came from a Ready sheet (or true legacy boards). */
    private boolean collectionShownReady() {
        if (currentPages == null || currentPages.isEmpty()) {
            return false;
        }
        String notebook = SCRATCHPAD.equals(collectionId) ? null : collectionId;
        if (repository.isSheetReady(notebook)) {
            return true;
        }
        // Pre-migration lists have no paper on boards.
        return currentPages.get(0).paper == null;
    }

    private ScrollFingerprint fingerprintOf(List<Board> pages, int scrollY) {
        if (pages == null || pages.isEmpty()) {
            return null;
        }
        String pageId = pageIdAtScroll(pages, scrollY);
        if (pageId == null) {
            pageId = pages.get(pages.size() - 1).id;
        }
        return new ScrollFingerprint(pages.size(), pageId);
    }

    private String pageIdAtScroll(List<Board> pages, int scrollY) {
        if (pages == null || pages.isEmpty()) {
            return null;
        }
        int y = Math.max(0, scrollY);
        int gap = layoutGap(pages);
        int cursor = 0;
        for (int i = 0; i < pages.size(); i++) {
            int span = heightOf(pages.get(i)) + gap;
            if (y < cursor + span || i == pages.size() - 1) {
                return pages.get(i).id;
            }
            cursor += span;
        }
        return pages.get(pages.size() - 1).id;
    }

    private boolean fingerprintMatches(String id, List<Board> pages) {
        if (id == null || pages == null || pages.isEmpty()) {
            return false;
        }
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (!prefs.contains(PREF_SCROLL_PAGES_PREFIX + id)
                || !prefs.contains(PREF_SCROLL_PAGE_PREFIX + id)) {
            // Pre-v78 memory: Y=0 on a multi-page Ready sheet is ambiguous (often the
            // pending-ScrollView / truncated-list bug). Prefer first-open once; the
            // next leave writes a real fingerprint. Non-zero Y must still fit.
            Integer y = rememberedScroll(id);
            if (y == null) {
                return false;
            }
            if (y == 0 && pages.size() > 1) {
                return false;
            }
            int max = 0;
            int gap = layoutGap(pages);
            for (int i = 0; i < pages.size(); i++) {
                max += heightOf(pages.get(i)) + gap;
            }
            return y <= Math.max(0, max);
        }
        int savedCount = prefs.getInt(PREF_SCROLL_PAGES_PREFIX + id, -1);
        String savedPage = prefs.getString(PREF_SCROLL_PAGE_PREFIX + id, null);
        if (savedCount != pages.size() || savedPage == null) {
            return false;
        }
        for (Board page : pages) {
            if (savedPage.equals(page.id)) {
                return true;
            }
        }
        return false;
    }

    private static final class ScrollFingerprint {
        final int pageCount;
        final String pageId;

        ScrollFingerprint(int pageCount, String pageId) {
            this.pageCount = pageCount;
            this.pageId = pageId;
        }
    }

    /**
     * Intended vertical scroll for the open collection. While {@link #pendingScrollY}
     * is set, the ScrollView may still read {@code 0}; callers that remember or
     * hold place across a reload must use this, not {@code scroller.getScrollY()}.
     */
    private int effectiveScrollY() {
        return pendingScrollY >= 0 ? pendingScrollY : scroller.getScrollY();
    }

    /**
     * Loads the notebooks and one page list off the main thread. Nothing can
     * leave it unshown: if the list is not up by the deadline the load runs
     * again on a fresh thread, with a longer deadline each time, until it is.
     * {@code keepScroll} null means restore a remembered scroll, else first-open
     * last page / last inked.
     */
    private void startLoad(String id, Integer keepScroll, boolean holdPen) {
        int generation = ++loadGeneration;
        pendingKeepScroll = keepScroll;
        if (holdPen) {
            inkView.hold(PageInkView.Hold.LOADING);
        }
        runLoad(generation, id, keepScroll, 0);
    }

    private void runLoad(int generation, String id, Integer keepScroll, int attempt) {
        // 0 = not started, > 0 = reading since that uptime, -1 = finished (shown or failed).
        AtomicLong reading = new AtomicLong();
        armDeadline(generation, id, keepScroll, attempt, reading, attempt);
        String notebook = SCRATCHPAD.equals(id) ? null : id;
        String name = label(id);
        Executor executor = attempt == 0 ? UiExecutors.loader : UiExecutors.retryLoader;
        boolean first = !firstListShown;
        try {
            executor.execute(() -> {
            List<BoardRepository.NotebookInfo> notebooks;
            List<Board> pages;
            boolean ready;
            long started = SystemClock.uptimeMillis();
            reading.set(started);
            try {
                notebooks = repository.notebooks();
                // Tabs don't wait for the page list.
                main.post(() -> {
                    if (generation == loadGeneration && !isDestroyed()) {
                        rebuildNotebookTabs(notebooks);
                    }
                });
                // Ready-gate: never call pages() for show while height is unknown
                // (that used to return truncated Room rows, then show after layout).
                if (pageHeight <= 0) {
                    reading.set(-1);
                    main.post(() -> onPagesLoaded(new Loaded(
                            generation, id, keepScroll, notebooks, java.util.Collections.emptyList(), false)));
                    return;
                }
                ready = repository.ensureSheet(notebook, pageHeight, legacyPageHeight, shortPagesSince);
                if (!ready) {
                    reading.set(-1);
                    // Deleted notebook: ensureSheet fails; still post so UI can leave it.
                    if (notebook != null && notebookByIdIn(notebooks, notebook) == null) {
                        main.post(() -> onPagesLoaded(new Loaded(
                                generation, id, keepScroll, notebooks,
                                java.util.Collections.emptyList(), true)));
                        return;
                    }
                    // Not Ready — do not show truncated Room rows; deadline retries.
                    LaunchLog.mark("page list " + name + " sheet not ready (attempt " + (attempt + 1) + ")");
                    return;
                }
                pages = repository.pages(notebook);
                if (pages.isEmpty()) {
                    reading.set(-1);
                    LaunchLog.mark("page list " + name + " empty after Ready ensure (attempt "
                            + (attempt + 1) + ")");
                    return;
                }
                if (first) {
                    LaunchLog.mark("page list " + name + " read: " + pages.size() + " pages ("
                            + inked(pages) + " inked), " + notebooks.size() + " notebooks in "
                            + (SystemClock.uptimeMillis() - started) + " ms (attempt " + (attempt + 1) + ")");
                }
            } catch (RuntimeException e) {
                reading.set(-1);
                Log.e(TAG, "page list load failed; the deadline retries it", e);
                LaunchLog.mark("page list load failed: " + e);
                return;
            }
            reading.set(-1);
            main.post(() -> onPagesLoaded(new Loaded(generation, id, keepScroll, notebooks, pages, true)));
            });
        } catch (RuntimeException e) {
            Log.e(TAG, "page list load could not start; the deadline retries it", e);
            LaunchLog.mark("page list load could not start: " + e);
        }
    }

    /**
     * If the list is not up by the deadline: an attempt still reading is
     * left to finish (a second read would only compete with it for the CPU);
     * one that failed, never started, or whose result was lost is run again.
     */
    private void armDeadline(int generation, String id, Integer keepScroll, int attempt, AtomicLong reading,
                             int wait) {
        long deadline = Math.min(LOAD_DEADLINE_MAX_MS, FIRST_LOAD_DEADLINE_MS << Math.min(wait, 4));
        main.postDelayed(() -> {
            if (generation != loadGeneration || shownGeneration == generation || isDestroyed()) {
                return;
            }
            long since = reading.get();
            long readFor = since > 0 ? SystemClock.uptimeMillis() - since : 0;
            if (since > 0 && readFor < LOAD_DEADLINE_MAX_MS) {
                LaunchLog.mark("page list " + label(id) + " still being read after " + readFor + " ms (attempt "
                        + (attempt + 1) + ")");
                armDeadline(generation, id, keepScroll, attempt, reading, wait + 1);
                return;
            }
            String stall = "page list " + label(id) + " not shown " + deadline + " ms after load attempt "
                    + (attempt + 1) + (since == 0 ? ", which never started" : "") + "; loading again";
            Log.w("zd-stall", stall);
            LaunchLog.mark(stall);
            runLoad(generation, id, keepScroll, attempt + 1);
        }, deadline);
    }

    private static int inked(List<Board> pages) {
        int n = 0;
        for (Board page : pages) {
            if (!page.isBlank()) {
                n++;
            }
        }
        return n;
    }

    private String label(String id) {
        if (SCRATCHPAD.equals(id)) {
            return SCRATCHPAD;
        }
        if (shownNotebooks != null) {
            for (BoardRepository.NotebookInfo each : shownNotebooks) {
                if (each.id.equals(id)) {
                    return "'" + each.title + "'";
                }
            }
        }
        return id;
    }

    private void onPagesLoaded(Loaded loaded) {
        if (loaded.generation != loadGeneration || shownGeneration == loaded.generation || isDestroyed()) {
            return;
        }
        rebuildNotebookTabs(loaded.notebooks);
        if (!SCRATCHPAD.equals(loaded.id) && notebookById(loaded.id) == null) {
            // The notebook went away (deleted elsewhere).
            if (!trail.isEmpty()) {
                openNotebook(trail.get(trail.size() - 1));
            } else {
                openNotebooks(loaded.notebooks);
            }
            return;
        }
        // Not Ready (height unknown or ensure pending): wait / retry — never show.
        if (!loaded.ready || pageHeight == 0) {
            awaitingLayout = loaded;
            return;
        }
        show(loaded);
    }

    private void show(Loaded loaded) {
        if (loaded.generation != loadGeneration || shownGeneration == loaded.generation) {
            return;
        }
        if (!loaded.ready || loaded.pages.isEmpty()) {
            return;
        }
        // One intent → one target after the sheet is Ready (notebook-navigation.md).
        int target;
        if (loaded.keepScroll != null) {
            // Jump: page link, Move, or hold-place reload/reflow.
            target = loaded.keepScroll;
        } else {
            Integer remembered = rememberedScroll(loaded.id);
            if (remembered != null && fingerprintMatches(loaded.id, loaded.pages)) {
                // Restore: return to where the user left (same page count + page id).
                target = remembered;
            } else if (SCRATCHPAD.equals(loaded.id)) {
                // First open (or fingerprint mismatch): Scratchpad trailing blank.
                target = 0;
                for (int i = 0; i < loaded.pages.size() - 1; i++) {
                    target += heightOf(loaded.pages.get(i)) + layoutGap(loaded.pages);
                }
            } else {
                // First open (or fingerprint mismatch): notebook last inked slice.
                target = lastInkOffset(loaded.pages);
            }
        }
        // Persist the resolved intent + fingerprint before ScrollView layout applies it.
        rememberScroll(loaded.id, target, fingerprintOf(loaded.pages, target));
        showPages(loaded.pages, target);
        syncNav();
        if (!firstListShown) {
            firstListShown = true;
            LaunchLog.mark("first page list on screen: " + label(loaded.id) + ", " + loaded.pages.size()
                    + " pages (" + inked(loaded.pages) + " inked), " + loaded.notebooks.size() + " notebook tabs");
        }
    }

    private static BoardRepository.NotebookInfo notebookByIdIn(
            List<BoardRepository.NotebookInfo> notebooks, String id) {
        if (notebooks == null || id == null) {
            return null;
        }
        for (BoardRepository.NotebookInfo each : notebooks) {
            if (each.id.equals(id)) {
                return each;
            }
        }
        return null;
    }

    /** One finished background load. {@code ready} means ensureSheet installed the sheet. */
    private static final class Loaded {
        final int generation;
        final String id;
        final Integer keepScroll;
        final List<BoardRepository.NotebookInfo> notebooks;
        final List<Board> pages;
        final boolean ready;

        Loaded(int generation, String id, Integer keepScroll, List<BoardRepository.NotebookInfo> notebooks,
               List<Board> pages, boolean ready) {
            this.generation = generation;
            this.id = id;
            this.keepScroll = keepScroll;
            this.notebooks = notebooks;
            this.pages = pages;
            this.ready = ready;
        }
    }

    private boolean loading() {
        return loadGeneration != shownGeneration;
    }

    private void reloadPages(int targetScrollY) {
        List<Board> pages = pagesIn(collectionId);
        // Log-backed sheet vanished from cache mid-session — re-ensure, do not show [].
        if (pages.isEmpty()) {
            startLoad(collectionId, targetScrollY, false);
            return;
        }
        if (tryAppendTrailingBlank(pages, targetScrollY)) {
            return;
        }
        ++loadGeneration;
        showPages(pages, targetScrollY);
    }

    private void showPages(List<Board> pages, int targetScrollY) {
        shownGeneration = loadGeneration;
        awaitingLayout = null;
        inkView.release(PageInkView.Hold.LOADING);
        layoutPages(pages, targetScrollY);
    }

    /**
     * First ink on the trailing blank grows the list by exactly one new blank
     * under an unchanged stack. Append that slot and extend the ink viewport
     * instead of tearing every page down (which flashes and holds the pen off).
     */
    private boolean tryAppendTrailingBlank(List<Board> pages, int targetScrollY) {
        if (loading() || currentPages == null || slots.isEmpty() || pageHeight <= 0) {
            return false;
        }
        if (pages.size() != currentPages.size() + 1 || pages.size() != slots.size() + 1) {
            return false;
        }
        if (targetScrollY != effectiveScrollY()) {
            return false;
        }
        int gap = layoutGap(pages);
        if (gap != layoutGap(currentPages)) {
            return false;
        }
        for (int i = 0; i < currentPages.size(); i++) {
            Board was = currentPages.get(i);
            Board now = pages.get(i);
            if (!was.id.equals(now.id) || heightOf(was) != heightOf(now) || slots.get(i).page != now) {
                return false;
            }
        }
        Board blank = pages.get(pages.size() - 1);
        if (!blank.isBlank()) {
            return false;
        }
        currentPages = pages;
        int height = heightOf(blank);
        int span = height + gap;
        int top = slotTops[slots.size()];
        int[] nextTops = new int[slotTops.length + 1];
        System.arraycopy(slotTops, 0, nextTops, 0, slotTops.length);
        nextTops[nextTops.length - 1] = top + span;
        slotTops = nextTops;
        if (slotStride > 0 && span != slotStride) {
            slotStride = 0;
        }
        int total = pages.size();
        for (int i = 0; i < slots.size(); i++) {
            slots.get(i).setIndex(i + 1, total);
        }
        PageSlot slot = new PageSlot(this, blank, total, total);
        slots.add(slot);
        pageColumn.addView(slot, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, span));
        // After the new blank is in slots so isTrailingBlank sees it as the tail.
        for (PageSlot each : slots) {
            each.syncEnabled();
        }
        int[] heights = new int[pages.size()];
        for (int i = 0; i < pages.size(); i++) {
            heights[i] = heightOf(pages.get(i));
        }
        inkView.extendPages(pages, heights, gap);
        pageColumn.requestLayout();
        refreshModeBar();
        updateExcludeRects();
        return true;
    }

    /** Builds the page slots and ink layout for {@code pages}; needs a page height. */
    private void layoutPages(List<Board> pages, int targetScrollY) {
        currentPages = pages;
        pageColumn.removeAllViews();
        slots.clear();
        int[] heights = new int[pages.size()];
        slotTops = new int[pages.size() + 1];
        int gap = layoutGap(pages);
        int cursor = 0;
        int stride = -1;
        boolean uniform = true;
        for (int i = 0; i < pages.size(); i++) {
            heights[i] = heightOf(pages.get(i));
            slotTops[i] = cursor;
            int span = heights[i] + gap;
            if (stride < 0) {
                stride = span;
            } else if (span != stride) {
                uniform = false;
            }
            cursor += span;
            PageSlot slot = new PageSlot(this, pages.get(i), i + 1, pages.size());
            slots.add(slot);
            pageColumn.addView(slot, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, span));
        }
        slotTops[pages.size()] = cursor;
        slotStride = uniform && stride > 0 ? stride : 0;
        for (PageSlot slot : slots) {
            slot.syncEnabled();
        }
        inkView.setPages(pages, heights, gap, targetScrollY);
        pendingScrollY = Math.max(0, targetScrollY);
        pageColumn.requestLayout();
        refreshModeBar();
    }

    /**
     * Scroll offset of the last slice that already has ink. Each slice keeps
     * its own height, so a tall page from before v10 and a short page after it
     * do not share one stride. An index past the last stored slice, or a blank
     * the padded bottom once named, is not a page to land on.
     */
    private int lastInkOffset(List<Board> pages) {
        if (pages.isEmpty() || pages.get(0).paper == null) {
            return 0;
        }
        int index = pages.get(0).paper.lastInkedSlice();
        if (index < 0) {
            return 0;
        }
        if (index >= pages.size()) {
            index = pages.size() - 1;
        }
        while (index > 0 && pages.get(index).isBlank()) {
            index--;
        }
        int scroll = 0;
        int gap = layoutGap(pages);
        for (int i = 0; i < index; i++) {
            scroll += heightOf(pages.get(i)) + gap;
        }
        return scroll;
    }

    /**
     * Floating bottom chip for Link and Reorder. Not parented into
     * the notebook tab bars; a hole in the Onyx reader when visible (bounds follow margins).
     */
    private LinearLayout buildModeBar() {
        LinearLayout stack = new LinearLayout(this);
        stack.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(6));
        background.setColor(Color.WHITE);
        background.setStroke(dp(1), Color.BLACK);
        stack.setBackground(background);
        stack.setVisibility(View.GONE);
        stack.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> updateExcludeRects());
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(8), dp(8), dp(8), dp(8));
        modeText = new TextView(this);
        modeText.setTextColor(Color.BLACK);
        modeText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        modeText.setSingleLine(false);
        row.addView(modeText, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        modeConfirm = tinyButton(getString(R.string.confirm), 15);
        styleButton(modeConfirm, true);
        modeConfirm.setVisibility(View.GONE);
        onTap(modeConfirm, v -> confirmMode());
        Button cancel = tinyButton(getString(R.string.cancel), 15);
        onTap(cancel, v -> cancelMode());
        LinearLayout.LayoutParams confirmLp = wrap();
        confirmLp.leftMargin = dp(8);
        row.addView(modeConfirm, confirmLp);
        LinearLayout.LayoutParams cancelLp = wrap();
        cancelLp.leftMargin = dp(8);
        row.addView(cancel, cancelLp);
        stack.addView(row, matchWrap());
        return stack;
    }

    private LinearLayout buildLassoMenu() {
        LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable background = new GradientDrawable();
        // Compact Notes-like capsule (not the wide bottom snackbar chip).
        background.setCornerRadius(dp(22));
        background.setColor(Color.WHITE);
        background.setStroke(dp(1), Color.BLACK);
        pill.setBackground(background);
        pill.setVisibility(View.GONE);
        pill.setElevation(0f);
        pill.setPadding(dp(2), dp(1), dp(2), dp(1));
        pill.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (l != ol || t != ot || r != or || b != ob) {
                positionLassoMenu();
            }
            updateExcludeRects();
        });
        lassoCutButton = menuChip(getString(R.string.cut));
        lassoCopyButton = menuChip(getString(R.string.copy));
        lassoDeleteButton = menuChip(getString(R.string.delete));
        lassoPasteButton = menuChip(getString(R.string.paste));
        onTap(lassoCutButton, v -> {
            if (inkView.cutSelection()) {
                showHint(getString(R.string.lasso_cut), true);
            }
        });
        onTap(lassoCopyButton, v -> {
            if (inkView.copySelection()) {
                showHint(getString(R.string.lasso_copied), true);
                syncLassoMenuActions();
            }
        });
        onTap(lassoDeleteButton, v -> {
            if (inkView.deleteSelection()) {
                showHint(getString(R.string.lasso_deleted), true);
            }
        });
        onTap(lassoPasteButton, v -> {
            if (inkView.pasteClipboard()) {
                showHint(getString(R.string.lasso_pasted), true);
                hideLassoMenu();
                syncLassoMenuActions();
            }
        });
        pill.addView(lassoCutButton, wrap());
        pill.addView(lassoCopyButton, wrap());
        pill.addView(lassoDeleteButton, wrap());
        pill.addView(lassoPasteButton, wrap());
        return pill;
    }

    private Button menuChip(String label) {
        Button button = tinyButton(label, 13);
        button.setMinimumHeight(dp(36));
        button.setMinimumWidth(dp(36));
        button.setPadding(dp(8), 0, dp(8), 0);
        return button;
    }

    private void showLassoMenu() {
        if (modeBar.getVisibility() == View.VISIBLE) {
            return;
        }
        syncLassoMenuActions();
        lassoMenu.setVisibility(View.VISIBLE);
        lassoMenu.bringToFront();
        // Position after layout so measure/getLocationOnScreen are valid.
        lassoMenu.post(this::positionLassoMenu);
        updateExcludeRects();
    }

    private void hideLassoMenu() {
        if (lassoMenu.getVisibility() != View.VISIBLE) {
            return;
        }
        lassoMenu.setVisibility(View.GONE);
        updateExcludeRects();
    }

    private void syncLassoMenuActions() {
        boolean paste = inkView != null && inkView.hasClipboard();
        // Optional on the pill — primary paste path is long-press on a page.
        lassoPasteButton.setVisibility(paste ? View.VISIBLE : View.GONE);
        lassoPasteButton.setEnabled(paste);
    }

    /**
     * Long-press on empty paper while the lasso clipboard is full: Paste at
     * that surface point (paper coords) or Clear clipboard.
     */
    private void onPasteLongPress(float surfaceX, float surfaceY) {
        if (inkView == null || !inkView.hasClipboard() || inkView.hasSelection()) {
            return;
        }
        if (modeBar.getVisibility() == View.VISIBLE || overlay != null) {
            return;
        }
        LinearLayout menu = panel();
        menu.setMinimumWidth(dp(180));
        addPanelButton(menu, getString(R.string.paste), () -> {
            if (inkView.pasteClipboardAt(surfaceX, surfaceY)) {
                showHint(getString(R.string.lasso_pasted), true);
                syncLassoMenuActions();
            }
        });
        addPanelButton(menu, getString(R.string.clear_clipboard), () -> {
            inkView.clearClipboard();
            showHint("", false);
            syncLassoMenuActions();
        });
        showOverlay(menu, pasteMenuLp(surfaceX, surfaceY, menu), drawingArea);
    }

    /** Anchor the paste menu near the long-press in {@link #drawingArea}. */
    private FrameLayout.LayoutParams pasteMenuLp(float surfaceX, float surfaceY, View content) {
        content.measure(
                View.MeasureSpec.makeMeasureSpec(drawingArea.getWidth(), View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(drawingArea.getHeight(), View.MeasureSpec.AT_MOST));
        int w = content.getMeasuredWidth();
        int h = content.getMeasuredHeight();
        int left = Math.round(surfaceX - w / 2f);
        int top = Math.round(surfaceY - dp(8));
        left = Math.max(dp(8), Math.min(left, drawingArea.getWidth() - w - dp(8)));
        top = Math.max(dp(8), Math.min(top, drawingArea.getHeight() - h - dp(8)));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.leftMargin = left;
        lp.topMargin = top;
        return lp;
    }

    /**
     * Anchor the floating pill just above the dashed selection bounds, in
     * {@link #root} coordinates (ink surface origin + surface-local box).
     */
    private void positionLassoMenu() {
        if (lassoMenu.getVisibility() != View.VISIBLE || inkView == null || root == null) {
            return;
        }
        android.graphics.RectF box = inkView.selectionBoundsOnSurface();
        if (box == null) {
            return;
        }
        int rootW = root.getWidth();
        int rootH = root.getHeight();
        if (rootW <= 0 || rootH <= 0) {
            return;
        }
        int[] inkLoc = new int[2];
        int[] rootLoc = new int[2];
        inkView.getLocationOnScreen(inkLoc);
        root.getLocationOnScreen(rootLoc);
        int originX = inkLoc[0] - rootLoc[0];
        int originY = inkLoc[1] - rootLoc[1];

        lassoMenu.measure(
                View.MeasureSpec.makeMeasureSpec(rootW, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(rootH, View.MeasureSpec.AT_MOST));
        int w = Math.max(lassoMenu.getMeasuredWidth(), lassoMenu.getWidth());
        int h = Math.max(lassoMenu.getMeasuredHeight(), lassoMenu.getHeight());
        if (w <= 0 || h <= 0) {
            lassoMenu.post(this::positionLassoMenu);
            return;
        }
        int gap = dp(8);
        int left = originX + Math.round(box.centerX() - w / 2f);
        int top = originY + Math.round(box.top - h - gap);
        // Prefer above the box; if that would cover the top chrome, sit just below.
        int minTop = originY + dp(4);
        if (top < minTop) {
            top = originY + Math.round(box.bottom + gap);
        }
        left = Math.max(dp(4), Math.min(left, rootW - w - dp(4)));
        // Clamp to the ink band so the pill never parks as a fake bottom snackbar
        // under the drawing area (the v50 failure mode when Y was out of range).
        int maxTop = originY + Math.max(dp(4), drawingArea.getHeight() - h - dp(4));
        top = Math.max(minTop, Math.min(top, maxTop));

        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) lassoMenu.getLayoutParams();
        if (lp.gravity == (Gravity.TOP | Gravity.START)
                && lp.leftMargin == left
                && lp.topMargin == top
                && lp.width == FrameLayout.LayoutParams.WRAP_CONTENT
                && lp.height == FrameLayout.LayoutParams.WRAP_CONTENT) {
            return;
        }
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.width = FrameLayout.LayoutParams.WRAP_CONTENT;
        lp.height = FrameLayout.LayoutParams.WRAP_CONTENT;
        lp.leftMargin = left;
        lp.topMargin = top;
        lp.rightMargin = 0;
        lp.bottomMargin = 0;
        lassoMenu.setLayoutParams(lp);
    }

    private void startLinking(PageSlot slot) {
        if (slot.page.isBlank()) {
            return;
        }
        cancelReorder();
        hideLassoMenu();
        linkSourceId = slot.page.id;
        linkSourceRef = refOnScreen(slot);
        modeBar.setVisibility(View.VISIBLE);
        refreshModeBar();
        updateExcludeRects();
    }

    private void startReorder(PageSlot slot) {
        if (slot.page.isBlank()) {
            return;
        }
        cancelLinking();
        hideLassoMenu();
        reorderSourceId = slot.page.id;
        reorderSourceRef = refOnScreen(slot);
        reorderSourceNotebookId = SCRATCHPAD.equals(collectionId) ? null : collectionId;
        modeBar.setVisibility(View.VISIBLE);
        refreshModeBar();
        updateExcludeRects();
    }

    private void cancelMode() {
        if (linkSourceId != null) {
            cancelLinking();
        } else {
            cancelReorder();
        }
    }

    private void confirmMode() {
        if (linkSourceId != null) {
            confirmLinking();
        } else if (reorderSourceId != null) {
            confirmReorder();
        }
    }

    private boolean modeActive() {
        return linkSourceId != null || reorderSourceId != null;
    }

    private void cancelLinking() {
        linkSourceId = null;
        linkSourceRef = null;
        if (!modeActive()) {
            modeBar.setVisibility(View.GONE);
            modeConfirm.setVisibility(View.GONE);
        } else {
            refreshModeBar();
        }
        updateExcludeRects();
    }

    private void cancelReorder() {
        reorderSourceId = null;
        reorderSourceRef = null;
        reorderSourceNotebookId = null;
        if (!modeActive()) {
            modeBar.setVisibility(View.GONE);
            modeConfirm.setVisibility(View.GONE);
        } else {
            refreshModeBar();
        }
        updateExcludeRects();
    }

    private void confirmLinking() {
        PageSlot front = frontSlot();
        if (linkSourceId == null || front == null || front.page.id.equals(linkSourceId)) {
            refreshModeBar();
            return;
        }
        repository.createLink(linkSourceId, front.page.id);
        int scroll = effectiveScrollY();
        cancelLinking();
        reloadPages(scroll);
    }

    private void confirmReorder() {
        PageSlot target = reorderTargetSlot();
        if (reorderSourceId == null || target == null || target.page.id.equals(reorderSourceId)) {
            refreshModeBar();
            return;
        }
        // Slots still show the previous notebook while a load runs. Confirm
        // would label dest as collectionId but the midline page belongs elsewhere.
        if (loading()) {
            refreshModeBar();
            return;
        }
        if (!target.page.isBlank() && sameNotebookForReorder(target) && isAlreadyBeforeTarget(target)) {
            refreshModeBar();
            return;
        }
        if (target.page.isBlank() && sameNotebookForReorder(target) && isSourceLastInked()) {
            refreshModeBar();
            return;
        }
        String sourceId = reorderSourceId;
        // Destination is the open collection (snackbar name), not the target
        // page's sheet — that page can be a stale slot from the parent notebook.
        String destNotebookId = SCRATCHPAD.equals(collectionId) ? null : collectionId;
        // Flush any pending pen-up before the tear. Cross-notebook place holds
        // sheet flushes so these writer tasks cannot persist a source tear
        // before the destination rewrite (see RoomBoardRepository holdFlush).
        repository.saveInk(target.page);
        Board source = pageOnScreen(sourceId);
        if (source == null) {
            for (Board page : repository.pages(reorderSourceNotebookId)) {
                if (page.id.equals(sourceId)) {
                    source = page;
                    break;
                }
            }
        }
        if (source != null) {
            repository.saveInk(source);
        }
        if (!repository.placePageBefore(sourceId, target.page.id, destNotebookId)) {
            LaunchLog.mark("place-before Confirm refused; leave Reorder mode open");
            refreshModeBar();
            return;
        }
        cancelReorder();
        openAfterReorder(sourceId, destNotebookId);
    }

    private Board pageOnScreen(String pageId) {
        for (PageSlot slot : slots) {
            if (slot.page.id.equals(pageId)) {
                return slot.page;
            }
        }
        return null;
    }

    private void openAfterReorder(String pageId, String notebookId) {
        List<Board> pages = repository.pages(notebookId);
        int gap = layoutGap(pages);
        int scroll = 0;
        for (int i = 0; i < pages.size(); i++) {
            if (pages.get(i).id.equals(pageId)) {
                if (notebookId == null) {
                    openScratchpad(scroll);
                } else {
                    openNotebook(notebookId, scroll);
                }
                return;
            }
            scroll += heightOf(pages.get(i)) + gap;
        }
        if (notebookId == null) {
            openScratchpad(effectiveScrollY());
        } else {
            openNotebook(notebookId, effectiveScrollY());
        }
    }

    private boolean sameNotebookForReorder(PageSlot target) {
        String targetNotebook = SCRATCHPAD.equals(collectionId) ? null : collectionId;
        return java.util.Objects.equals(targetNotebook, reorderSourceNotebookId);
    }

    /** True when source already sits immediately before {@code target} in this list. */
    private boolean isAlreadyBeforeTarget(PageSlot target) {
        int targetIndex = slots.indexOf(target);
        if (targetIndex < 0) {
            return false;
        }
        int sourceIndex = -1;
        for (int i = 0; i < slots.size(); i++) {
            if (slots.get(i).page.id.equals(reorderSourceId)) {
                sourceIndex = i;
                break;
            }
        }
        return sourceIndex == targetIndex - 1;
    }

    /** True when source is the last inked page of the open list (already at the end). */
    private boolean isSourceLastInked() {
        int lastInked = -1;
        int sourceIndex = -1;
        for (int i = 0; i < slots.size(); i++) {
            PageSlot slot = slots.get(i);
            if (slot.page.id.equals(reorderSourceId)) {
                sourceIndex = i;
            }
            if (!slot.page.isBlank()) {
                lastInked = i;
            }
        }
        return sourceIndex >= 0 && sourceIndex == lastInked;
    }

    /**
     * The page at the top of the viewport. Scroll offset divided by the page
     * height: the slots above it are not visited. Used by Link.
     */
    private PageSlot frontSlot() {
        int index = indexAt(scroller.getScrollY());
        if (index < 0) {
            return null;
        }
        return slots.get(index);
    }

    /**
     * Reorder target: the page that owns the vertical center of the drawing
     * viewport, even when only partially visible.
     */
    private PageSlot reorderTargetSlot() {
        int viewport = scroller.getHeight();
        if (viewport <= 0 && drawingArea != null) {
            viewport = drawingArea.getHeight();
        }
        int mid = scroller.getScrollY() + Math.max(viewport, 1) / 2;
        int index = indexAt(mid);
        if (index < 0) {
            return null;
        }
        return slots.get(index);
    }

    /** Slot that contains content offset {@code y}, from the tops recorded when the list was shown. */
    private int indexAt(int y) {
        int n = slots.size();
        if (n == 0) {
            return -1;
        }
        if (y <= 0) {
            return 0;
        }
        if (slotStride > 0) {
            return Math.min(n - 1, y / slotStride);
        }
        if (slotTops.length != n + 1) {
            return 0;
        }
        int lo = 0;
        int hi = n - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (slotTops[mid] <= y) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo;
    }

    private void refreshModeBar() {
        if (modeBar.getVisibility() != View.VISIBLE) {
            return;
        }
        if (linkSourceId != null && linkSourceRef != null) {
            PageSlot front = frontSlot();
            if (front == null || front.page.id.equals(linkSourceId)) {
                modeText.setText(getString(R.string.linking_from, linkSourceRef));
                setModeConfirmEnabled(false);
            } else {
                modeText.setText(getString(R.string.link_from_to, linkSourceRef, refOnScreen(front)));
                setModeConfirmEnabled(true);
            }
            return;
        }
        if (reorderSourceId != null && reorderSourceRef != null) {
            PageSlot target = reorderTargetSlot();
            if (loading()) {
                modeText.setText(getString(R.string.reordering, reorderSourceRef));
                setModeConfirmEnabled(false);
            } else if (target == null || target.page.id.equals(reorderSourceId)) {
                modeText.setText(getString(R.string.reordering, reorderSourceRef));
                setModeConfirmEnabled(false);
            } else if (!target.page.isBlank() && sameNotebookForReorder(target)
                    && isAlreadyBeforeTarget(target)) {
                modeText.setText(getString(R.string.reordering, reorderSourceRef));
                setModeConfirmEnabled(false);
            } else if (target.page.isBlank() && sameNotebookForReorder(target)
                    && isSourceLastInked()) {
                modeText.setText(getString(R.string.reordering, reorderSourceRef));
                setModeConfirmEnabled(false);
            } else if (target.page.isBlank()) {
                modeText.setText(getString(R.string.place_in, reorderSourceRef, nameOnScreen()));
                setModeConfirmEnabled(true);
            } else {
                modeText.setText(getString(R.string.place_before, reorderSourceRef, refOnScreen(target)));
                setModeConfirmEnabled(true);
            }
        }
    }

    /**
     * Valid Place under target: a different notebook that is not a descendant
     * and not already the source's parent.
     */
    private boolean canPlaceUnder(String sourceId, String targetId) {
        if (sourceId == null || targetId == null || sourceId.equals(targetId)) {
            return false;
        }
        BoardRepository.NotebookInfo source = notebookById(sourceId);
        BoardRepository.NotebookInfo target = notebookById(targetId);
        if (source == null || target == null) {
            return false;
        }
        if (targetId.equals(source.parentId)) {
            return false;
        }
        return !descendantIds(sourceId).contains(targetId);
    }

    /** Confirm stays visible while the mode chip is up; greyed when the action is a no-op. */
    private void setModeConfirmEnabled(boolean enabled) {
        modeConfirm.setVisibility(View.VISIBLE);
        modeConfirm.setEnabled(enabled);
        modeConfirm.setAlpha(enabled ? 1f : 0.3f);
    }

    /**
     * Notebook name and current page index for Link / Reorder snackbars.
     * Totals stay on the top-left chrome only ({@code k/n}); snackbars omit
     * {@code /n} so two page numbers are easier to compare.
     */
    private String refOnScreen(PageSlot slot) {
        return nameOnScreen() + " " + slot.index;
    }

    private String nameOnScreen() {
        if (SCRATCHPAD.equals(collectionId)) {
            return getString(R.string.scratchpad);
        }
        if (shownNotebooks != null) {
            for (BoardRepository.NotebookInfo each : shownNotebooks) {
                if (each.id.equals(collectionId)) {
                    return each.title;
                }
            }
        }
        return "";
    }

    /**
     * Notebook name and k/n, read now. A rename or a deleted page above the
     * target changes the text the next time the page is shown.
     */
    private String pageRef(String pageId) {
        BoardRepository.PagePlace place = repository.placeOf(pageId);
        if (place == null) {
            return null;
        }
        String name = place.notebookId == null
                ? getString(R.string.scratchpad)
                : titleOf(place.notebookId);
        if (name == null || name.isEmpty()) {
            return null;
        }
        return name + " " + (place.index + 1) + "/" + place.count;
    }

    /** Opens that page the way tapping its notebook and scrolling there would. */
    private void openLinkedPage(String pageId) {
        BoardRepository.PagePlace place = repository.placeOf(pageId);
        if (place == null) {
            return;
        }
        List<Board> pages = repository.pages(place.notebookId);
        int gap = layoutGap(pages);
        int scroll = 0;
        for (int i = 0; i < place.index && i < pages.size(); i++) {
            scroll += heightOf(pages.get(i)) + gap;
        }
        if (place.notebookId == null) {
            openScratchpad(scroll);
        } else {
            openNotebook(place.notebookId, scroll);
        }
    }

    private List<Board> pagesIn(String id) {
        return repository.pages(SCRATCHPAD.equals(id) ? null : id);
    }

    private void onScrolled(int scrollY) {
        if (inkView.hasSelection()) {
            // Selection captures touches; do not remount the viewport or pause
            // the (already paused) pen with Hold.SCROLL.
            refreshModeBar();
            return;
        }
        if (!scrolling) {
            scrolling = true;
            inkView.hold(PageInkView.Hold.SCROLL);
        }
        inkView.setContentScrollY(scrollY);
        refreshModeBar();
        scroller.removeCallbacks(scrollSettled);
        scroller.postDelayed(scrollSettled, SCROLL_SETTLE_MS);
    }

    private void onScrollSettled() {
        if (inkView.hasSelection()) {
            scrolling = false;
            updateExcludeRects();
            return;
        }
        scrolling = false;
        inkView.setContentScrollY(scroller.getScrollY());
        updateExcludeRects();
        inkView.release(PageInkView.Hold.SCROLL);
    }

    /** Above the page's bottom-right controls, right-aligned with them; below if there is no room above. */
    private FrameLayout.LayoutParams pageAnchoredLp(View anchor, View content) {
        content.measure(View.MeasureSpec.makeMeasureSpec(drawingArea.getWidth(), View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(drawingArea.getHeight(), View.MeasureSpec.AT_MOST));
        int[] area = new int[2];
        int[] button = new int[2];
        drawingArea.getLocationOnScreen(area);
        anchor.getLocationOnScreen(button);
        int buttonTop = button[1] - area[1];
        int buttonBottom = buttonTop + anchor.getHeight();
        int top = buttonTop - dp(6) - content.getMeasuredHeight();
        if (top < 0) {
            top = Math.min(buttonBottom + dp(6),
                    Math.max(0, drawingArea.getHeight() - content.getMeasuredHeight()));
        }
        top = Math.max(0, top);
        int right = drawingArea.getWidth() - (button[0] - area[0] + anchor.getWidth());
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.TOP | Gravity.END;
        lp.topMargin = top;
        lp.rightMargin = Math.max(dp(8), right);
        return lp;
    }

    /** Wipe and Delete for one page, anchored to its ⋮; each confirms in the same spot. */
    private void showPageMenu(PageSlot slot) {
        if (isTrailingBlank(slot) || !slot.moreButton.isEnabled()) {
            return;
        }
        LinearLayout menu = panel();
        menu.setMinimumWidth(dp(200));
        if (!slot.page.isBlank()) {
            addPanelButton(menu, getString(R.string.wipe), () -> confirmPageAction(slot, false));
        }
        addPanelButton(menu, getString(R.string.delete_page), () -> confirmPageAction(slot, true));
        showOverlay(menu, pageAnchoredLp(slot.moreButton, menu), drawingArea);
    }

    private void confirmPageAction(PageSlot slot, boolean delete) {
        LinearLayout box = panel();
        box.addView(panelText(getString(delete ? R.string.delete_page_title : R.string.wipe_page_title), 15));
        box.addView(panelText(getString(delete ? R.string.delete_page_body : R.string.wipe_page_body), 13));
        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.END);
        Button cancel = tinyButton(getString(R.string.cancel), 15);
        cancel.setMinimumHeight(dp(48));
        onTap(cancel, v -> dismissOverlay());
        Button confirm = tinyButton(getString(delete ? R.string.delete : R.string.wipe), 15);
        confirm.setMinimumHeight(dp(48));
        styleButton(confirm, true);
        onTap(confirm, v -> {
            dismissOverlay();
            if (delete) {
                deletePage(slot);
            } else {
                wipe(slot);
            }
        });
        actions.addView(cancel, wrap());
        LinearLayout.LayoutParams lp = wrap();
        lp.leftMargin = dp(8);
        actions.addView(confirm, lp);
        LinearLayout.LayoutParams actionsLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        actionsLp.topMargin = dp(8);
        box.addView(actions, actionsLp);
        showOverlay(box, pageAnchoredLp(slot.moreButton, box), drawingArea);
    }

    /** The page after it slides into its place, and the view scrolls so that page starts at the top. */
    private void deletePage(PageSlot slot) {
        int index = slots.indexOf(slot);
        repository.deletePage(slot.page.id);
        inkView.invalidatePage(slot.page.id);
        List<Board> pages = pagesIn(collectionId);
        int target = 0;
        for (int i = 0; i < Math.min(index, pages.size() - 1); i++) {
            target += heightOf(pages.get(i)) + layoutGap(pages);
        }
        ++loadGeneration;
        showPages(pages, index < 0 ? effectiveScrollY() : target);
    }

    /** The blank page that ends every list: it has nothing to wipe, move or delete. */
    private boolean isTrailingBlank(PageSlot slot) {
        return !slots.isEmpty() && slots.get(slots.size() - 1) == slot && slot.page.isBlank();
    }

    /** Rename / Delete, dropped down from the notebook's own tab. */
    private void showNotebookMenu(String id) {
        NotebookTab tab = tabFor(id);
        if (tab == null) {
            return;
        }
        if (tab.getParent() instanceof View && tab.getParent().getParent() instanceof HorizontalScrollView) {
            revealTab((HorizontalScrollView) tab.getParent().getParent(), tab, false);
        }
        LinearLayout menu = panel();
        menu.setMinimumWidth(Math.max(dp(200), tab.getWidth()));
        addPanelButton(menu, getString(R.string.rename), 0, () -> showNameForm(id, tab));
        addPanelButton(menu, getString(R.string.place_under), 0, () -> showPlaceUnderMenu(id, tab));
        addPanelButton(menu, getString(R.string.make_top_level), 0, () -> {
            if (repository.placeNotebook(id, null) && trail.contains(id)) {
                openNotebook(id);
            } else {
                rebuildNotebookTabs();
            }
        });
        addPanelButton(menu, getString(R.string.delete), 0, () -> confirmDelete(id, tab));
        showOverlay(menu, anchoredLp(tab, menu), root);
    }

    /**
     * In-window Move destination picker (not snackbar+nav). Appends the page
     * as newest in the chosen notebook. Uses the shared hierarchy menu.
     */
    private void showMoveMenu(PageSlot slot) {
        if (slot.page.isBlank()) {
            return;
        }
        cancelLinking();
        cancelReorder();
        hideLassoMenu();
        showNotebookHierarchyMenu(slot.moveButton, drawingArea, null, true, slot,
                notebookId -> movePageToNotebook(slot, notebookId));
    }

    /**
     * Place under parent picker — same hierarchy menu as Move, with self and
     * descendants omitted.
     */
    private void showPlaceUnderMenu(String notebookId, View anchor) {
        if (notebookId == null || notebookById(notebookId) == null) {
            return;
        }
        HashSet<String> skip = descendantIds(notebookId);
        skip.add(notebookId);
        showNotebookHierarchyMenu(anchor, root, skip, false, null, parentId -> {
            if (!canPlaceUnder(notebookId, parentId)) {
                return;
            }
            if (repository.placeNotebook(notebookId, parentId)) {
                if (trail.contains(notebookId) || notebookId.equals(collectionId)) {
                    openNotebook(notebookId);
                } else {
                    rebuildNotebookTabs();
                }
            }
        });
    }

    /**
     * Shared indented notebook hierarchy for Move destination and Place-under
     * parent. Fills nearly the full drawing-area height (minus safe margins)
     * so deep trees need far less scrolling than the old ~55% chip.
     */
    private void showNotebookHierarchyMenu(
            View anchor,
            FrameLayout host,
            HashSet<String> skipIds,
            boolean includeNewNotebook,
            PageSlot moveAfter,
            NotebookPick onPick) {
        if (shownNotebooks == null) {
            shownNotebooks = repository.notebooks();
        }
        // Always lay out in the drawing band so height is the ink area, not the
        // full window (top bars / Place-under tab anchor must not shrink it).
        FrameLayout layoutHost = drawingArea != null ? drawingArea : host;
        LinearLayout list = hierarchyPanel();
        LinearLayout tree = new LinearLayout(this);
        tree.setOrientation(LinearLayout.VERTICAL);
        boolean any = addNotebookHierarchy(tree, null, 0, skipIds, onPick);
        if (!any) {
            tree.addView(panelText(getString(R.string.no_place_target), 14));
        }
        if (includeNewNotebook) {
            addPanelButton(tree, getString(R.string.new_notebook_item), 0,
                    () -> showNameForm(null, null, null, moveAfter));
        }
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.addView(tree, matchWrap());
        int margin = dp(8);
        int hostH = layoutHost.getHeight() > 0 ? layoutHost.getHeight() : root.getHeight();
        // Nearly full available height — only chrome safe margins left empty.
        int maxH = Math.max(dp(280), hostH - 2 * margin - list.getPaddingTop() - list.getPaddingBottom());
        tree.measure(
                View.MeasureSpec.makeMeasureSpec(dp(320), View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int treeH = tree.getMeasuredHeight();
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                treeH > maxH ? maxH : LinearLayout.LayoutParams.WRAP_CONTENT);
        list.addView(scroll, scrollLp);
        showOverlay(list, hierarchyMenuLp(list, layoutHost, anchor, margin), layoutHost);
    }

    /**
     * Tall hierarchy panel: top of the drawing area with safe margins, not
     * tucked above the Move/⋮ chip (that left most of the screen unused).
     */
    private FrameLayout.LayoutParams hierarchyMenuLp(
            View content, FrameLayout host, View anchor, int margin) {
        content.measure(
                View.MeasureSpec.makeMeasureSpec(Math.max(dp(280), host.getWidth() - 2 * margin),
                        View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(host.getHeight(), View.MeasureSpec.AT_MOST));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = margin;
        // Move (page chrome, right) → END; Place under (tab, usually left) → START.
        boolean fromPageChrome = anchor != null && drawingArea != null
                && isDescendantOf(anchor, drawingArea);
        if (fromPageChrome) {
            lp.gravity = Gravity.TOP | Gravity.END;
            lp.rightMargin = margin;
        } else {
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.leftMargin = margin;
        }
        return lp;
    }

    private static boolean isDescendantOf(View child, View ancestor) {
        View current = child;
        while (current != null) {
            if (current == ancestor) {
                return true;
            }
            current = current.getParent() instanceof View ? (View) current.getParent() : null;
        }
        return false;
    }

    private boolean addNotebookHierarchy(
            LinearLayout tree,
            String parentId,
            int depth,
            HashSet<String> skipIds,
            NotebookPick onPick) {
        boolean any = false;
        for (BoardRepository.NotebookInfo each : childrenOf(parentId)) {
            if (skipIds != null && skipIds.contains(each.id)) {
                // Still walk children in case a skipped node has eligible cousins
                // via another branch — skipIds already includes descendants of
                // Place-under source, so children are also skipped.
                continue;
            }
            any = true;
            String id = each.id;
            addPanelButton(tree, each.title, depth, () -> onPick.onPick(id));
            if (addNotebookHierarchy(tree, each.id, depth + 1, skipIds, onPick)) {
                any = true;
            }
        }
        return any;
    }

    /**
     * Cross-notebook append Move (menu path). Places before the dest trailing
     * blank via {@code placePageBefore} so page id + holdFlush / dest-id (v63+)
     * stay intact — not the legacy append tear that dropped the slice id.
     */
    private void movePageToNotebook(PageSlot slot, String targetNotebookId) {
        if (slot == null || slot.page.isBlank() || targetNotebookId == null) {
            return;
        }
        repository.saveInk(slot.page);
        List<Board> destPages = repository.pages(targetNotebookId);
        if (destPages.isEmpty()) {
            return;
        }
        Board trailing = destPages.get(destPages.size() - 1);
        if (!repository.placePageBefore(slot.page.id, trailing.id, targetNotebookId)) {
            LaunchLog.mark("Move menu place-before refused for " + targetNotebookId);
            return;
        }
        reloadPages(effectiveScrollY());
    }

    private interface NotebookPick {
        void onPick(String notebookId);
    }

    /** Roomier than {@link #panel()} for deep Move / Place-under trees. */
    private LinearLayout hierarchyPanel() {
        LinearLayout list = panel();
        list.setMinimumWidth(dp(280));
        list.setPadding(dp(10), dp(8), dp(10), dp(8));
        return list;
    }

    private HashSet<String> descendantIds(String id) {
        HashSet<String> skip = new HashSet<>();
        ArrayList<String> queue = new ArrayList<>();
        queue.add(id);
        for (int i = 0; i < queue.size(); i++) {
            for (BoardRepository.NotebookInfo each : childrenOf(queue.get(i))) {
                if (skip.add(each.id)) {
                    queue.add(each.id);
                }
            }
        }
        skip.remove(id);
        return skip;
    }

    /**
     * Create ({@code id == null}) or rename a notebook. With an {@code anchor}
     * in the top bar the form hangs under it.
     */
    private void showNameForm(String id, View anchor) {
        showNameForm(id, anchor, null, null);
    }

    /**
     * Create ({@code id == null}) or rename a notebook. {@code parentId} is
     * the parent of a notebook created from a child bar; null is top-level.
     */
    private void showNameForm(String id, View anchor, String parentId) {
        showNameForm(id, anchor, parentId, null);
    }

    /**
     * Create / rename. When {@code moveAfter} is set (Move → + New notebook),
     * the page is filed into the created notebook instead of opening it empty.
     */
    private void showNameForm(String id, View anchor, String parentId, PageSlot moveAfter) {
        LinearLayout form = panel();
        form.addView(panelText(getString(id == null ? R.string.new_notebook : R.string.rename_notebook), 15));
        EditText name = new EditText(this);
        name.setSingleLine(true);
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        name.setImeOptions(EditorInfo.IME_ACTION_DONE);
        name.setHint(R.string.notebook_name_hint);
        name.setTextColor(Color.BLACK);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        name.setMinWidth(dp(260));
        if (id != null) {
            name.setText(titleOf(id));
            name.setSelectAllOnFocus(true);
        }
        form.addView(name, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        Runnable submit = () -> {
            String text = name.getText().toString();
            if (text.trim().isEmpty()) {
                return;
            }
            dismissOverlay();
            if (id != null) {
                repository.renameNotebook(id, text);
                rebuildNotebookTabs();
                syncNav();
                return;
            }
            BoardRepository.NotebookInfo created = repository.createNotebook(text, parentId);
            rebuildNotebookTabs();
            if (created == null) {
                syncNav();
            } else if (moveAfter != null) {
                movePageToNotebook(moveAfter, created.id);
            } else {
                openNotebook(created.id);
            }
        };
        name.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submit.run();
                return true;
            }
            return false;
        });
        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.END);
        Button cancel = tinyButton(getString(R.string.cancel), 15);
        onTap(cancel, v -> dismissOverlay());
        Button ok = tinyButton(getString(id == null ? R.string.create : R.string.save), 15);
        styleButton(ok, true);
        onTap(ok, v -> submit.run());
        actions.addView(cancel, wrap());
        LinearLayout.LayoutParams okLp = wrap();
        okLp.leftMargin = dp(8);
        actions.addView(ok, okLp);
        LinearLayout.LayoutParams actionsLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        actionsLp.topMargin = dp(8);
        form.addView(actions, actionsLp);

        if (anchor != null) {
            showOverlay(form, anchoredLp(anchor, form), root);
        } else {
            showOverlay(form, centeredLp(), drawingArea);
        }
        name.requestFocus();
        name.post(() -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(name, InputMethodManager.SHOW_IMPLICIT);
            }
        });
    }

    private void confirmDelete(String id, View anchor) {
        int pages = 0;
        for (Board page : repository.pages(id)) {
            if (!page.isBlank()) {
                pages++;
            }
        }
        LinearLayout box = panel();
        box.addView(panelText(getString(R.string.delete_notebook_title, titleOf(id)), 15));
        box.addView(panelText(pages == 0
                ? getString(R.string.delete_notebook_empty)
                : getResources().getQuantityString(R.plurals.delete_notebook_pages, pages, pages), 13));
        if (!childrenOf(id).isEmpty()) {
            box.addView(panelText(getString(R.string.delete_notebook_children), 13));
        }
        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.END);
        Button cancel = tinyButton(getString(R.string.cancel), 15);
        onTap(cancel, v -> dismissOverlay());
        Button delete = tinyButton(getString(R.string.delete), 15);
        styleButton(delete, true);
        onTap(delete, v -> {
            dismissOverlay();
            repository.deleteNotebook(id);
            scrollByCollection.remove(id);
            int cut = trail.indexOf(id);
            if (cut >= 0) {
                trail.subList(cut, trail.size()).clear();
            }
            boolean opened = id.equals(collectionId);
            rebuildNotebookTabs();
            if (opened) {
                if (!trail.isEmpty()) {
                    openNotebook(trail.get(trail.size() - 1));
                } else {
                    openNotebooks();
                }
            }
        });
        actions.addView(cancel, wrap());
        LinearLayout.LayoutParams lp = wrap();
        lp.leftMargin = dp(8);
        actions.addView(delete, lp);
        LinearLayout.LayoutParams actionsLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        actionsLp.topMargin = dp(8);
        box.addView(actions, actionsLp);
        showOverlay(box, anchoredLp(anchor, box), root);
    }

    /** In-window Settings: Google Sign-In, sync toggle, privacy, version. */
    private void showSettings() {
        settingsGeneration++;
        SyncConfig config = SyncConfig.load(this);
        LinearLayout box = panel();
        box.setMinimumWidth(dp(280));
        box.addView(panelText(getString(R.string.settings), 16));

        TextView account = panelText(
                config.hasGoogleAccount()
                        ? getString(R.string.settings_signed_in_as,
                        config.googleEmail.isEmpty() ? config.googleSub : config.googleEmail)
                        : getString(R.string.settings_not_signed_in),
                13);
        box.addView(account);

        TextView syncStatus = panelText(
                config.enabled()
                        ? getString(R.string.settings_sync_on)
                        : getString(R.string.settings_sync_off),
                13);
        box.addView(syncStatus);

        RoomBoardRepository syncStore = ZettelData.repository(this);
        int pending = syncStore.outboxSize();
        long lastOk = syncStore.lastSyncedAt();
        TextView progress = panelText(
                pending > 0
                        ? getString(R.string.settings_sync_pending, pending)
                        : getString(R.string.settings_sync_caught_up),
                13);
        box.addView(progress);
        TextView lastSync = panelText(
                lastOk <= 0
                        ? getString(R.string.settings_sync_never)
                        : getString(R.string.settings_sync_last, formatSyncTime(lastOk)),
                12);
        box.addView(lastSync);

        String writerBlock = SyncConfig.writerBlockMessage(this);
        if (!writerBlock.isEmpty()) {
            box.addView(panelText(getString(R.string.settings_not_live_writer), 12));
        } else if (config.enabled() && lastOk <= 0) {
            box.addView(panelText(getString(R.string.settings_restoring), 12));
        } else if (config.hasGoogleAccount()) {
            box.addView(panelText(getString(R.string.settings_restore_contract), 11));
        }

        TextView statusLine = panelText("", 13);
        statusLine.setVisibility(View.GONE);
        box.addView(statusLine);

        if (!config.hasGoogleAccount()) {
            Button signIn = tinyButton(getString(R.string.settings_sign_in_google), 15);
            styleButton(signIn, true);
            onTap(signIn, v -> {
                statusLine.setVisibility(View.VISIBLE);
                statusLine.setText(R.string.settings_signing_in);
                signIn.setEnabled(false);
                GoogleAuth.signIn(this, new GoogleAuth.Callback() {
                    @Override
                    public void onSuccess(String email, String claimStatus) {
                        dismissOverlay();
                        String msg;
                        if ("claimed".equals(claimStatus) || "nothing_to_claim".equals(claimStatus)) {
                            msg = getString(R.string.settings_claim_claimed);
                        } else if ("already_yours".equals(claimStatus)) {
                            msg = getString(R.string.settings_claim_yours);
                        } else if ("already_taken".equals(claimStatus)) {
                            msg = getString(R.string.settings_claim_taken);
                        } else {
                            msg = getString(R.string.settings_signed_in_as, email);
                        }
                        showHint(msg + "\n" + getString(R.string.settings_restore_started), true);
                        // Local-only ink (not untouched seeds) → outbox; first sync pulls cloud first.
                        ZettelData.repository(CanvasActivity.this).ensureUnsyncedQueued();
                        SyncScheduler.syncNow(CanvasActivity.this);
                    }

                    @Override
                    public void onError(String message) {
                        statusLine.setText(message);
                        signIn.setEnabled(true);
                    }
                });
            });
            LinearLayout.LayoutParams signLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            signLp.topMargin = dp(8);
            box.addView(signIn, signLp);
        } else {
            Button toggle = tinyButton(
                    config.syncOn
                            ? getString(R.string.settings_sync_off).replace(" — notes stay on this device", "")
                            : getString(R.string.settings_enable_sync),
                    15);
            styleButton(toggle, true);
            onTap(toggle, v -> {
                SyncConfig.setSyncOn(this, !config.syncOn);
                if (!config.syncOn) {
                    ZettelData.repository(this).ensureUnsyncedQueued();
                    SyncScheduler.syncNow(this);
                }
                dismissOverlay();
                showSettings();
            });
            LinearLayout.LayoutParams toggleLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            toggleLp.topMargin = dp(8);
            box.addView(toggle, toggleLp);

            if (config.enabled()) {
                Button syncNow = tinyButton(getString(R.string.settings_sync_now), 15);
                styleButton(syncNow, true);
                onTap(syncNow, v -> {
                    ZettelData.repository(this).ensureUnsyncedQueued();
                    SyncScheduler.syncNow(this);
                    showHint(getString(R.string.settings_sync_started), true);
                    dismissOverlay();
                    showSettings();
                });
                box.addView(syncNow, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            }

            Button signOut = tinyButton(getString(R.string.settings_sign_out), 15);
            onTap(signOut, v -> {
                SyncConfig.signOut(this);
                dismissOverlay();
                showHint(getString(R.string.settings_not_signed_in), true);
            });
            box.addView(signOut, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        }

        // Always show Prepare transfer. When blocked, keep it visible but disabled with a reason
        // (never silently omit — Elton needs a path explanation before Sign-In / live writer).
        // Prepare transfer must stay on-device (sync + POST /sync/prepare-transfer). It must never
        // open a browser — that was the v66/v67 confusion with "Open web companion" below.
        String prepareBlockReason = prepareTransferBlockReason(config, writerBlock, lastOk);
        boolean canPrepare = prepareBlockReason == null;
        Button prepare = tinyButton(getString(R.string.settings_prepare_transfer), 15);
        styleButton(prepare, canPrepare);
        prepare.setEnabled(canPrepare);
        prepare.setAlpha(canPrepare ? 1f : 0.35f);
        if (canPrepare) {
            onTap(prepare, v -> startPrepareTransfer(prepare, statusLine));
        }
        LinearLayout.LayoutParams prepareLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        prepareLp.topMargin = dp(8);
        box.addView(prepare, prepareLp);
        box.addView(panelText(
                canPrepare
                        ? getString(R.string.settings_prepare_transfer_hint)
                        : prepareBlockReason,
                11));

        String sha1 = com.zetteldraw.penpoc.sync.SigningCert.sha1(this);
        if (sha1.isEmpty()) {
            sha1 = "(unknown)";
        }
        box.addView(panelText(
                getString(R.string.settings_oauth_diag, getPackageName(), sha1),
                10));

        box.addView(panelText(
                getString(R.string.settings_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                12));

        Button close = tinyButton(getString(R.string.close), 15);
        styleButton(close, true);
        onTap(close, v -> dismissOverlay());
        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.END);
        actions.addView(close, wrap());
        LinearLayout.LayoutParams actionsLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        actionsLp.topMargin = dp(8);
        box.addView(actions, actionsLp);

        // Browser links last and clearly labeled so they are not mistaken for Prepare transfer.
        TextView linksHeader = panelText(getString(R.string.settings_links_section), 11);
        LinearLayout.LayoutParams linksHeaderLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        linksHeaderLp.topMargin = dp(16);
        box.addView(linksHeader, linksHeaderLp);

        Button privacy = tinyButton(getString(R.string.settings_privacy), 15);
        onTap(privacy, v -> openUrl("https://zetteldraw.com/privacy"));
        box.addView(privacy, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // Dedicated static /transfer page (SSR, works without JS) — not the CSR homepage spinner.
        Button web = tinyButton(getString(R.string.settings_web), 15);
        onTap(web, v -> openUrl("https://zetteldraw.com/transfer"));
        box.addView(web, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // Scroll so Prepare / Links never sit off-screen or share a clipped hit target on Boox.
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        scroll.addView(box, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        int maxH = Math.max(dp(240), Math.round(root.getHeight() * 0.78f));
        scroll.measure(
                View.MeasureSpec.makeMeasureSpec(root.getWidth() - dp(16), View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST));
        FrameLayout.LayoutParams scrollLp = anchoredLp(settingsButton, scroll);
        scrollLp.width = Math.max(dp(280), scroll.getMeasuredWidth());
        scrollLp.height = Math.min(maxH, Math.max(dp(200), scroll.getMeasuredHeight()));
        showOverlay(scroll, scrollLp, root);
    }

    /**
     * Why Prepare transfer is disabled, or {@code null} when it may run.
     * Sign-In is required: prepare posts with the Google bearer and only the
     * live writer may ack.
     */
    private String prepareTransferBlockReason(SyncConfig config, String writerBlock, long lastOk) {
        if (!config.hasGoogleAccount()) {
            return getString(R.string.settings_prepare_need_sign_in);
        }
        if (!config.enabled()) {
            return getString(R.string.settings_prepare_need_sync);
        }
        if (!writerBlock.isEmpty()) {
            return getString(R.string.settings_prepare_need_live);
        }
        if (lastOk <= 0) {
            return getString(R.string.settings_prepare_need_restore);
        }
        return null;
    }

    /**
     * Settings → Prepare transfer: force sync until outbox empty, then ack the
     * server so web Hand off unlocks. Runs entirely on-device (HttpURLConnection to
     * the sync API). Never opens a browser / Custom Tab / WebView.
     */
    private void startPrepareTransfer(Button prepare, TextView statusLine) {
        if (!prepareTransferInFlight.compareAndSet(false, true)) {
            statusLine.setVisibility(View.VISIBLE);
            statusLine.setText(R.string.settings_prepare_transfer_working);
            return;
        }
        final int generation = settingsGeneration;
        prepare.setEnabled(false);
        statusLine.setVisibility(View.VISIBLE);
        statusLine.setText(R.string.settings_prepare_transfer_working);
        showHint(getString(R.string.settings_prepare_transfer_working), false);
        UiExecutors.loader.execute(() -> {
            String err = null;
            boolean blocked = false;
            try {
                SyncConfig cfg = SyncConfig.load(CanvasActivity.this);
                com.zetteldraw.penpoc.data.SyncStore store = ZettelData.repository(CanvasActivity.this);
                store.ensureUnsyncedQueued();
                SyncClient client = new SyncClient(cfg, ZettelDatabase.SCHEMA_VERSION);
                SyncEngine engine = new SyncEngine(
                        store, client, cfg, ZettelDatabase.SCHEMA_VERSION, System::currentTimeMillis);
                SyncEngine.Result r = engine.prepareTransfer();
                if (r.blockedAsNonWriter) {
                    blocked = true;
                    SyncConfig.setWriterBlockMessage(CanvasActivity.this, r.writerBlockMessage);
                    err = getString(R.string.settings_not_live_writer);
                }
            } catch (Throwable e) {
                // Catch Error as well as Exception — concurrent sync races previously escaped
                // Exception-only handlers and killed the activity on 2nd/3rd Prepare taps.
                Log.w("zd-prepare", "prepare transfer failed", e);
                err = e.getMessage() == null || e.getMessage().isEmpty()
                        ? getString(R.string.settings_prepare_transfer_failed)
                        : e.getMessage();
            }
            String message = err;
            boolean wasBlocked = blocked;
            runOnUiThread(() -> {
                prepareTransferInFlight.set(false);
                if (isDestroyed() || generation != settingsGeneration) {
                    // Settings was closed/reopened; do not touch detached views or dismissOverlay.
                    if (message == null) {
                        showHint(getString(R.string.settings_prepare_transfer_done), false);
                    } else {
                        showHint(message, true);
                    }
                    return;
                }
                if (message == null) {
                    // Stay in Settings with durable success copy — do not open a browser.
                    String done = getString(R.string.settings_prepare_transfer_done);
                    statusLine.setText(done);
                    showHint(done, false);
                    prepare.setEnabled(false);
                    prepare.setAlpha(0.35f);
                } else {
                    statusLine.setText(message);
                    showHint(message, true);
                    prepare.setEnabled(!wasBlocked);
                    prepare.setAlpha(wasBlocked ? 0.35f : 1f);
                    if (wasBlocked) {
                        dismissOverlay();
                        showSettings();
                    }
                }
            });
        });
    }

    private static String formatSyncTime(long epochMillis) {
        return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                .format(new Date(epochMillis));
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception ignored) {
            showHint(url, true);
        }
    }

    /** Long-press on the Scratchpad icon: this launch's timeline and the two before it, newest first. */
    private void showLaunchLog() {
        LinearLayout box = panel();
        box.addView(panelText(getString(R.string.launch_log_title), 15));
        TextView text = new TextView(this);
        text.setText(LaunchLog.forViewer(2));
        text.setTextColor(Color.BLACK);
        text.setTypeface(Typeface.MONOSPACE);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        text.setTextIsSelectable(true);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(text);
        box.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.round(root.getHeight() * 0.7f)));
        Button close = tinyButton(getString(R.string.close), 15);
        styleButton(close, true);
        onTap(close, v -> dismissOverlay());
        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.END);
        actions.addView(close, wrap());
        box.addView(actions, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.TOP;
        lp.leftMargin = dp(8);
        lp.rightMargin = dp(8);
        lp.topMargin = topBar.getBottom();
        showOverlay(box, lp, root);
    }

    /** The one frame of a menu or form; its rows and buttons are borderless. */
    private LinearLayout panel() {
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(6), dp(4), dp(6), dp(4));
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(6));
        background.setColor(Color.WHITE);
        background.setStroke(dp(1), Color.BLACK);
        list.setBackground(background);
        list.setClickable(true);
        return list;
    }

    private TextView panelText(String text, int sp) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(Color.BLACK);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setPadding(dp(2), dp(4), dp(2), dp(4));
        view.setMaxWidth(dp(320));
        return view;
    }

    private void addPanelButton(LinearLayout list, String label, Runnable action) {
        addPanelButton(list, label, 0, action);
    }

    private void addPanelButton(LinearLayout list, String label, int depth, Runnable action) {
        Button item = tinyButton(label, 16);
        // Hierarchy indent: 20dp/level (was 16) so nested notebooks read clearly.
        item.setPadding(dp(12 + 20 * depth), 0, dp(12), 0);
        item.setMinimumHeight(dp(48));
        item.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        item.setSingleLine(true);
        item.setEllipsize(TextUtils.TruncateAt.END);
        onTap(item, v -> {
            dismissOverlay();
            if (action != null) {
                action.run();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        list.addView(item, lp);
    }

    private FrameLayout.LayoutParams centeredLp() {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.topMargin = dp(24);
        return lp;
    }

    /** Places {@code content} (in {@link #root}) hanging from the top bar, left-aligned with {@code anchor}. */
    private FrameLayout.LayoutParams anchoredLp(View anchor, View content) {
        content.measure(
                View.MeasureSpec.makeMeasureSpec(root.getWidth() - dp(16), View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(root.getHeight(), View.MeasureSpec.AT_MOST));
        int[] rootAt = new int[2];
        int[] anchorAt = new int[2];
        root.getLocationInWindow(rootAt);
        anchor.getLocationInWindow(anchorAt);
        int left = anchorAt[0] - rootAt[0];
        left = Math.max(dp(8), Math.min(left, root.getWidth() - content.getMeasuredWidth() - dp(8)));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.leftMargin = left;
        lp.topMargin = anchorAt[1] - rootAt[1] + anchor.getHeight() - dp(4);
        return lp;
    }

    /**
     * {@code host} is {@link #drawingArea} for page menus, {@link #root} for
     * top-bar menus. A root-level scrim never covers the tool row: tapping
     * Pen/Eraser/Lasso must reach {@link ToolController#setTool}, not only
     * dismiss (v53 color-menu desync class of bug).
     */
    private void showOverlay(View content, FrameLayout.LayoutParams lp, FrameLayout host) {
        dismissOverlay();
        FrameLayout.LayoutParams contentLp = new FrameLayout.LayoutParams(lp);
        FrameLayout.LayoutParams scrimLp = matchMatch();
        if (host == root && drawingArea != null && drawingArea.getHeight() > 0) {
            int[] rootLoc = new int[2];
            int[] areaLoc = new int[2];
            root.getLocationOnScreen(rootLoc);
            drawingArea.getLocationOnScreen(areaLoc);
            int inset = Math.max(0, areaLoc[1] - rootLoc[1]);
            scrimLp.topMargin = inset;
            // Content was measured in full-root coordinates; shift into the inset scrim.
            contentLp.topMargin = Math.max(0, contentLp.topMargin - inset);
        }
        FrameLayout scrim = new FrameLayout(this);
        scrim.setClickable(true);
        onTap(scrim, v -> dismissOverlay());
        scrim.addView(content, contentLp);
        inkView.hold(PageInkView.Hold.OVERLAY);
        // Attach before publishing overlay so dismissOverlay never sees a null parent.
        host.addView(scrim, scrimLp);
        overlay = scrim;
        updateExcludeRects();
    }

    private void dismissOverlay() {
        FrameLayout scrim = overlay;
        if (scrim == null) {
            return;
        }
        overlay = null;
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(scrim.getWindowToken(), 0);
        }
        ViewParent parent = scrim.getParent();
        if (parent instanceof ViewGroup) {
            ((ViewGroup) parent).removeView(scrim);
        }
        updateExcludeRects();
        if (inkView != null) {
            inkView.release(PageInkView.Hold.OVERLAY);
        }
    }

    private void wipe(PageSlot slot) {
        repository.wipePage(slot.page.id);
        inkView.invalidatePage(slot.page.id);
        if (pagesIn(collectionId).size() != slots.size()) {
            reloadPages(effectiveScrollY());
        } else {
            inkView.redrawAll();
            slot.syncEnabled();
        }
    }

    /** Side effects after {@link ToolController} has synced chrome + ink tool. */
    private void afterToolChanged(PageInkView.Tool tool) {
        if (tool == PageInkView.Tool.LASSO) {
            showHint(getString(R.string.lasso_hint), false);
            if (inkView.hasSelection()) {
                showLassoMenu();
            }
        } else if (!inkView.hasSelection()) {
            showHint("", false);
            hideLassoMenu();
        }
    }

    private void syncNav() {
        styleTabs();
    }

    private void styleTabs() {
        boolean scratch = trail.isEmpty() && SCRATCHPAD.equals(collectionId);
        styleTool(scratchpadTab, scratch);
        for (NotebookTab tab : notebookTabs) {
            boolean current = tab.depth < trail.size() && trail.get(tab.depth).equals(tab.id);
            tab.setCurrent(current);
        }
    }

    /** E-ink selection without fills: a 2 dp black underline (and bold label). */
    private void styleTab(View tab, boolean selected) {
        tab.setBackground(selected ? new Underline(dp(2), dp(12)) : null);
        tab.setSelected(selected);
    }

    private void styleTabLabel(Button label, String text) {
        label.setText(text);
        label.setAllCaps(false);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setMinWidth(0);
        label.setMinimumWidth(0);
        label.setMinHeight(0);
        label.setMinimumHeight(dp(48));
        label.setPadding(dp(14), 0, dp(14), 0);
        label.setStateListAnimator(null);
        label.setBackground(null);
    }

    private static void inkLabel(TextView label, boolean selected) {
        label.setTextColor(Color.BLACK);
        label.setTypeface(Typeface.DEFAULT, selected ? Typeface.BOLD : Typeface.NORMAL);
    }

    /**
     * One notebook in the top bar. Tap opens it; long-press opens it and its
     * Rename / Delete menu.
     */
    private final class NotebookTab extends LinearLayout {
        final String id;
        final int depth;
        final Button label;

        NotebookTab(Context context, BoardRepository.NotebookInfo info, int depth) {
            super(context);
            id = info.id;
            this.depth = depth;
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            label = new Button(context, null, android.R.attr.borderlessButtonStyle);
            styleTabLabel(label, info.title);
            label.setMaxWidth(dp(220));
            onTap(label, v -> openNotebook(id));
            onLongTap(label, v -> {
                openNotebook(id);
                showNotebookMenu(id);
                return true;
            });
            addView(label, wrap());
            setCurrent(false);
        }

        void setCurrent(boolean current) {
            styleTab(this, current);
            inkLabel(label, current);
        }
    }

    private void updateExcludeRects() {
        ArrayList<Rect> rects = new ArrayList<>();
        if (overlay != null) {
            rects.add(new Rect(0, 0, inkView.getWidth(), inkView.getHeight()));
            inkView.setExtraExcludeRects(rects);
            return;
        }
        int[] origin = new int[2];
        int[] loc = new int[2];
        inkView.getLocationOnScreen(origin);
        // The reader owns the stylus wherever it can draw. Every control is a hole,
        // in the surface view's coordinates, including the bars above the page.
        addExclude(rects, topBar, dp(6), origin, loc);
        for (View bar : childBars) {
            addExclude(rects, bar, dp(6), origin, loc);
        }
        addExclude(rects, modeBar, MOVE_EXCLUDE_PAD_PX, origin, loc);
        addExclude(rects, lassoMenu, MOVE_EXCLUDE_PAD_PX, origin, loc);
        addExclude(rects, toolbar, dp(6), origin, loc);
        int top = scroller.getScrollY();
        int bottom = top + scroller.getHeight();
        int first = indexAt(top);
        int last = indexAt(Math.max(top, bottom - 1));
        for (int i = first; i >= 0 && i <= last && i < slots.size(); i++) {
            PageSlot slot = slots.get(i);
            // Link / Reorder / Move are only the word. The link lines are the same kind of hole.
            addExclude(rects, slot.linkButton, MOVE_EXCLUDE_PAD_PX, origin, loc);
            addExclude(rects, slot.reorderButton, MOVE_EXCLUDE_PAD_PX, origin, loc);
            addExclude(rects, slot.moveButton, MOVE_EXCLUDE_PAD_PX, origin, loc);
            addExclude(rects, slot.moreButton, dp(6), origin, loc);
            addExclude(rects, slot.number, dp(6), origin, loc);
            for (View line : slot.linkLines) {
                addExclude(rects, line, MOVE_EXCLUDE_PAD_PX, origin, loc);
            }
        }
        inkView.setExtraExcludeRects(rects);
    }

    /** A couple of pixels: ink reaches the word Move, and the hole is that box. */
    private static final int MOVE_EXCLUDE_PAD_PX = 2;

    private void addExclude(List<Rect> rects, View chrome, int padPx, int[] origin, int[] loc) {
        if (chrome == null || chrome.getWidth() <= 0 || chrome.getVisibility() != View.VISIBLE) {
            return;
        }
        chrome.getLocationOnScreen(loc);
        Rect rect = new Rect(
                loc[0] - origin[0] - padPx,
                loc[1] - origin[1] - padPx,
                loc[0] - origin[0] + chrome.getWidth() + padPx,
                loc[1] - origin[1] + chrome.getHeight() + padPx);
        rects.add(rect);
    }

    private PageSlot slotFor(String pageId) {
        for (PageSlot slot : slots) {
            if (slot.page.id.equals(pageId)) {
                return slot;
            }
        }
        return null;
    }

    /**
     * Pages are {@link #pageHeight} tall, except a page from before v10 whose
     * ink reaches below that: it keeps the old full-window height, so nothing
     * is cropped or shifted.
     */
    /** The dashed line is a mark. On a one-sheet notebook it adds no gap. */
    private int layoutGap(List<Board> pages) {
        if (pages != null && !pages.isEmpty() && pages.get(0).paper != null) {
            return 0;
        }
        return pageGap;
    }

    private int heightOf(Board page) {
        if (page.slicePx > 0) {
            return page.slicePx;
        }
        if (page.createdAt >= shortPagesSince) {
            return pageHeight;
        }
        float bottom = 0f;
        for (InkRenderer.InkStroke stroke : page.strokes) {
            bottom = Math.max(bottom, stroke.bounds.bottom + stroke.maxWidth);
        }
        if (bottom <= pageHeight) {
            return pageHeight;
        }
        return Math.max(legacyPageHeight, (int) Math.ceil(bottom));
    }

    /**
     * Transparent stand-in for one page above the ink surface: carries the
     * page's "k/n" number (top-left, n counting the trailing blank), Link and
     * Reorder with stacked references (bottom-left), Move and ⋮ (bottom-right),
     * and the dashed separator below it. Slots are rebuilt whenever the list
     * changes, so n is always current.
     */
    private final class PageSlot extends FrameLayout {
        final Board page;
        /** 1-based page index; snackbars use this alone, chrome shows {@code index/total}. */
        int index;
        final TextView number;
        final LinearLayout actions;
        final Button linkButton;
        final Button reorderButton;
        final Button moveButton;
        final Button moreButton;
        final ArrayList<View> linkLines = new ArrayList<>();

        PageSlot(Context context, Board page, int index, int total) {
            super(context);
            this.page = page;
            number = new TextView(context);
            // One step up from 11sp, and gray rather than ink-black, so it reads on e-ink.
            number.setTextColor(Color.rgb(0x55, 0x55, 0x55));
            number.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            setIndex(index, total);
            FrameLayout.LayoutParams labelLp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT);
            labelLp.gravity = Gravity.TOP | Gravity.START;
            labelLp.leftMargin = dp(10);
            labelLp.topMargin = dp(6);
            addView(number, labelLp);

            linkButton = tinyButton(getString(R.string.link), 14);
            reorderButton = tinyButton(getString(R.string.reorder), 14);
            moveButton = tinyButton(getString(R.string.move), 14);
            // Word-wide, same row height and vertical center as ⋮ (stylus-sized).
            chromeWord(linkButton);
            chromeWord(reorderButton);
            chromeWord(moveButton);
            moreButton = tinyButton(getString(R.string.notebook_options), 18);
            moreButton.setMinimumHeight(dp(48));
            moreButton.setMinimumWidth(dp(48));
            moreButton.setMinHeight(dp(48));
            moreButton.setMinWidth(dp(48));
            // Optical: END-align for right gutter (rightMargin 8 + padRight 10 → 18dp
            // edge→ink). Vertical: padTop must be 0 (v74's +2dp pushed ⋮ too low).
            // Measured glyph centers (PageChromeOpticalTest): with pad 0/0, ⋮ sits
            // 1dp below Link — padBottom 1dp raises the glyph in the 48dp target.
            moreButton.setPadding(dp(10), 0, dp(10), dp(1));
            moreButton.setIncludeFontPadding(false);
            moreButton.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
            moreButton.setContentDescription(getString(R.string.page_options));
            onTap(linkButton, v -> startLinking(this));
            onTap(reorderButton, v -> startReorder(this));
            onTap(moveButton, v -> showMoveMenu(this));
            onTap(moreButton, v -> showPageMenu(this));

            int mark = page.paper != null ? dp(2) : pageGap;
            // v78: shared bottom margin 10→8dp lowers the whole Link/Reorder/Move/⋮
            // row by 2dp toward the dashed edge (optical nudge). ⋮ padBottom 1 stays.
            int chromeBottom = (page.paper != null ? 0 : pageGap) + dp(8);

            // Left: references above Link · Reorder (both navigate-to-target).
            LinearLayout left = new LinearLayout(context);
            left.setOrientation(LinearLayout.VERTICAL);
            left.setGravity(Gravity.START);
            LinearLayout lines = new LinearLayout(context);
            lines.setOrientation(LinearLayout.VERTICAL);
            fillLinkLines(lines);
            left.addView(lines, wrap());
            LinearLayout leftRow = new LinearLayout(context);
            leftRow.setOrientation(LinearLayout.HORIZONTAL);
            leftRow.setGravity(Gravity.CENTER_VERTICAL);
            leftRow.setBaselineAligned(false);
            leftRow.addView(linkButton, wrap());
            LinearLayout.LayoutParams reorderLp = wrap();
            reorderLp.leftMargin = dp(6);
            leftRow.addView(reorderButton, reorderLp);
            left.addView(leftRow, wrap());
            FrameLayout.LayoutParams leftLp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT);
            leftLp.gravity = Gravity.BOTTOM | Gravity.START;
            leftLp.leftMargin = dp(10);
            leftLp.bottomMargin = chromeBottom;
            addView(left, leftLp);

            // Right: Move (notebook menu) and ⋮ share one chrome row.
            actions = new LinearLayout(context);
            actions.setOrientation(LinearLayout.HORIZONTAL);
            actions.setGravity(Gravity.CENTER_VERTICAL);
            actions.setBaselineAligned(false);
            actions.addView(moveButton, wrap());
            LinearLayout.LayoutParams moreLp = wrap();
            moreLp.leftMargin = dp(6);
            actions.addView(moreButton, moreLp);
            FrameLayout.LayoutParams actionsLp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT);
            actionsLp.gravity = Gravity.BOTTOM | Gravity.END;
            actionsLp.rightMargin = dp(8);
            actionsLp.bottomMargin = chromeBottom;
            addView(actions, actionsLp);

            FrameLayout.LayoutParams dashLp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, mark);
            dashLp.gravity = Gravity.BOTTOM;
            addView(new DashedRule(context), dashLp);
            syncEnabled();
        }

        void setIndex(int index, int total) {
            this.index = index;
            number.setText(index + "/" + total);
            number.setContentDescription(getString(R.string.page_number, index, total));
        }

        /**
         * Blank pages: Link, Reorder, and Move greyed alike. Trailing blank:
         * ⋮ stays visible and uses the same disabled alpha (no invisible chrome).
         */
        void syncEnabled() {
            boolean blank = page.isBlank();
            boolean trailing = isTrailingBlank(this);
            setChromeEnabled(linkButton, !blank);
            setChromeEnabled(reorderButton, !blank);
            setChromeEnabled(moveButton, !blank);
            moreButton.setVisibility(View.VISIBLE);
            setChromeEnabled(moreButton, !trailing);
        }

        private void setChromeEnabled(Button button, boolean enabled) {
            button.setEnabled(enabled);
            button.setAlpha(enabled ? 1f : 0.35f);
        }

        /** Outgoing lines first, then backlinks. A missing page is not shown. */
        private void fillLinkLines(LinearLayout lines) {
            ArrayList<BoardRepository.PageLink> outgoing = new ArrayList<>();
            ArrayList<BoardRepository.PageLink> incoming = new ArrayList<>();
            for (BoardRepository.PageLink link : repository.linksTouching(page.id)) {
                if (page.id.equals(link.sourceId)) {
                    outgoing.add(link);
                } else if (page.id.equals(link.targetId)) {
                    incoming.add(link);
                }
            }
            for (BoardRepository.PageLink link : outgoing) {
                addLinkLine(lines, link.targetId, true);
            }
            for (BoardRepository.PageLink link : incoming) {
                addLinkLine(lines, link.sourceId, false);
            }
        }

        private void addLinkLine(LinearLayout lines, String otherId, boolean outgoing) {
            String ref = pageRef(otherId);
            if (ref == null) {
                return;
            }
            TextView line = new TextView(getContext());
            String label = getString(outgoing ? R.string.link_outgoing : R.string.link_incoming, ref);
            // ← and → at ⋮'s size so both directions match its weight; the page name stays 14sp.
            SpannableString text = new SpannableString(label);
            text.setSpan(new AbsoluteSizeSpan(18, true), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            text.setSpan(new StyleSpan(Typeface.BOLD), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            line.setText(text);
            line.setTextColor(Color.BLACK);
            line.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            line.setPadding(dp(2), dp(4), dp(2), dp(4));
            line.setIncludeFontPadding(false);
            line.setGravity(Gravity.CENTER_VERTICAL);
            line.setMinHeight(dp(32));
            onTap(line, v -> openLinkedPage(otherId));
            lines.addView(line, wrap());
            linkLines.add(line);
        }
    }

    /**
     * Link, Reorder, and Move stay as wide as the word, but match the ⋮ row
     * height so they share one vertical center above the dashed edge.
     */
    private void chromeWord(Button button) {
        button.setMinimumWidth(0);
        button.setMinWidth(0);
        button.setMinimumHeight(dp(48));
        button.setMinHeight(dp(48));
        button.setPadding(dp(4), 0, dp(4), 0);
        button.setIncludeFontPadding(false);
        button.setGravity(Gravity.CENTER);
    }

    private static final class DashedRule extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        DashedRule(Context context) {
            super(context);
            float density = context.getResources().getDisplayMetrics().density;
            paint.setColor(Color.BLACK);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1f, 1.5f * density));
            paint.setPathEffect(new DashPathEffect(new float[]{8f * density, 6f * density}, 0f));
            setLayerType(LAYER_TYPE_SOFTWARE, null);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float y = getHeight() / 2f;
            canvas.drawLine(0f, y, getWidth(), y, paint);
        }
    }

    private LinearLayout row(int gravity) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(gravity);
        row.setPadding(dp(8), dp(6), dp(8), dp(6));
        return row;
    }

    private View rule() {
        View rule = new View(this);
        rule.setBackgroundColor(Color.BLACK);
        return rule;
    }

    private LinearLayout.LayoutParams ruleLp() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
    }

    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private static FrameLayout.LayoutParams matchMatch() {
        return new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT);
    }

    /**
     * Soft click on press ({@link MotionEvent#ACTION_DOWN}), action on click.
     * Sound is down-only so it never double-fires with the click/long-click.
     */
    private void onTap(View view, View.OnClickListener listener) {
        view.setSoundEffectsEnabled(false);
        attachDownClick(view);
        view.setOnClickListener(listener);
    }

    private void onLongTap(View view, View.OnLongClickListener listener) {
        view.setSoundEffectsEnabled(false);
        attachDownClick(view);
        view.setOnLongClickListener(listener);
    }

    /** Play once when the finger/stylus contacts the control; do not consume the event. */
    private static void attachDownClick(View view) {
        view.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                UiClick.play(v.getContext());
            }
            return false;
        });
    }

    private Button tinyButton(String label, int textSp) {
        Button button = new Button(this, null, android.R.attr.borderlessButtonStyle);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, textSp);
        button.setMinHeight(0);
        button.setMinWidth(0);
        button.setMinimumHeight(dp(48));
        button.setMinimumWidth(dp(48));
        button.setPadding(dp(12), 0, dp(12), 0);
        button.setStateListAnimator(null);
        styleButton(button, false);
        return button;
    }

    /** Borderless text button; the primary action of a form is bold. */
    private void styleButton(Button button, boolean primary) {
        button.setBackground(null);
        button.setTextColor(Color.BLACK);
        button.setTypeface(Typeface.DEFAULT, primary ? Typeface.BOLD : Typeface.NORMAL);
    }

    /**
     * A black bar along the bottom edge when selected. When not selected, paints
     * the same slot white so e-ink clears the previous mark (drawing nothing
     * left a stuck Pen underline while Lasso was active).
     */
    private static final class Underline extends Drawable {
        private final Paint paint = new Paint();
        private final int thickness;
        private final int inset;
        private final boolean selected;

        Underline(int thickness, int inset) {
            this(thickness, inset, true);
        }

        Underline(int thickness, int inset, boolean selected) {
            this.thickness = thickness;
            this.inset = inset;
            this.selected = selected;
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            paint.setColor(selected ? Color.BLACK : Color.WHITE);
            canvas.drawRect(b.left + inset, b.bottom - thickness, b.right - inset, b.bottom, paint);
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(ColorFilter filter) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
