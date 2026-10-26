package com.zetteldraw.penpoc;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.DashPathEffect;
import android.graphics.Insets;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
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
import android.widget.Toast;

import com.zetteldraw.penpoc.data.BoardRepository;
import com.zetteldraw.penpoc.data.RoomBoardRepository;
import com.zetteldraw.penpoc.data.ZettelData;
import com.zetteldraw.penpoc.data.db.ZettelDatabase;
import com.zetteldraw.penpoc.nav.CollectionNav;
import com.zetteldraw.penpoc.nav.CollectionOpen;
import com.zetteldraw.penpoc.sync.GoogleAuth;
import com.zetteldraw.penpoc.sync.SyncClient;
import com.zetteldraw.penpoc.sync.SyncConfig;
import com.zetteldraw.penpoc.sync.SyncEngine;
import com.zetteldraw.penpoc.sync.SyncScheduler;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One compact top bar: Scratchpad icon | active notebook (tap toggles the same
 * multi-column hierarchy menu as Move / Place under; create via + New notebook
 * inside that menu) | undo/redo | pen/eraser/lasso | settings.
 * No persistent top-level notebook strip, no child-notebook bars, and no bar +.
 * Long-press the active notebook for Rename / Place / Delete. Link / Reorder
 * keep the bottom Confirm/Cancel snackbar. Each collection is a continuous
 * vertical scroll of its own pages.
 */
public final class CanvasActivity extends Activity {
    private static final long SCROLL_SETTLE_MS = 160;
    private static final String SCRATCHPAD = "scratchpad";
    private static final String PREFS = "canvas";
    /** Most recently opened normal notebook — chip label while on Scratchpad. */
    private static final String PREF_LAST_NOTEBOOK = "lastNotebookId";
    /**
     * Scroll intent lives in {@link CollectionNav} (Y only — not page count).
     * Length SoT is Ready ink-log via the repository. Remember via
     * {@link #effectiveScrollY()}, never a ScrollView that has not yet applied
     * {@link #pendingScrollY}. See docs/ink-sot-rewrite.md.
     */
    private BoardRepository repository;
    private CollectionNav collectionNav;
    private String collectionId = SCRATCHPAD;
    /** Last opened notebook; null until one is chosen or when none exist. */
    private String notebookId;

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
     * Bottom strip for Link / Reorder Confirm/Cancel. Floated on {@link #root},
     * not folded into the compact top nav. Move and Place under use the shared
     * notebook hierarchy menu instead.
     */
    private LinearLayout modeBar;
    private TextView modeText;
    private Button modeConfirm;
    /**
     * Post-delete undo chip (Material-style). Separate from Link/Reorder
     * {@link #modeBar} Confirm/Cancel — real delete holds one Undo payload.
     */
    private LinearLayout deleteUndoBar;
    private TextView deleteUndoText;
    /** Timeout / dismiss commits the deferred tear (does not undo). */
    private final Runnable clearDeleteUndo = this::commitDeleteUndo;
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
    private PageInkView inkView;
    private PageScroller scroller;
    private LinearLayout pageColumn;
    /** Full-width control under the last real page (or alone when empty). */
    private Button createPageButton;
    private ImageButton scratchpadTab;
    /** Real notebook name (MRU on Scratchpad); tap toggles hierarchy switcher. */
    private Button activeNotebookButton;
    private ImageButton penButton;
    private ImageButton eraserButton;
    private ImageButton lassoButton;
    private ToolController tools;
    private ImageButton settingsButton;
    private ImageButton undoButton;
    private ImageButton redoButton;
    private TextView toolHint;
    private final Runnable clearHint = () -> toolHint.setText("");
    private final ArrayList<PageSlot> slots = new ArrayList<>();
    /** Top of each slot, plus the column bottom. Zero stride means the heights differ. */
    private int[] slotTops = new int[0];
    private int slotStride;

    /** Height of a page: the drawing area minus the gap, so a whole page and its controls fit on screen. */
    private volatile int pageHeight;
    /** Full-window page height of v4–v9; old pages whose ink reaches below {@link #pageHeight} keep it. */
    /** Last root window height — layout resize gate only (not ink migration). */
    private volatile int legacyPageHeight;
    private int pageGap;
    private boolean scrolling;
    private boolean pagesLoaded;
    private int pendingScrollY = -1;
    /** Not-Ready / empty / failed open retries on this interval until shown or superseded. */
    private static final long LOAD_RETRY_MS = 1_000;
    private static final String TAG = "zd-canvas";
    /** Times a drawing-area resize threw the page list away. Link must not move this. */
    static final java.util.concurrent.atomic.AtomicInteger pageReflows = new java.util.concurrent.atomic.AtomicInteger();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable remoteChanged = this::onRemoteChange;
    /** The page list on screen, for re-laying it out when the page height changes. */
    private List<Board> currentPages;
    private Integer pendingKeepScroll;
    private List<BoardRepository.NotebookInfo> shownNotebooks;
    /** Selected notebook and its ancestors, root first. Empty on the Scratchpad. */
    private final ArrayList<String> trail = new ArrayList<>();
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
        collectionNav = new CollectionNav(prefs);
        pageGap = dp(24);
        // Restored after inkView is created below.

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.WHITE);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        root.addView(column, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        topBar = buildTopBar();
        topBar.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> updateExcludeRects());
        column.addView(topBar, matchWrap());
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
        deleteUndoBar = buildDeleteUndoBar();
        FrameLayout.LayoutParams undoLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        undoLp.gravity = Gravity.BOTTOM;
        undoLp.leftMargin = dp(10);
        undoLp.rightMargin = dp(10);
        undoLp.bottomMargin = dp(12);
        root.addView(deleteUndoBar, undoLp);
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
                // Wipe/delete/trim can change Ready slice count; reload when lists disagree.
                if (slot != null && !loading()
                        && pagesIn(collectionId).size() != slots.size()) {
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
        createPageButton = new Button(this, null, android.R.attr.borderlessButtonStyle);
        // Same full-width block; visible mark is "+" (label kept for a11y).
        createPageButton.setText(R.string.create_new_page_mark);
        createPageButton.setContentDescription(getString(R.string.create_new_page));
        createPageButton.setAllCaps(false);
        createPageButton.setTextColor(Color.BLACK);
        createPageButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        createPageButton.setGravity(Gravity.CENTER);
        createPageButton.setMinHeight(dp(56));
        createPageButton.setPadding(dp(12), dp(14), dp(12), dp(14));
        // No top stroke — clashes with the dashed page separator above the button.
        createPageButton.setBackground(createNewPageButtonBackground());
        createPageButton.setOnClickListener(v -> onCreateNewPage());
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
        tools.bind(penButton, eraserButton, lassoButton, topBar);
        tools.setTool(PageInkView.Tool.PEN);
        syncHistory();
        // (1) App shell only. Default selection = Scratchpad chrome; no open yet.
        // (2) First real page height → openScratchpad — exact same path as tapping it.
        styleTabs();
        inkView.setLive(true);
    }

    /**
     * Page height follows the drawing area: recomputed on every size change,
     * not fixed by the first layout. Sizes too small to hold a page are ignored.
     * First real size opens the default selection via the shared tap path.
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
            if (first) {
                if (loading()) {
                    // Rare: user tapped a tab before height; finish that shared open.
                    startLoad(collectionId, pendingKeepScroll, false);
                } else {
                    // Boot default: exact same select/open as tapping Scratchpad.
                    openScratchpad(null);
                }
            } else if (currentPages != null && !loading()) {
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
        commitDeleteUndo();
        inkView.pauseLive();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        commitDeleteUndo();
        repository.removeRemoteChangeListener(remoteChanged);
        scroller.removeCallbacks(scrollSettled);
        toolHint.removeCallbacks(clearHint);
        inkView.close();
        super.onDestroy();
    }

    /**
     * Single chrome strip: Scratchpad | notebook chip (name) | hint |
     * undo/redo | tools | settings. Chip always toggles the shared hierarchy
     * switcher (including when that notebook is already open — no ▾).
     */
    private LinearLayout buildTopBar() {
        LinearLayout bar = row(Gravity.CENTER_VERTICAL);
        scratchpadTab = iconButton(R.drawable.ic_scratchpad, R.string.scratchpad);
        onTap(scratchpadTab, v -> openScratchpad(null));
        bar.addView(scratchpadTab, iconLp(0));
        bar.addView(rule(), barDividerLp());

        activeNotebookButton = tinyButton("", 16);
        activeNotebookButton.setContentDescription(getString(R.string.switch_notebook));
        activeNotebookButton.setAllCaps(false);
        activeNotebookButton.setSingleLine(true);
        activeNotebookButton.setEllipsize(TextUtils.TruncateAt.END);
        activeNotebookButton.setMaxWidth(dp(220));
        activeNotebookButton.setMinHeight(dp(48));
        activeNotebookButton.setMinimumHeight(dp(48));
        activeNotebookButton.setPadding(dp(10), 0, dp(10), 0);
        activeNotebookButton.setStateListAnimator(null);
        onTap(activeNotebookButton, v -> showNotebookSwitcher());
        activeNotebookButton.setOnLongClickListener(v -> {
            String open = notebookKey(collectionId);
            if (open != null) {
                showNotebookMenu(open);
                return true;
            }
            return false;
        });
        bar.addView(activeNotebookButton, wrap());

        toolHint = new TextView(this);
        toolHint.setTextColor(Color.DKGRAY);
        toolHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        toolHint.setSingleLine(true);
        toolHint.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        hintLp.leftMargin = dp(8);
        bar.addView(toolHint, hintLp);

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
        onTap(settingsButton, v -> toggleSettings());
        bar.addView(undoButton, iconLp(0));
        bar.addView(redoButton, iconLp(4));
        bar.addView(rule(), barDividerLp());
        bar.addView(penButton, iconLp(0));
        bar.addView(eraserButton, iconLp(6));
        bar.addView(lassoButton, iconLp(6));
        bar.addView(rule(), barDividerLp());
        bar.addView(settingsButton, iconLp(0));
        rebuildNotebookTabs(null);
        return bar;
    }

    private LinearLayout.LayoutParams barDividerLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(1), dp(32));
        lp.leftMargin = dp(8);
        lp.rightMargin = dp(8);
        return lp;
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

    /** {@code notebooks} null: not loaded yet — chip uses MRU / first seed when styled. */
    private void rebuildNotebookTabs(List<BoardRepository.NotebookInfo> notebooks) {
        if (notebooks != null && sameNotebooks(notebooks)) {
            styleTabs();
            return;
        }
        shownNotebooks = notebooks == null ? null : new ArrayList<>(notebooks);
        pruneTrail();
        if (shownNotebooks != null && !SCRATCHPAD.equals(collectionId)) {
            BoardRepository.NotebookInfo open = notebookById(collectionId);
            if (open != null && (trail.isEmpty() || !trail.get(trail.size() - 1).equals(collectionId))) {
                openNotebook(collectionId, null);
                return;
            }
        }
        styleTabs();
    }

    private boolean sameNotebooks(List<BoardRepository.NotebookInfo> notebooks) {
        if (shownNotebooks == null || shownNotebooks.size() != notebooks.size()) {
            return false;
        }
        for (int i = 0; i < notebooks.size(); i++) {
            BoardRepository.NotebookInfo a = shownNotebooks.get(i);
            BoardRepository.NotebookInfo b = notebooks.get(i);
            if (!a.id().equals(b.id()) || !a.title().equals(b.title()) || !Objects.equals(a.parentId(), b.parentId())) {
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
            if (info == null || !Objects.equals(info.parentId(), parent)) {
                break;
            }
            parent = info.id();
            keep++;
        }
        if (keep < trail.size()) {
            trail.subList(keep, trail.size()).clear();
        }
    }

    private List<BoardRepository.NotebookInfo> childrenOf(String parentId) {
        ArrayList<BoardRepository.NotebookInfo> kids = new ArrayList<>();
        if (shownNotebooks == null) {
            return kids;
        }
        for (BoardRepository.NotebookInfo each : shownNotebooks) {
            if (Objects.equals(each.parentId(), parentId)) {
                kids.add(each);
            }
        }
        return kids;
    }

    private BoardRepository.NotebookInfo notebookById(String id) {
        return notebookByIdIn(shownNotebooks, id);
    }

    /**
     * Tap the notebook chip:
     * <ul>
     *   <li>Chip notebook ≠ active collection → open that notebook (no menu).
     *       On Scratchpad the chip is MRU / first seed — first tap selects it.</li>
     *   <li>Chip notebook is already active → toggle the hierarchy menu.</li>
     * </ul>
     */
    private void showNotebookSwitcher() {
        if (overlay != null) {
            dismissOverlay();
            return;
        }
        String chipId = chipNotebookId();
        String open = notebookKey(collectionId);
        if (chipId != null && !chipId.equals(open)) {
            openNotebook(chipId, null);
            return;
        }
        if (shownNotebooks == null) {
            shownNotebooks = repository.notebooks();
        }
        showNotebookHierarchyMenu(activeNotebookButton, drawingArea, null, true, null,
                id -> openNotebook(id, null));
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
    private void openNotebooks(List<BoardRepository.NotebookInfo> notebooks) {
        String target = notebookId != null && notebookByIdIn(notebooks, notebookId) != null
                ? notebookId
                : null;
        if (target == null && !notebooks.isEmpty()) {
            target = notebooks.get(0).id();
        }
        if (target == null) {
            notebookId = null;
            openScratchpad(null);
        } else {
            openNotebook(target, null);
        }
    }

    /**
     * After the open notebook vanishes (delete / pull): trail parent, else first
     * remaining / Scratchpad. {@code notebooks} null → refresh from repository.
     */
    private void reopenAfterNotebookGone(List<BoardRepository.NotebookInfo> notebooks) {
        if (!trail.isEmpty()) {
            openNotebook(trail.get(trail.size() - 1), null);
        } else {
            openNotebooks(notebooks != null ? notebooks : repository.notebooks());
        }
    }

    /**
     * Select + open Scratchpad. Cold boot after shell and the Scratchpad tab
     * both call this — one function, no parallel cold-start open helper.
     */
    private void openScratchpad(Integer keepScroll) {
        trail.clear();
        styleTabs();
        openCollection(SCRATCHPAD, keepScroll);
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
            next.add(0, info.id());
            cursor = info.parentId();
        }
        if (next.isEmpty()) {
            openNotebooks(repository.notebooks());
            return;
        }
        trail.clear();
        trail.addAll(next);
        notebookId = id;
        rememberLastNotebook(id);
        styleTabs();
        openCollection(id, keepScroll);
    }

    private void rememberLastNotebook(String id) {
        if (id == null) {
            return;
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(PREF_LAST_NOTEBOOK, id)
                .apply();
    }

    /** Chip title target: open notebook, else MRU, else first live notebook. */
    private String chipNotebookId() {
        String open = notebookKey(collectionId);
        if (open != null && notebookById(open) != null) {
            return open;
        }
        String last = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString(PREF_LAST_NOTEBOOK, null);
        if (last != null && notebookById(last) != null) {
            return last;
        }
        if (shownNotebooks != null) {
            for (BoardRepository.NotebookInfo each : shownNotebooks) {
                if (each != null && each.id() != null) {
                    return each.id();
                }
            }
        }
        List<BoardRepository.NotebookInfo> all = repository.notebooks();
        return all.isEmpty() ? null : all.get(0).id();
    }

    private String titleOf(String id) {
        BoardRepository.NotebookInfo info = notebookById(id);
        if (info == null) {
            info = notebookByIdIn(repository.notebooks(), id);
        }
        return info == null ? "" : info.title();
    }

    private void openCollection(String id, Integer keepScroll) {
        dismissOverlay();
        String previous = collectionId;
        boolean switched = !id.equals(previous);
        if (switched) {
            inkView.clearHistory();
        }
        // Persist the collection we leave using the intended Y (pending apply wins).
        // Only when that collection was Ready and shown — never from a partial load.
        rememberOpenScroll();
        pagesLoaded = true;
        collectionId = id;
        styleTabs();
        // Snappy path: Ready sheet → same showReady spine as async load.
        String notebook = notebookKey(id);
        if (pageHeight > 0 && repository.isSheetReady(notebook)) {
            List<Board> ready = repository.pages(notebook);
            if (ready != null) {
                ++loadGeneration;
                // Same tab chrome as async finishLoad (boot snappy must not skip this).
                rebuildNotebookTabs(repository.notebooks());
                showReady(id, ready, keepScroll);
                return;
            }
        }
        // Not Ready: no page chrome / viewport until ink is Ready (shared open
        // path — cold start and every notebook tap). Nav tabs stay; blank pages
        // that tempt drawing are never shown. Keep pages during Link/Reorder so
        // the mode bar target ref survives until Ready returns.
        if (linkSourceId == null && reorderSourceId == null) {
            clearShownPages();
            showOpenLoadingShell();
        }
        startLoad(id, keepScroll, true);
    }

    /**
     * Open failed (OOM / Error on ensure or paint): stay alive with tabs + empty
     * chrome; leave {@code .zdl} intact. Not the cold-start product path.
     */
    private void escapeOpenFailure(Throwable cause) {
        Log.e(TAG, "open failed; staying on empty chrome", cause);
        LaunchLog.mark("open escape: " + cause);
        try {
            if (repository instanceof RoomBoardRepository) {
                ((RoomBoardRepository) repository).invalidateOpenSheet(notebookKey(collectionId));
            }
        } catch (RuntimeException ignored) {
        }
        ++loadGeneration;
        shownGeneration = loadGeneration;
        clearShownPages();
        inkView.release(PageInkView.Hold.LOADING);
        try {
            rebuildNotebookTabs(repository.notebooks());
        } catch (RuntimeException e) {
            Log.e(TAG, "notebooks() after open escape failed", e);
        }
        firstListShown = true;
        Toast.makeText(this, "Could not open sheet — tap a notebook", Toast.LENGTH_LONG).show();
    }

    /** Drops painted slots so a pending load cannot be mistaken for the open notebook. */
    private void clearShownPages() {
        currentPages = null;
        pageColumn.removeAllViews();
        slots.clear();
        slotTops = new int[]{0};
        slotStride = 0;
        pendingScrollY = 0;
        inkView.setPages(java.util.Collections.emptyList(), new int[0], pageGap, 0);
        refreshModeBar();
        updateExcludeRects();
    }

    /** Ready-list scroll gate for leave-collection and process pause. */
    private void rememberOpenScroll() {
        if (!pagesLoaded || loading() || collectionId == null || !collectionShownReady()) {
            return;
        }
        int y = effectiveScrollY();
        String anchor = collectionNav.anchorPageId(currentPages, y, pageLayout());
        int count = currentPages == null ? 0 : currentPages.size();
        collectionNav.rememberScroll(collectionId, y, count, anchor);
    }

    /** True when the on-screen list came from a Ready sheet. */
    private boolean collectionShownReady() {
        return currentPages != null && !currentPages.isEmpty()
                && repository.isSheetReady(notebookKey(collectionId));
    }

    private CollectionNav.PageLayout pageLayout() {
        return new CollectionNav.PageLayout() {
            @Override
            public int heightOf(Board page) {
                return CanvasActivity.this.heightOf(page);
            }

            @Override
            public int gap(List<Board> pages) {
                return layoutGap(pages);
            }

            @Override
            public int lastInkOffset(List<Board> pages) {
                return CanvasActivity.this.lastInkOffset(pages);
            }
        };
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
     * Shared open/load spine for every collection (Scratchpad tap, notebook tap,
     * boot-after-shell). Ensure Ready off the main thread, then {@link #showReady}.
     * When {@code pageHeight} is still 0, records intent only — first layout
     * resumes via the same {@link #openScratchpad} / {@link #openNotebook} path.
     * {@code keepScroll} null means restore a remembered scroll, else first-open
     * last page / last inked.
     */
    private void startLoad(String id, Integer keepScroll, boolean holdPen) {
        pendingKeepScroll = keepScroll;
        if (holdPen) {
            inkView.hold(PageInkView.Hold.LOADING);
        }
        // No ensure / pages until height is known.
        if (pageHeight <= 0) {
            ++loadGeneration;
            return;
        }
        int generation = ++loadGeneration;
        requestReadyPages(generation, id, keepScroll, false);
    }

    /**
     * One ensure→pages→paint pass on the shared open spine (Scratchpad and every
     * notebook). No page chrome until Ready (ensureSheet is full-replay only).
     * Retry only when not shown and ensure is idle — never parallel full replays.
     * Caller guarantees {@code pageHeight > 0}.
     */
    private void requestReadyPages(int generation, String id, Integer keepScroll, boolean retry) {
        scheduleLoadRetry(generation, id, keepScroll);
        String notebook = notebookKey(id);
        String name = label(id);
        Executor executor = retry ? UiExecutors.retryLoader : UiExecutors.loader;
        boolean first = !firstListShown;
        boolean scratch = notebook == null;
        try {
            executor.execute(() -> {
                List<BoardRepository.NotebookInfo> notebooks;
                List<Board> pages;
                boolean ready;
                long started = SystemClock.uptimeMillis();
                try {
                    notebooks = repository.notebooks();
                    // Tabs don't wait for the page list.
                    main.post(() -> {
                        if (generation == loadGeneration && !isDestroyed()) {
                            rebuildNotebookTabs(notebooks);
                        }
                    });
                    // ensureSheet is Ready-only — no structure-before-strokes dual
                    // (v153 draw-on-blank race deleted at the API, not just UI null).
                    ready = repository.ensureSheet(notebook, pageHeight);
                    if (!ready) {
                        // Deleted notebook: ensureSheet fails; still post so UI can leave it.
                        if (notebook != null && notebookByIdIn(notebooks, notebook) == null) {
                            main.post(() -> finishLoad(new Loaded(
                                    generation, id, keepScroll, notebooks,
                                    java.util.Collections.emptyList())));
                            return;
                        }
                        // Scratchpad/huge sheet refused (Error path): stay alive.
                        if (scratch) {
                            main.post(() -> {
                                if (generation == loadGeneration && !isDestroyed()) {
                                    escapeOpenFailure(new IllegalStateException(
                                            "ensureSheet returned false for Scratchpad"));
                                }
                            });
                            return;
                        }
                        // Not Ready — do not show; armed retry.
                        LaunchLog.mark("page list " + name + " sheet not ready");
                        return;
                    }
                    pages = repository.pages(notebook);
                    // Empty Ready sheet is valid — Create new page button only.
                    if (first) {
                        LaunchLog.mark("page list " + name + " read: " + pages.size() + " pages, "
                                + notebooks.size() + " notebooks in "
                                + (SystemClock.uptimeMillis() - started) + " ms");
                    }
                } catch (Throwable e) {
                    // Incl. OutOfMemoryError — do not die; empty chrome, files intact.
                    Log.e(TAG, "page list load failed", e);
                    LaunchLog.mark("page list load failed: " + e);
                    main.post(() -> {
                        if (generation == loadGeneration && !isDestroyed()) {
                            escapeOpenFailure(e);
                        }
                    });
                    return;
                }
                main.post(() -> finishLoad(new Loaded(generation, id, keepScroll, notebooks, pages)));
            });
        } catch (RuntimeException e) {
            // Armed retry still owns recovery — do not bump loadGeneration here.
            Log.e(TAG, "page list load could not start", e);
            LaunchLog.mark("page list load could not start: " + e);
        }
    }

    /**
     * Nav-only open shell while ensure runs: no page slots, empty ink viewport.
     * Cleared by {@link #layoutPages} when Ready arrives.
     */
    private void showOpenLoadingShell() {
        if (pageColumn.getChildCount() > 0) {
            return;
        }
        TextView loading = new TextView(this);
        loading.setText(R.string.open_loading);
        loading.setTextSize(16f);
        loading.setGravity(Gravity.CENTER);
        loading.setPadding(dp(24), dp(48), dp(24), dp(48));
        pageColumn.addView(loading, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LaunchLog.mark("open loading shell " + label(collectionId));
    }

    private void scheduleLoadRetry(int generation, String id, Integer keepScroll) {
        main.postDelayed(() -> {
            if (generation != loadGeneration || shownGeneration == generation || isDestroyed()) {
                return;
            }
            // Dense ensure often takes >1s — join the same flight; do not start another.
            if (repository.isSheetEnsuring(notebookKey(id))) {
                scheduleLoadRetry(generation, id, keepScroll);
                return;
            }
            LaunchLog.mark("page list " + label(id) + " not shown after " + LOAD_RETRY_MS
                    + " ms; loading again");
            requestReadyPages(generation, id, keepScroll, true);
        }, LOAD_RETRY_MS);
    }

    /** Launch-log name: Scratchpad as-is, else quoted title via {@link #titleOf}. */
    private String label(String id) {
        if (SCRATCHPAD.equals(id)) {
            return SCRATCHPAD;
        }
        String title = titleOf(id);
        return title.isEmpty() ? id : "'" + title + "'";
    }

    private void finishLoad(Loaded loaded) {
        if (loaded.generation() != loadGeneration || shownGeneration == loaded.generation() || isDestroyed()) {
            return;
        }
        rebuildNotebookTabs(loaded.notebooks());
        if (!SCRATCHPAD.equals(loaded.id()) && notebookById(loaded.id()) == null) {
            // The notebook went away (deleted elsewhere).
            reopenAfterNotebookGone(loaded.notebooks());
            return;
        }
        // Empty Ready list is valid — Create new page button only.
        // Ready first-paint runs off main via PageInkView / UiExecutors.renderer.
        showReady(loaded.id(), loaded.pages(), loaded.keepScroll());
        if (!firstListShown) {
            firstListShown = true;
            LaunchLog.mark("first page list on screen: " + label(loaded.id()) + ", " + loaded.pages().size()
                    + " pages, " + loaded.notebooks().size() + " notebook tabs");
        }
    }

    /**
     * Paint a Ready page list via {@link CollectionOpen} (scroll bind only).
     * Length SoT remains the repository / ink sheet.
     */
    private void showReady(String id, List<Board> pages, Integer keepScroll) {
        CollectionOpen.Bind bind = CollectionOpen.bindScroll(
                collectionNav, id, pages, keepScroll, SCRATCHPAD.equals(id), pageLayout());
        if (bind == null) {
            return;
        }
        showPages(bind.pages(), bind.scrollY());
        styleTabs();
    }

    private static BoardRepository.NotebookInfo notebookByIdIn(
            List<BoardRepository.NotebookInfo> notebooks, String id) {
        if (notebooks == null || id == null) {
            return null;
        }
        for (BoardRepository.NotebookInfo each : notebooks) {
            if (each.id().equals(id)) {
                return each;
            }
        }
        return null;
    }

    /** One finished background load (ensureSheet succeeded or notebook gone). */
    private record Loaded(
            int generation,
            String id,
            Integer keepScroll,
            List<BoardRepository.NotebookInfo> notebooks,
            List<Board> pages) {
    }

    private boolean loading() {
        return loadGeneration != shownGeneration;
    }

    private void reloadPages(int targetScrollY) {
        List<Board> pages = pagesIn(collectionId);
        // Sheet vanished mid-session — re-ensure. Empty Ready list is fine.
        if (pages.isEmpty() && !repository.isSheetReady(notebookKey(collectionId))) {
            startLoad(collectionId, targetScrollY, false);
            return;
        }
        ++loadGeneration;
        // Same Ready paint + scroll spine as snappy open / async load.
        showReady(collectionId, pages, targetScrollY);
    }

    private void showPages(List<Board> pages, int targetScrollY) {
        shownGeneration = loadGeneration;
        inkView.release(PageInkView.Hold.LOADING);
        layoutPages(pages, targetScrollY);
    }

    /** Explicit Create new page — appends a real Ready slice. */
    private void onCreateNewPage() {
        if (loading() || pageHeight <= 0) {
            return;
        }
        Board created = repository.createPage(notebookKey(collectionId));
        if (created == null) {
            showHint(getString(R.string.create_page_failed), true);
            return;
        }
        List<Board> pages = pagesIn(collectionId);
        int target = CollectionNav.scrollYBefore(pages, Math.max(0, pages.size() - 1), pageLayout());
        ++loadGeneration;
        showReady(collectionId, pages, target);
    }

    /**
     * White fill with left/right/bottom hairlines only — no top edge so it does
     * not double the dashed page separator above the control.
     */
    private Drawable createNewPageButtonBackground() {
        int stroke = dp(1);
        GradientDrawable fill = new GradientDrawable();
        fill.setColor(Color.WHITE);
        ColorDrawable left = new ColorDrawable(Color.BLACK);
        ColorDrawable right = new ColorDrawable(Color.BLACK);
        ColorDrawable bottom = new ColorDrawable(Color.BLACK);
        LayerDrawable layers = new LayerDrawable(new Drawable[]{fill, left, right, bottom});
        layers.setLayerWidth(1, stroke);
        layers.setLayerGravity(1, Gravity.LEFT | Gravity.FILL_VERTICAL);
        layers.setLayerWidth(2, stroke);
        layers.setLayerGravity(2, Gravity.RIGHT | Gravity.FILL_VERTICAL);
        layers.setLayerHeight(3, stroke);
        layers.setLayerGravity(3, Gravity.BOTTOM | Gravity.FILL_HORIZONTAL);
        return layers;
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
        // Full-width Create new page (+) control under the last real page.
        LinearLayout.LayoutParams createLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        if (createPageButton.getParent() instanceof ViewGroup) {
            ((ViewGroup) createPageButton.getParent()).removeView(createPageButton);
        }
        pageColumn.addView(createPageButton, createLp);
        createPageButton.setEnabled(!loading());
        inkView.setPages(pages, heights, gap, targetScrollY);
        pendingScrollY = Math.max(0, targetScrollY);
        pageColumn.requestLayout();
        refreshModeBar();
    }

    /**
     * Scroll offset of the last slice that already has ink. Each slice keeps
     * its own height, so a tall page from before v10 and a short page after it
     * do not share one stride.
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
        return CollectionNav.scrollYBefore(pages, index, pageLayout());
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

    /** Bottom chip after real Delete: message + Undo (not Confirm/Cancel). */
    private LinearLayout buildDeleteUndoBar() {
        LinearLayout stack = new LinearLayout(this);
        stack.setOrientation(LinearLayout.HORIZONTAL);
        stack.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(6));
        background.setColor(Color.WHITE);
        background.setStroke(dp(1), Color.BLACK);
        stack.setBackground(background);
        stack.setVisibility(View.GONE);
        stack.setPadding(dp(8), dp(8), dp(8), dp(8));
        stack.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> updateExcludeRects());
        deleteUndoText = new TextView(this);
        deleteUndoText.setTextColor(Color.BLACK);
        deleteUndoText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        deleteUndoText.setText(R.string.page_deleted);
        // Tap the message to dismiss (commit) without Undo.
        onTap(deleteUndoText, v -> commitDeleteUndo());
        stack.addView(deleteUndoText, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button undo = tinyButton(getString(R.string.undo), 15);
        styleButton(undo, true);
        onTap(undo, v -> undoPendingDelete());
        LinearLayout.LayoutParams undoLp = wrap();
        undoLp.leftMargin = dp(8);
        stack.addView(undo, undoLp);
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

    /** Link or Reorder: one clear + source capture + chrome. */
    private void startMode(PageSlot slot, boolean reorder) {
        clearModeSources();
        if (reorder) {
            reorderSourceId = slot.page.id;
            reorderSourceRef = refOnScreen(slot);
            reorderSourceNotebookId = notebookKey(collectionId);
        } else {
            linkSourceId = slot.page.id;
            linkSourceRef = refOnScreen(slot);
        }
        beginModeChrome();
    }

    private void beginModeChrome() {
        commitDeleteUndo();
        hideLassoMenu();
        modeBar.setVisibility(View.VISIBLE);
        refreshModeBar();
        updateExcludeRects();
    }

    /** Drop Link/Reorder source state — one clear before start or cancel. */
    private void clearModeSources() {
        linkSourceId = null;
        linkSourceRef = null;
        reorderSourceId = null;
        reorderSourceRef = null;
        reorderSourceNotebookId = null;
    }

    private void cancelMode() {
        clearModeSources();
        endModeChrome();
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

    private void endModeChrome() {
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
        cancelMode();
        reloadPages(scroll);
    }

    private void confirmReorder() {
        PageSlot target = reorderTargetSlot();
        boolean append = reorderAppendTarget();
        // Blocked while loading: slots can still show the previous notebook.
        if (reorderSourceId == null || reorderConfirmBlocked(target, append)) {
            refreshModeBar();
            return;
        }
        String sourceId = reorderSourceId;
        // Destination is the open collection (snackbar name), not the target
        // page's sheet — that page can be a stale slot from the parent notebook.
        String destNotebookId = notebookKey(collectionId);
        // Flush any pending pen-up before the tear. Cross-notebook place holds
        // sheet flushes so these writer tasks cannot persist a source tear
        // before the destination rewrite (see RoomBoardRepository holdFlush).
        if (target != null) {
            repository.saveInk(target.page);
        }
        PageSlot onScreen = slotFor(sourceId);
        Board source = onScreen == null ? null : onScreen.page;
        if (source == null) {
            source = livePageIn(reorderSourceNotebookId, sourceId);
        }
        if (source != null) {
            repository.saveInk(source);
        }
        String beforeId = append ? null : target.page.id;
        if (!repository.placePageBefore(sourceId, beforeId, destNotebookId)) {
            LaunchLog.mark("place-before Confirm refused; leave Reorder mode open");
            refreshModeBar();
            return;
        }
        cancelMode();
        openAfterReorder(sourceId, destNotebookId);
    }

    /**
     * Midline is past the last real page (over the Create new page control) →
     * Reorder Confirm appends instead of placing before a page.
     */
    private boolean reorderAppendTarget() {
        if (slots.isEmpty() || slotTops.length != slots.size() + 1) {
            return slots.isEmpty();
        }
        int viewport = scroller.getHeight();
        if (viewport <= 0 && drawingArea != null) {
            viewport = drawingArea.getHeight();
        }
        int mid = scroller.getScrollY() + Math.max(viewport, 1) / 2;
        return mid >= slotTops[slots.size()];
    }

    private void openAfterReorder(String pageId, String notebookId) {
        List<Board> pages = repository.pages(notebookId);
        for (int i = 0; i < pages.size(); i++) {
            if (pages.get(i).id.equals(pageId)) {
                openNotebookOrScratch(notebookId, CollectionNav.scrollYBefore(pages, i, pageLayout()));
                return;
            }
        }
        openNotebookOrScratch(notebookId, effectiveScrollY());
    }

    /** Scratchpad when {@code notebookId == null}; else that notebook. */
    private void openNotebookOrScratch(String notebookId, Integer keepScroll) {
        if (notebookId == null) {
            openScratchpad(keepScroll);
        } else {
            openNotebook(notebookId, keepScroll);
        }
    }

    /** UI collection id → Room notebook id ({@code null} = Scratchpad). */
    private static String notebookKey(String collectionOrId) {
        return SCRATCHPAD.equals(collectionOrId) ? null : collectionOrId;
    }

    private boolean sameNotebookForReorder(PageSlot target) {
        return java.util.Objects.equals(notebookKey(collectionId), reorderSourceNotebookId);
    }

    /** Slot index of the Reorder source, or −1. */
    private int reorderSourceIndex() {
        for (int i = 0; i < slots.size(); i++) {
            if (slots.get(i).page.id.equals(reorderSourceId)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * The page at the top of the viewport. Scroll offset divided by the page
     * height: the slots above it are not visited. Used by Link.
     */
    private PageSlot frontSlot() {
        return slotAtContentY(scroller.getScrollY());
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
        return slotAtContentY(scroller.getScrollY() + Math.max(viewport, 1) / 2);
    }

    /** Slot owning content offset {@code y}, or null when the list is empty. */
    private PageSlot slotAtContentY(int y) {
        int index = indexAt(y);
        return index < 0 ? null : slots.get(index);
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
            boolean append = reorderAppendTarget();
            if (reorderConfirmBlocked(target, append)) {
                modeText.setText(getString(R.string.reordering, reorderSourceRef));
                setModeConfirmEnabled(false);
            } else if (append) {
                modeText.setText(getString(R.string.place_in, reorderSourceRef, nameOnScreen()));
                setModeConfirmEnabled(true);
            } else {
                modeText.setText(getString(R.string.place_before, reorderSourceRef, refOnScreen(target)));
                setModeConfirmEnabled(true);
            }
        }
    }

    /**
     * Same gate for mode-bar Confirm and {@link #confirmReorder}: loading, no
     * target (unless append), self, or same-notebook noop.
     */
    private boolean reorderConfirmBlocked(PageSlot target, boolean append) {
        if (loading()) {
            return true;
        }
        if (append) {
            int sourceIndex = reorderSourceIndex();
            if (!java.util.Objects.equals(notebookKey(collectionId), reorderSourceNotebookId)) {
                return false;
            }
            return sourceIndex >= 0 && sourceIndex == slots.size() - 1;
        }
        if (target == null || target.page.id.equals(reorderSourceId)) {
            return true;
        }
        if (!sameNotebookForReorder(target)) {
            return false;
        }
        int sourceIndex = reorderSourceIndex();
        if (sourceIndex < 0) {
            return false;
        }
        int targetIndex = slots.indexOf(target);
        return targetIndex > 0 && sourceIndex == targetIndex - 1;
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
        if (targetId.equals(source.parentId())) {
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
                if (each.id().equals(collectionId)) {
                    return each.title();
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
        String name = place.notebookId() == null
                ? getString(R.string.scratchpad)
                : titleOf(place.notebookId());
        if (name == null || name.isEmpty()) {
            return null;
        }
        return name + " " + (place.index() + 1) + "/" + place.count();
    }

    /** Opens that page the way tapping its notebook and scrolling there would. */
    private void openLinkedPage(String pageId) {
        BoardRepository.PagePlace place = repository.placeOf(pageId);
        if (place == null) {
            return;
        }
        List<Board> pages = repository.pages(place.notebookId());
        openNotebookOrScratch(place.notebookId(),
                CollectionNav.scrollYBefore(pages, place.index(), pageLayout()));
    }

    private List<Board> pagesIn(String id) {
        return repository.pages(notebookKey(id));
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

    /** Wipe (confirm) and Delete (real tear + undo chip, no confirm). */
    private void showPageMenu(PageSlot slot) {
        LinearLayout menu = panel();
        menu.setMinimumWidth(dp(200));
        addPanelButton(menu, getString(R.string.wipe), () -> confirmWipe(slot));
        addPanelButton(menu, getString(R.string.delete_page), () -> deletePage(slot));
        showOverlay(menu, pageAnchoredLp(slot.moreButton, menu), drawingArea);
    }

    private void confirmWipe(PageSlot slot) {
        LinearLayout box = panel();
        box.addView(panelText(getString(R.string.wipe_page_title), 15));
        box.addView(panelText(getString(R.string.wipe_page_body), 13));
        addCancelConfirmRow(box, getString(R.string.wipe), () -> wipe(slot), true);
        showOverlay(box, pageAnchoredLp(slot.moreButton, box), drawingArea);
    }

    /**
     * Real delete (no confirm). Defers sync for ~5s so Undo can restore;
     * timeout / dismiss / next delete commits.
     */
    private void deletePage(PageSlot slot) {
        String pageId = slot.page.id;
        Board live = livePageIn(notebookKey(collectionId), pageId);
        if (live != null) {
            repository.saveInk(live);
        }
        int index = slots.indexOf(slot);
        dismissOverlay();
        cancelMode();
        // Commits any prior pending delete, then tears this page (held flush).
        repository.deletePage(pageId);
        inkView.invalidatePage(pageId);
        List<Board> pages = pagesIn(collectionId);
        ++loadGeneration;
        int target = index < 0 || pages.isEmpty()
                ? effectiveScrollY()
                : CollectionNav.scrollYBefore(pages, Math.min(index, pages.size() - 1), pageLayout());
        showReady(collectionId, pages, target);
        if (repository.placeOf(pageId) == null) {
            showDeleteUndo();
        } else {
            showHint(getString(R.string.delete_failed), true);
        }
    }

    private void showDeleteUndo() {
        deleteUndoBar.removeCallbacks(clearDeleteUndo);
        deleteUndoBar.setVisibility(View.VISIBLE);
        deleteUndoBar.postDelayed(clearDeleteUndo, 5000);
        updateExcludeRects();
    }

    /** Hide the chip without committing — only after a successful Undo. */
    private void hideDeleteUndoBar() {
        deleteUndoBar.removeCallbacks(clearDeleteUndo);
        if (deleteUndoBar.getVisibility() != View.GONE) {
            deleteUndoBar.setVisibility(View.GONE);
            updateExcludeRects();
        }
    }

    private void commitDeleteUndo() {
        hideDeleteUndoBar();
        repository.commitPendingDelete();
    }

    private void undoPendingDelete() {
        hideDeleteUndoBar();
        if (!repository.undoPendingDelete()) {
            showHint(getString(R.string.delete_failed), true);
            return;
        }
        reloadPages(effectiveScrollY());
    }

    /** Rename / Place / Delete — anchored on the active-notebook chip. */
    private void showNotebookMenu(String id) {
        if (id == null || notebookById(id) == null) {
            return;
        }
        View anchor = activeNotebookButton;
        LinearLayout menu = panel();
        menu.setMinimumWidth(Math.max(dp(200), anchor.getWidth()));
        addPanelButton(menu, getString(R.string.rename), 0, () -> showNameForm(id, anchor, null, null));
        addPanelButton(menu, getString(R.string.place_under), 0, () -> showPlaceUnderMenu(id, anchor));
        addPanelButton(menu, getString(R.string.make_top_level), 0, () -> {
            if (repository.placeNotebook(id, null) && trail.contains(id)) {
                openNotebook(id, null);
            } else {
                rebuildNotebookTabs(repository.notebooks());
            }
        });
        addPanelButton(menu, getString(R.string.delete), 0, () -> confirmDelete(id, anchor));
        showOverlay(menu, anchoredLp(anchor, menu), root);
    }

    /**
     * In-window Move destination picker (not snackbar+nav). Appends the page
     * as newest in the chosen notebook. Uses the shared hierarchy menu.
     */
    private void showMoveMenu(PageSlot slot) {
        // Reseat by id — Ready list is SoT; missing id means the slot is stale.
        Board live = livePageIn(notebookKey(collectionId), slot.page.id);
        if (live == null) {
            showHint(getString(R.string.move_failed), true);
            reloadPages(effectiveScrollY());
            return;
        }
        cancelMode();
        commitDeleteUndo();
        hideLassoMenu();
        showNotebookHierarchyMenu(slot.moveButton, drawingArea, null, true, slot,
                notebookId -> movePageToNotebook(live.id, notebookId));
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
                    openNotebook(notebookId, null);
                } else {
                    rebuildNotebookTabs(repository.notebooks());
                }
            }
        });
    }

    /**
     * Shared indented notebook hierarchy for Move destination and Place-under
     * parent. Panel spans nearly the full drawing-area width; rows pack into
     * ~180dp columns when the tree is taller than one screen so large libraries
     * need far less vertical scrolling (v145; lost in v148 tip rollback).
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
        int margin = dp(8);
        int hostW = layoutHost.getWidth() > 0 ? layoutHost.getWidth() : root.getWidth();
        int hostH = layoutHost.getHeight() > 0 ? layoutHost.getHeight() : root.getHeight();
        int panelW = Math.max(dp(280), hostW - 2 * margin);
        int maxH = Math.max(dp(280), hostH - 2 * margin - list.getPaddingTop() - list.getPaddingBottom());
        int innerW = Math.max(dp(200), panelW - list.getPaddingLeft() - list.getPaddingRight());

        ArrayList<HierarchyRow> rows = new ArrayList<>();
        collectNotebookHierarchy(rows, null, 0, skipIds, onPick);
        if (rows.isEmpty()) {
            rows.add(new HierarchyRow(getString(R.string.no_place_target), 0, null));
        }
        if (includeNewNotebook) {
            // Switcher: nest under the open notebook (top-level on Scratchpad).
            // Move → + New stays top-level, then files the page there.
            String parent = moveAfter == null ? notebookKey(collectionId) : null;
            String createParent = parent;
            rows.add(new HierarchyRow(getString(R.string.new_notebook_item), 0,
                    () -> showNameForm(null, null, createParent, moveAfter)));
        }

        int colMin = dp(180);
        int rowH = dp(48);
        int rowsFit = Math.max(1, maxH / rowH);
        int byWidth = Math.max(1, innerW / colMin);
        int byHeight = Math.max(1, (rows.size() + rowsFit - 1) / rowsFit);
        int cols = rows.size() <= rowsFit ? 1 : Math.min(byWidth, byHeight);
        int perCol = (rows.size() + cols - 1) / cols;
        int colW = Math.max(colMin, innerW / cols);

        LinearLayout columns = new LinearLayout(this);
        columns.setOrientation(LinearLayout.HORIZONTAL);
        for (int c = 0; c < cols; c++) {
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            int start = c * perCol;
            int end = Math.min(rows.size(), start + perCol);
            for (int i = start; i < end; i++) {
                HierarchyRow row = rows.get(i);
                if (row.action == null) {
                    col.addView(panelText(row.label, 14));
                } else {
                    addPanelButton(col, row.label, row.depth, row.action);
                }
            }
            columns.addView(col, new LinearLayout.LayoutParams(colW, LinearLayout.LayoutParams.WRAP_CONTENT));
        }

        View body = columns;
        int contentW = colW * cols;
        if (contentW > innerW) {
            HorizontalScrollView hScroll = new HorizontalScrollView(this);
            hScroll.setHorizontalScrollBarEnabled(false);
            hScroll.addView(columns, new FrameLayout.LayoutParams(contentW, FrameLayout.LayoutParams.WRAP_CONTENT));
            body = hScroll;
        }
        columns.measure(
                View.MeasureSpec.makeMeasureSpec(contentW, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int treeH = columns.getMeasuredHeight();
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.addView(body, matchWrap());
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                treeH > maxH ? maxH : LinearLayout.LayoutParams.WRAP_CONTENT);
        list.addView(scroll, scrollLp);
        showOverlay(list, hierarchyMenuLp(list, layoutHost, panelW, margin), layoutHost);
    }

    /** Wide multi-column panel: top-centered over the drawing area. */
    private FrameLayout.LayoutParams hierarchyMenuLp(
            View content, FrameLayout host, int panelW, int margin) {
        content.measure(
                View.MeasureSpec.makeMeasureSpec(panelW, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(host.getHeight(), View.MeasureSpec.AT_MOST));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                panelW, FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.topMargin = margin;
        return lp;
    }

    private void collectNotebookHierarchy(
            ArrayList<HierarchyRow> rows,
            String parentId,
            int depth,
            HashSet<String> skipIds,
            NotebookPick onPick) {
        for (BoardRepository.NotebookInfo each : childrenOf(parentId)) {
            if (skipIds != null && skipIds.contains(each.id())) {
                // skipIds already includes Place-under source descendants.
                continue;
            }
            String id = each.id();
            rows.add(new HierarchyRow(each.title(), depth, () -> onPick.onPick(id)));
            collectNotebookHierarchy(rows, each.id(), depth + 1, skipIds, onPick);
        }
    }

    private static final class HierarchyRow {
        final String label;
        final int depth;
        final Runnable action;

        HierarchyRow(String label, int depth, Runnable action) {
            this.label = label;
            this.depth = depth;
            this.action = action;
        }
    }

    /**
     * Cross-notebook append Move (menu path). Reseats the source by page id
     * from Ready, then {@link BoardRepository#movePageToNotebook} (same
     * {@code placePageBefore} spine as Reorder Confirm — slice id + dest-first).
     */
    private void movePageToNotebook(String pageId, String targetNotebookId) {
        if (pageId == null || targetNotebookId == null) {
            return;
        }
        Board live = livePageIn(notebookKey(collectionId), pageId);
        boolean ok = live != null;
        if (ok) {
            repository.saveInk(live);
            ok = repository.movePageToNotebook(pageId, targetNotebookId);
        }
        if (!ok) {
            LaunchLog.mark("Move menu refused for " + targetNotebookId + " page=" + pageId);
            showHint(getString(R.string.move_failed), true);
        }
        reloadPages(effectiveScrollY());
    }

    /** Board with {@code pageId} in notebook {@code notebookKey} ({@code null} = Scratchpad). */
    private Board livePageIn(String notebookKey, String pageId) {
        if (pageId == null) {
            return null;
        }
        for (Board page : repository.pages(notebookKey)) {
            if (pageId.equals(page.id)) {
                return page;
            }
        }
        return null;
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
                if (skip.add(each.id())) {
                    queue.add(each.id());
                }
            }
        }
        skip.remove(id);
        return skip;
    }

    /**
     * Create / rename. When {@code moveAfter} is set (Move → + New notebook),
     * the page is filed into the created notebook instead of opening it empty.
     * {@code parentId} null = top-level; omit {@code moveAfter} for create/rename only.
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
                rebuildNotebookTabs(repository.notebooks());
                styleTabs();
                return;
            }
            BoardRepository.NotebookInfo created = repository.createNotebook(text, parentId);
            rebuildNotebookTabs(repository.notebooks());
            if (created == null) {
                styleTabs();
            } else if (moveAfter != null && moveAfter.page != null) {
                movePageToNotebook(moveAfter.page.id, created.id());
            } else {
                openNotebook(created.id(), null);
            }
        };
        name.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submit.run();
                return true;
            }
            return false;
        });
        // Empty name must leave the form open — confirm does not dismiss first.
        addCancelConfirmRow(form, getString(id == null ? R.string.create : R.string.save),
                submit, false);

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
        // pages() is Ready-only; ensure when height is known so the count is real.
        if (pageHeight > 0) {
            repository.ensureSheet(id, pageHeight);
        }
        int pages = repository.pages(id).size();
        LinearLayout box = panel();
        box.addView(panelText(getString(R.string.delete_notebook_title, titleOf(id)), 15));
        box.addView(panelText(pages == 0
                ? getString(R.string.delete_notebook_empty)
                : getResources().getQuantityString(R.plurals.delete_notebook_pages, pages, pages), 13));
        if (!childrenOf(id).isEmpty()) {
            box.addView(panelText(getString(R.string.delete_notebook_children), 13));
        }
        addCancelConfirmRow(box, getString(R.string.delete), () -> {
            repository.deleteNotebook(id);
            collectionNav.clear(id);
            int cut = trail.indexOf(id);
            if (cut >= 0) {
                trail.subList(cut, trail.size()).clear();
            }
            boolean opened = id.equals(collectionId);
            rebuildNotebookTabs(repository.notebooks());
            if (opened) {
                reopenAfterNotebookGone(null);
            }
        }, true);
        showOverlay(box, anchoredLp(anchor, box), root);
    }

    /**
     * Cancel + primary action row. When {@code dismissFirst}, overlay closes before
     * {@code onConfirm} (wipe/delete). When false, caller validates then dismisses
     * (name form keeps the form open on empty input).
     */
    private void addCancelConfirmRow(LinearLayout box, String confirmLabel, Runnable onConfirm,
                                     boolean dismissFirst) {
        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.END);
        Button cancel = tinyButton(getString(R.string.cancel), 15);
        cancel.setMinimumHeight(dp(48));
        onTap(cancel, v -> dismissOverlay());
        Button confirm = tinyButton(confirmLabel, 15);
        confirm.setMinimumHeight(dp(48));
        styleButton(confirm, true);
        onTap(confirm, v -> {
            if (dismissFirst) {
                dismissOverlay();
            }
            if (onConfirm != null) {
                onConfirm.run();
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
    }

    /** End-aligned Close row for the launch-log panel (Settings has no Close — gear toggles). */
    private void addCloseActionsRow(LinearLayout box, int topMarginPx) {
        Button close = tinyButton(getString(R.string.close), 15);
        styleButton(close, true);
        onTap(close, v -> dismissOverlay());
        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.END);
        actions.addView(close, wrap());
        LinearLayout.LayoutParams actionsLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        actionsLp.topMargin = topMarginPx;
        box.addView(actions, actionsLp);
    }

    /** Gear toggles Settings (second tap / any open overlay dismisses). Scrim tap also closes. */
    private void toggleSettings() {
        if (overlay != null) {
            dismissOverlay();
            return;
        }
        showSettings();
    }

    /** In-window Settings: account, Sync now / Sign out, Prepare transfer, Launch log. */
    private void showSettings() {
        settingsGeneration++;
        SyncConfig config = SyncConfig.load(this);
        // Sync stays on whenever signed in — no Sync-off control in the product UI.
        if (config.hasGoogleAccount() && !config.syncOn()) {
            SyncConfig.setSyncOn(this, true);
            config = SyncConfig.load(this);
        }
        LinearLayout box = panel();
        box.setMinimumWidth(dp(280));
        box.addView(panelText(getString(R.string.settings), 16));

        TextView account = panelText(
                config.hasGoogleAccount()
                        ? getString(R.string.settings_signed_in_as,
                        config.googleEmail().isEmpty() ? config.googleSub() : config.googleEmail())
                        : getString(R.string.settings_not_signed_in),
                13);
        box.addView(account);

        long lastOk = ZettelData.repository(this).lastSyncedAt();
        String writerBlock = SyncConfig.writerBlockMessage(this);

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
                        queueLocalInkForSync();
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
            Button syncNow = tinyButton(getString(R.string.settings_sync_now), 15);
            styleButton(syncNow, true);
            onTap(syncNow, v -> {
                queueLocalInkForSync();
                SyncScheduler.syncNow(this);
                showHint(getString(R.string.settings_sync_started), true);
                dismissOverlay();
                showSettings();
            });
            LinearLayout.LayoutParams syncLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            syncLp.topMargin = dp(8);
            box.addView(syncNow, syncLp);

            Button signOut = tinyButton(getString(R.string.settings_sign_out), 15);
            onTap(signOut, v -> {
                SyncConfig.signOut(this);
                dismissOverlay();
                showHint(getString(R.string.settings_not_signed_in), true);
            });
            box.addView(signOut, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        }

        // Always show Prepare transfer. When blocked, keep it visible but disabled with a reason.
        // Runs on-device only — never opens a browser.
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
        if (!canPrepare) {
            box.addView(panelText(prepareBlockReason, 11));
        }

        Button launchLog = tinyButton(getString(R.string.settings_launch_log), 15);
        styleButton(launchLog, true);
        onTap(launchLog, v -> showLaunchLog());
        LinearLayout.LayoutParams launchLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        launchLp.topMargin = dp(8);
        box.addView(launchLog, launchLp);

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

    /** Queue Ready LOG / notebook meta for push. No BOARD UPSERT. */
    private void queueLocalInkForSync() {
        ZettelData.repository(this).ensureUnsyncedQueued();
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
                queueLocalInkForSync();
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

    /** Settings → Launch log: this launch's timeline and the two before it, newest first. */
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
        addCloseActionsRow(box, 0);
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
        }
    }

    /** Side effects after {@link ToolController} has synced chrome + ink tool. */
    private void afterToolChanged(PageInkView.Tool tool) {
        if (tool == PageInkView.Tool.LASSO) {
            // No instructional hint — just select the tool.
            if (inkView.hasSelection()) {
                showLassoMenu();
            }
        } else if (!inkView.hasSelection()) {
            showHint("", false);
            hideLassoMenu();
        }
    }

    private void styleTabs() {
        boolean scratch = SCRATCHPAD.equals(collectionId);
        // Mutual-exclusive underline from active-sheet SoT — never both.
        styleTool(scratchpadTab, scratch);
        String chipId = chipNotebookId();
        String title = chipId == null ? "" : titleOf(chipId);
        if (title.isEmpty()) {
            title = getString(R.string.notebook_fallback);
        }
        // Always a real notebook name (no ▾); never the label "Notebooks".
        activeNotebookButton.setText(title);
        boolean inNotebook = !scratch && notebookKey(collectionId) != null;
        styleTab(activeNotebookButton, inNotebook);
        inkLabel(activeNotebookButton, inNotebook);
        activeNotebookButton.setSelected(inNotebook);
    }

    /** E-ink selection without fills: a 2 dp black underline (and bold label). */
    private void styleTab(View tab, boolean selected) {
        // Always assign a drawable (never null) so e-ink clears the previous mark.
        tab.setBackground(new Underline(dp(2), dp(12), selected));
        tab.setSelected(selected);
    }

    private static void inkLabel(TextView label, boolean selected) {
        label.setTextColor(Color.BLACK);
        label.setTypeface(Typeface.DEFAULT, selected ? Typeface.BOLD : Typeface.NORMAL);
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
        addExclude(rects, modeBar, MOVE_EXCLUDE_PAD_PX, origin, loc);
        addExclude(rects, deleteUndoBar, MOVE_EXCLUDE_PAD_PX, origin, loc);
        addExclude(rects, lassoMenu, MOVE_EXCLUDE_PAD_PX, origin, loc);
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
            // moreButton already has a finger-sized min box; small pad only.
            addExclude(rects, slot.moreButton, MOVE_EXCLUDE_PAD_PX, origin, loc);
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
        return page.slicePx > 0 ? page.slicePx : pageHeight;
    }

    /**
     * Transparent stand-in for one page above the ink surface: carries the
     * page's "k/n" number (top-left, Ready slice count), Link and
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
            // Hit ≈ visible label (no 48dp min / pad slop) so lasso can reach near the word.
            chromeWord(linkButton);
            chromeWord(reorderButton);
            chromeWord(moveButton);
            // 20sp glyph + finger min box (pad still 0). See chromeMore().
            moreButton = tinyButton(getString(R.string.notebook_options), 20);
            chromeMore(moreButton);
            moreButton.setContentDescription(getString(R.string.page_options));
            onTap(linkButton, v -> startMode(this, false));
            onTap(reorderButton, v -> startMode(this, true));
            onTap(moveButton, v -> showMoveMenu(this));
            onTap(moreButton, v -> showPageMenu(this));

            int mark = page.paper != null ? dp(2) : pageGap;
            // Comfortable inset from dashed edge / page rim (hit pad stays 0).
            int chromeBottom = (page.paper != null ? 0 : pageGap) + dp(16);

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
            leftRow.setBaselineAligned(true);
            leftRow.addView(linkButton, wrap());
            // Pre-v173 optical gaps used pad+margin; pad is 0 now — put the
            // same space in margin so interstitial hits stay drawable (lasso).
            LinearLayout.LayoutParams reorderLp = wrap();
            reorderLp.leftMargin = dp(14); // was 4+6+4
            leftRow.addView(reorderButton, reorderLp);
            left.addView(leftRow, wrap());
            FrameLayout.LayoutParams leftLp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT);
            leftLp.gravity = Gravity.BOTTOM | Gravity.START;
            leftLp.leftMargin = dp(22); // farther from left edge (was 14)
            leftLp.bottomMargin = chromeBottom;
            addView(left, leftLp);

            // Right: Move (notebook menu) and ⋮ share one chrome row.
            actions = new LinearLayout(context);
            actions.setOrientation(LinearLayout.HORIZONTAL);
            actions.setGravity(Gravity.CENTER_VERTICAL);
            // Baseline-align ⋮ with Move text (⋮ at 18sp sat slightly low).
            actions.setBaselineAligned(true);
            actions.addView(moveButton, wrap());
            LinearLayout.LayoutParams moreLp = wrap();
            moreLp.leftMargin = dp(20); // was Move pad 4 + 6 + ⋮ pad 10
            moreLp.gravity = Gravity.CENTER_VERTICAL;
            actions.addView(moreButton, moreLp);
            FrameLayout.LayoutParams actionsLp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT);
            actionsLp.gravity = Gravity.BOTTOM | Gravity.END;
            actionsLp.rightMargin = dp(24); // farther from right edge (was 18)
            actionsLp.bottomMargin = chromeBottom;
            addView(actions, actionsLp);

            FrameLayout.LayoutParams dashLp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, mark);
            dashLp.gravity = Gravity.BOTTOM;
            addView(new DashedRule(context), dashLp);
        }

        void setIndex(int index, int total) {
            this.index = index;
            number.setText(index + "/" + total);
            number.setContentDescription(getString(R.string.page_number, index, total));
        }

        /** Outgoing lines first, then backlinks. A missing page is not shown. */
        private void fillLinkLines(LinearLayout lines) {
            ArrayList<BoardRepository.PageLink> outgoing = new ArrayList<>();
            ArrayList<BoardRepository.PageLink> incoming = new ArrayList<>();
            for (BoardRepository.PageLink link : repository.linksTouching(page.id)) {
                if (page.id.equals(link.sourceId())) {
                    outgoing.add(link);
                } else if (page.id.equals(link.targetId())) {
                    incoming.add(link);
                }
            }
            for (BoardRepository.PageLink link : outgoing) {
                addLinkLine(lines, link.targetId(), true);
            }
            for (BoardRepository.PageLink link : incoming) {
                addLinkLine(lines, link.sourceId(), false);
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
     * Page chrome Link / Reorder / Move: padding 0 and no min size so the hit
     * target is the visible label. Visual gaps live on layout margins (outside
     * the hit rect). Ink exclude holes follow view bounds.
     */
    private void chromeWord(Button button) {
        button.setMinimumWidth(0);
        button.setMinWidth(0);
        button.setMinimumHeight(0);
        button.setMinHeight(0);
        button.setPadding(0, 0, 0, 0);
        button.setIncludeFontPadding(false);
        button.setGravity(Gravity.CENTER);
    }

    /**
     * Page ⋮: same pad-0 rule as {@link #chromeWord}, but a finger-usable min
     * box. Why finger missed before: hit bounds ≈ glyph only (~narrow ⋮ at
     * 14–16sp); TouchHelper owns the surface except exclude rects that track
     * those tiny view bounds — so a fat finger rarely lands inside the View,
     * and the stylus (precise) sometimes did. Fix: min 40×40 dp hit box with
     * Gravity.CENTER (glyph stays small); no padding into Move/edge gaps
     * (those stay layout <b>margin</b>). Baseline still aligns with Move.
     */
    private void chromeMore(Button button) {
        int finger = dp(40);
        button.setMinimumWidth(finger);
        button.setMinWidth(finger);
        button.setMinimumHeight(finger);
        button.setMinHeight(finger);
        button.setPadding(0, 0, 0, 0);
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
        attachDownClick(view);
        view.setOnClickListener(listener);
    }

    private void onLongTap(View view, View.OnLongClickListener listener) {
        attachDownClick(view);
        view.setOnLongClickListener(listener);
    }

    /** Soft click on press; disable framework click sound so it never double-fires. */
    private static void attachDownClick(View view) {
        view.setSoundEffectsEnabled(false);
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
