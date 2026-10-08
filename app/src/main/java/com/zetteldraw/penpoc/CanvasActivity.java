package com.zetteldraw.penpoc;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
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
import com.zetteldraw.penpoc.data.ZettelData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
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

    private BoardRepository repository;
    private String collectionId = SCRATCHPAD;
    /** Last opened notebook; null until one is chosen or when none exist. */
    private String notebookId;
    private final HashMap<String, Integer> scrollByCollection = new HashMap<>();

    private FrameLayout root;
    private FrameLayout drawingArea;
    /** In-window menu or form (Move, notebook menu, name, delete confirm). */
    private FrameLayout overlay;
    private LinearLayout topBar;
    private LinearLayout linkingBar;
    private TextView linkingText;
    private Button linkConfirm;
    /** Page the Link control was pressed on. Null when the linking line is closed. */
    private String linkSourceId;
    /** "notebook k/n" captured from the slot on screen. Not looked up again. */
    private String linkSourceRef;
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
        InkRenderer.applyBaseWidthMm(getResources().getDisplayMetrics());
        repository = ZettelData.repository(this);
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        shortPagesSince = prefs.getLong(SHORT_PAGES_SINCE, 0L);
        if (shortPagesSince == 0L) {
            shortPagesSince = System.currentTimeMillis();
            prefs.edit().putLong(SHORT_PAGES_SINCE, shortPagesSince).apply();
        }
        pageGap = dp(24);

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
        linkingBar = buildLinkingBar();
        column.addView(linkingBar, matchWrap());
        toolbar = buildToolbar();
        column.addView(toolbar, matchWrap());
        column.addView(rule(), ruleLp());

        drawingArea = new FrameLayout(this);
        column.addView(drawingArea, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        inkView = new PageInkView(this);
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
                    reloadPages(scroller.getScrollY());
                }
            }

            @Override
            public void onPageBecameNonEmpty(Board page) {
                // The first stroke stored the trailing blank, and the repository added a new one.
                if (slotFor(page.id) != null && !loading() && pagesIn(collectionId).size() != slots.size()) {
                    reloadPages(scroller.getScrollY());
                }
            }

            @Override
            public void onLassoSelected(int selected) {
                if (selected == 0) {
                    showHint(getString(R.string.lasso_nothing), true);
                } else {
                    showHint(getResources().getQuantityString(R.plurals.lasso_selected, selected, selected), false);
                }
            }

            @Override
            public void onLassoMoved(Lasso.Move move) {
                showHint("", false);
                setTool(PageInkView.Tool.PEN);
            }

            @Override
            public void onLassoCancelled() {
                showHint("", false);
            }

            @Override
            public void onHistoryChanged() {
                runOnUiThread(CanvasActivity.this::syncHistory);
            }
        });
        drawingArea.addView(inkView, matchMatch());

        scroller = new PageScroller(this);
        scroller.setStylusTarget(inkView);
        scroller.setListener(this::onScrolled);
        pageColumn = new LinearLayout(this);
        pageColumn.setOrientation(LinearLayout.VERTICAL);
        pageColumn.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (pendingScrollY < 0) {
                updateExcludeRects();
                return;
            }
            int target = pendingScrollY;
            pendingScrollY = -1;
            scroller.post(() -> {
                scroller.scrollTo(0, target);
                inkView.setContentScrollY(scroller.getScrollY());
                refreshLinkingBar();
                updateExcludeRects();
            });
        });
        scroller.addView(pageColumn, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT));
        drawingArea.addView(scroller, matchMatch());


        drawingArea.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> onDrawingAreaLaidOut());

        setContentView(root);
        repository.addRemoteChangeListener(remoteChanged);
        setTool(PageInkView.Tool.PEN);
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
        // The linking line sits in this column. Showing it shrinks the drawing area, and treating
        // that as a new page height reloaded every page on the first press (the sheets then stayed
        // open, so the next press was cheap). Put the line's height back and keep the pages.
        if (linkingBar != null && linkingBar.getVisibility() == View.VISIBLE && linkingBar.getHeight() > 0) {
            next = Math.min(window, area + linkingBar.getHeight() - pageGap);
        }
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
                startLoad(loaded.id, loaded.keepScroll, false);
            } else if (!first && currentPages != null && !loading()) {
                pageReflows.incrementAndGet();
                startLoad(collectionId, scroller.getScrollY(), false);
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
        scratchpadTab.setOnClickListener(v -> openScratchpad());
        scratchpadTab.setOnLongClickListener(v -> {
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
        addButton.setOnClickListener(v -> showNameForm(null, null, addButton));
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
        undoButton.setOnClickListener(v -> {
            if (!inkView.undo()) {
                showHint(getString(R.string.undo_nothing), true);
            }
        });
        redoButton.setOnClickListener(v -> {
            if (!inkView.redo()) {
                showHint(getString(R.string.redo_nothing), true);
            }
        });
        penButton.setOnClickListener(v -> setTool(PageInkView.Tool.PEN));
        eraserButton.setOnClickListener(v -> setTool(PageInkView.Tool.ERASER));
        lassoButton.setOnClickListener(v -> setTool(PageInkView.Tool.LASSO));
        toolbar.addView(undoButton, iconLp(6));
        toolbar.addView(redoButton, iconLp(4));
        toolbar.addView(rule(), barDividerLp());
        toolbar.addView(penButton, iconLp(0));
        toolbar.addView(eraserButton, iconLp(6));
        toolbar.addView(lassoButton, iconLp(6));
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

    /** Borderless icon; the active one gets a 2 dp black underline. */
    private void styleTool(ImageButton button, boolean selected) {
        button.setBackground(selected ? new Underline(dp(2), dp(10)) : null);
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
                plus.setOnClickListener(v -> showNameForm(null, null, plus, createUnder));
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
        Integer keep = loading() ? pendingKeepScroll : Integer.valueOf(scroller.getScrollY());
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
        if (pagesLoaded && !loading()) {
            scrollByCollection.put(collectionId, scroller.getScrollY());
        }
        pagesLoaded = true;
        collectionId = id;
        syncNav();
        // The tab is marked now; its pages load off the main thread, so a tap never waits on
        // storage. The old pages stay up with the pen held until the new ones replace them.
        // A non-null keepScroll (a page link) wins over the last-inked-page jump.
        startLoad(id, keepScroll, true);
    }

    /**
     * Loads the notebooks and one page list off the main thread. Nothing can
     * leave it unshown: if the list is not up by the deadline the load runs
     * again on a fresh thread, with a longer deadline each time, until it is.
     * {@code keepScroll} null means the list's remembered position.
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
                if (pageHeight > 0) {
                    repository.ensureSheet(notebook, pageHeight, legacyPageHeight, shortPagesSince);
                }
                pages = repository.pages(notebook);
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
            main.post(() -> onPagesLoaded(new Loaded(generation, id, keepScroll, notebooks, pages)));
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
        if (pageHeight == 0) {
            awaitingLayout = loaded;
            return;
        }
        show(loaded);
    }

    private void show(Loaded loaded) {
        if (loaded.generation != loadGeneration || shownGeneration == loaded.generation) {
            return;
        }
        int target;
        if (loaded.keepScroll != null) {
            target = loaded.keepScroll;
        } else if (SCRATCHPAD.equals(loaded.id)) {
            if (scrollByCollection.containsKey(loaded.id)) {
                target = scrollByCollection.get(loaded.id);
            } else {
                target = 0;
                for (int i = 0; i < loaded.pages.size() - 1; i++) {
                    target += heightOf(loaded.pages.get(i)) + layoutGap(loaded.pages);
                }
            }
        } else {
            // A fresh selection jumps to the last inked page. An explicit
            // keepScroll (a refresh, or a link opened later) already won above.
            target = lastInkOffset(loaded.pages);
        }
        showPages(loaded.pages, target);
        syncNav();
        if (!firstListShown) {
            firstListShown = true;
            LaunchLog.mark("first page list on screen: " + label(loaded.id) + ", " + loaded.pages.size()
                    + " pages (" + inked(loaded.pages) + " inked), " + loaded.notebooks.size() + " notebook tabs");
        }
    }

    /** One finished background load. */
    private static final class Loaded {
        final int generation;
        final String id;
        final Integer keepScroll;
        final List<BoardRepository.NotebookInfo> notebooks;
        final List<Board> pages;

        Loaded(int generation, String id, Integer keepScroll, List<BoardRepository.NotebookInfo> notebooks,
               List<Board> pages) {
            this.generation = generation;
            this.id = id;
            this.keepScroll = keepScroll;
            this.notebooks = notebooks;
            this.pages = pages;
        }
    }

    private boolean loading() {
        return loadGeneration != shownGeneration;
    }

    private void reloadPages(int targetScrollY) {
        ++loadGeneration;
        showPages(pagesIn(collectionId), targetScrollY);
    }

    private void showPages(List<Board> pages, int targetScrollY) {
        shownGeneration = loadGeneration;
        awaitingLayout = null;
        inkView.release(PageInkView.Hold.LOADING);
        layoutPages(pages, targetScrollY);
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
        refreshLinkingBar();
    }

    /**
     * Scroll offset of the last slice that already has ink. Page height times
     * that index: no walk of the pages, and no second read of the log.
     */
    private int lastInkOffset(List<Board> pages) {
        if (pages.isEmpty() || pages.get(0).paper == null) {
            return 0;
        }
        int index = pages.get(0).paper.lastInkedSlice();
        if (index <= 0) {
            return 0;
        }
        return heightOf(pages.get(0)) * index;
    }

    /** One line under the notebook bars. Confirm is absent while the page in front is the source. */
    private LinearLayout buildLinkingBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), dp(4), dp(8), dp(4));
        bar.setVisibility(View.GONE);
        bar.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> updateExcludeRects());
        linkingText = new TextView(this);
        linkingText.setTextColor(Color.BLACK);
        linkingText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        linkingText.setSingleLine(false);
        bar.addView(linkingText, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        linkConfirm = tinyButton(getString(R.string.confirm), 15);
        styleButton(linkConfirm, true);
        linkConfirm.setVisibility(View.GONE);
        linkConfirm.setOnClickListener(v -> confirmLinking());
        Button cancel = tinyButton(getString(R.string.cancel), 15);
        cancel.setOnClickListener(v -> cancelLinking());
        LinearLayout.LayoutParams confirmLp = wrap();
        confirmLp.leftMargin = dp(8);
        bar.addView(linkConfirm, confirmLp);
        LinearLayout.LayoutParams cancelLp = wrap();
        cancelLp.leftMargin = dp(8);
        bar.addView(cancel, cancelLp);
        return bar;
    }

    private void startLinking(PageSlot slot) {
        linkSourceId = slot.page.id;
        linkSourceRef = refOnScreen(slot);
        linkingBar.setVisibility(View.VISIBLE);
        refreshLinkingBar();
        updateExcludeRects();
    }

    private void cancelLinking() {
        linkSourceId = null;
        linkSourceRef = null;
        linkingBar.setVisibility(View.GONE);
        linkConfirm.setVisibility(View.GONE);
        updateExcludeRects();
    }

    private void confirmLinking() {
        PageSlot front = frontSlot();
        if (linkSourceId == null || front == null || front.page.id.equals(linkSourceId)) {
            refreshLinkingBar();
            return;
        }
        repository.createLink(linkSourceId, front.page.id);
        int scroll = scroller.getScrollY();
        cancelLinking();
        reloadPages(scroll);
    }

    /**
     * The page at the top of the viewport. Scroll offset divided by the page
     * height: the slots above it are not visited.
     */
    private PageSlot frontSlot() {
        int index = indexAt(scroller.getScrollY());
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

    private void refreshLinkingBar() {
        if (linkSourceId == null || linkSourceRef == null || linkingBar.getVisibility() != View.VISIBLE) {
            return;
        }
        PageSlot front = frontSlot();
        if (front == null || front.page.id.equals(linkSourceId)) {
            linkingText.setText(getString(R.string.linking_from, linkSourceRef));
            linkConfirm.setVisibility(View.GONE);
        } else {
            linkingText.setText(getString(R.string.link_from_to, linkSourceRef, refOnScreen(front)));
            linkConfirm.setVisibility(View.VISIBLE);
        }
    }

    /** Notebook name and k/n already drawn on this slot. Does not open a notebook or walk its pages. */
    private String refOnScreen(PageSlot slot) {
        return nameOnScreen() + " " + slot.number.getText();
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
        if (!scrolling) {
            scrolling = true;
            inkView.hold(PageInkView.Hold.SCROLL);
        }
        inkView.setContentScrollY(scrollY);
        refreshLinkingBar();
        scroller.removeCallbacks(scrollSettled);
        scroller.postDelayed(scrollSettled, SCROLL_SETTLE_MS);
    }

    private void onScrollSettled() {
        scrolling = false;
        inkView.setContentScrollY(scroller.getScrollY());
        updateExcludeRects();
        inkView.release(PageInkView.Hold.SCROLL);
    }

    /**
     * In-window menu rather than a PopupMenu: a separate popup window does not
     * appear on the Boox over the TouchHelper surface.
     */
    private void showMoveMenu(PageSlot slot) {
        if (shownNotebooks == null) {
            shownNotebooks = repository.notebooks();
        }
        LinearLayout list = panel();
        addMoveTree(list, null, 0, slot);
        addPanelButton(list, getString(R.string.new_notebook_item), 0, () -> showNameForm(null, slot, null, null));

        showOverlay(list, pageAnchoredLp(slot.moveButton, list), drawingArea);
    }

    private void addMoveTree(LinearLayout list, String parentId, int depth, PageSlot slot) {
        for (BoardRepository.NotebookInfo each : childrenOf(parentId)) {
            addPanelButton(list, each.title, depth, () -> moveTo(slot, each.id));
            addMoveTree(list, each.id, depth + 1, slot);
        }
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

    /** Wipe and Delete for one page, anchored to its ⋯; each confirms in the same spot. */
    private void showPageMenu(PageSlot slot) {
        if (isTrailingBlank(slot)) {
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
        cancel.setOnClickListener(v -> dismissOverlay());
        Button confirm = tinyButton(getString(delete ? R.string.delete : R.string.wipe), 15);
        confirm.setMinimumHeight(dp(48));
        styleButton(confirm, true);
        confirm.setOnClickListener(v -> {
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
        showPages(pages, index < 0 ? scroller.getScrollY() : target);
    }

    /** The blank page that ends every list: it has nothing to wipe, move or delete. */
    private boolean isTrailingBlank(PageSlot slot) {
        return !slots.isEmpty() && slots.get(slots.size() - 1) == slot && slot.page.isBlank();
    }

    private void moveTo(PageSlot slot, String targetNotebookId) {
        repository.saveInk(slot.page);
        repository.movePageToNotebook(slot.page.id, targetNotebookId);
        reloadPages(scroller.getScrollY());
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
        addPanelButton(menu, getString(R.string.rename), 0, () -> showNameForm(id, null, tab, null));
        addPanelButton(menu, getString(R.string.place_under), 0, () -> showPlaceMenu(id, tab));
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

    /** Indented tree of every notebook that is not this one and not under it. */
    private void showPlaceMenu(String id, View anchor) {
        HashSet<String> skip = descendantIds(id);
        LinearLayout menu = panel();
        menu.setMinimumWidth(dp(200));
        if (!addPlaceTree(menu, null, 0, id, skip)) {
            menu.addView(panelText(getString(R.string.no_place_target), 14));
        }
        showOverlay(menu, anchoredLp(anchor, menu), root);
    }

    private boolean addPlaceTree(LinearLayout menu, String parentId, int depth, String moving, HashSet<String> skip) {
        boolean any = false;
        for (BoardRepository.NotebookInfo each : childrenOf(parentId)) {
            if (each.id.equals(moving) || skip.contains(each.id)) {
                continue;
            }
            any = true;
            addPanelButton(menu, each.title, depth, () -> {
                if (repository.placeNotebook(moving, each.id)) {
                    if (trail.contains(moving)) {
                        openNotebook(moving);
                    } else {
                        rebuildNotebookTabs();
                    }
                }
            });
            if (addPlaceTree(menu, each.id, depth + 1, moving, skip)) {
                any = true;
            }
        }
        return any;
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
     * Create ({@code id == null}) or rename a notebook. With {@code moveAfter},
     * the page is moved into the new notebook once it exists. With an
     * {@code anchor} in the top bar the form hangs under it.
     */
    private void showNameForm(String id, PageSlot moveAfter, View anchor) {
        showNameForm(id, moveAfter, anchor, null);
    }

    /**
     * Create ({@code id == null}) or rename a notebook. {@code parentId} is
     * the parent of a notebook created from a child bar; null is top-level.
     */
    private void showNameForm(String id, PageSlot moveAfter, View anchor, String parentId) {
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
                notebookId = created.id;
                moveTo(moveAfter, created.id);
                syncNav();
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
        cancel.setOnClickListener(v -> dismissOverlay());
        Button ok = tinyButton(getString(id == null ? R.string.create : R.string.save), 15);
        styleButton(ok, true);
        ok.setOnClickListener(v -> submit.run());
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
        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.END);
        Button cancel = tinyButton(getString(R.string.cancel), 15);
        cancel.setOnClickListener(v -> dismissOverlay());
        Button delete = tinyButton(getString(R.string.delete), 15);
        styleButton(delete, true);
        delete.setOnClickListener(v -> {
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
        close.setOnClickListener(v -> dismissOverlay());
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
        item.setPadding(dp(12 + 16 * depth), 0, dp(12), 0);
        item.setMinimumHeight(dp(48));
        item.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        item.setSingleLine(true);
        item.setEllipsize(TextUtils.TruncateAt.END);
        item.setOnClickListener(v -> {
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

    /** {@code host} is {@link #drawingArea} for page menus, {@link #root} for top-bar menus. */
    private void showOverlay(View content, FrameLayout.LayoutParams lp, FrameLayout host) {
        dismissOverlay();
        FrameLayout scrim = new FrameLayout(this);
        scrim.setClickable(true);
        scrim.setOnClickListener(v -> dismissOverlay());
        scrim.addView(content, lp);
        overlay = scrim;
        inkView.hold(PageInkView.Hold.OVERLAY);
        host.addView(scrim, matchMatch());
        updateExcludeRects();
    }

    private void dismissOverlay() {
        if (overlay == null) {
            return;
        }
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(overlay.getWindowToken(), 0);
        }
        ((FrameLayout) overlay.getParent()).removeView(overlay);
        overlay = null;
        updateExcludeRects();
        inkView.release(PageInkView.Hold.OVERLAY);
    }

    private void wipe(PageSlot slot) {
        repository.wipePage(slot.page.id);
        inkView.invalidatePage(slot.page.id);
        if (pagesIn(collectionId).size() != slots.size()) {
            reloadPages(scroller.getScrollY());
        } else {
            inkView.redrawAll();
            slot.syncEnabled();
        }
    }

    private void setTool(PageInkView.Tool tool) {
        styleTool(penButton, tool == PageInkView.Tool.PEN);
        styleTool(eraserButton, tool == PageInkView.Tool.ERASER);
        styleTool(lassoButton, tool == PageInkView.Tool.LASSO);
        // A style change takes the firmware pen down until it is applied. The buttons
        // stay pressable the whole time, including the one that is already selected.
        penButton.setEnabled(true);
        penButton.setClickable(true);
        eraserButton.setEnabled(true);
        eraserButton.setClickable(true);
        lassoButton.setEnabled(true);
        lassoButton.setClickable(true);
        inkView.setTool(tool);
        if (tool == PageInkView.Tool.LASSO) {
            showHint(getString(R.string.lasso_hint), false);
        } else if (!inkView.hasSelection()) {
            showHint("", false);
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
            label.setOnClickListener(v -> openNotebook(id));
            label.setOnLongClickListener(v -> {
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
        addExclude(rects, linkingBar, MOVE_EXCLUDE_PAD_PX, origin, loc);
        addExclude(rects, toolbar, dp(6), origin, loc);
        int top = scroller.getScrollY();
        int bottom = top + scroller.getHeight();
        int first = indexAt(top);
        int last = indexAt(Math.max(top, bottom - 1));
        for (int i = first; i >= 0 && i <= last && i < slots.size(); i++) {
            PageSlot slot = slots.get(i);
            // Move and Link are only the word. The link lines are the same kind of hole.
            addExclude(rects, slot.linkButton, MOVE_EXCLUDE_PAD_PX, origin, loc);
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
     * page's "k/n" number (top-left, n counting the trailing blank), Move and
     * ⋯ (Wipe, Delete) (bottom-right) and the dashed separator below it.
     * Slots are rebuilt whenever the list changes, so n is always current.
     */
    private final class PageSlot extends FrameLayout {
        final Board page;
        final TextView number;
        final LinearLayout actions;
        final Button linkButton;
        final Button moveButton;
        final Button moreButton;
        final ArrayList<View> linkLines = new ArrayList<>();

        PageSlot(Context context, Board page, int index, int total) {
            super(context);
            this.page = page;
            number = new TextView(context);
            number.setText(index + "/" + total);
            // One step up from 11sp, and gray rather than ink-black, so it reads on e-ink.
            number.setTextColor(Color.rgb(0x55, 0x55, 0x55));
            number.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            number.setContentDescription(getString(R.string.page_number, index, total));
            FrameLayout.LayoutParams labelLp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT);
            labelLp.gravity = Gravity.TOP | Gravity.START;
            labelLp.leftMargin = dp(10);
            labelLp.topMargin = dp(6);
            addView(number, labelLp);
            actions = new LinearLayout(context);
            actions.setOrientation(LinearLayout.HORIZONTAL);
            actions.setGravity(Gravity.CENTER_VERTICAL);
            linkButton = tinyButton(getString(R.string.link), 14);
            moveButton = tinyButton(getString(R.string.move), 14);
            // The hole the pen skips is this view. Keep it to the word plus a couple of pixels.
            tighten(linkButton);
            tighten(moveButton);
            moreButton = tinyButton(getString(R.string.notebook_options), 18);
            moreButton.setMinimumHeight(dp(48));
            moreButton.setMinimumWidth(dp(48));
            moreButton.setPadding(dp(10), 0, dp(10), dp(4));
            moreButton.setContentDescription(getString(R.string.page_options));
            linkButton.setOnClickListener(v -> startLinking(this));
            moveButton.setOnClickListener(v -> showMoveMenu(this));
            moreButton.setOnClickListener(v -> showPageMenu(this));
            actions.addView(linkButton, wrap());
            LinearLayout.LayoutParams linkGap = wrap();
            linkGap.leftMargin = dp(6);
            actions.addView(moveButton, linkGap);
            LinearLayout.LayoutParams lp = wrap();
            lp.leftMargin = dp(6);
            actions.addView(moreButton, lp);
            FrameLayout.LayoutParams actionsLp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT);
            actionsLp.gravity = Gravity.BOTTOM | Gravity.END;
            actionsLp.rightMargin = dp(10);
            int mark = page.paper != null ? dp(2) : pageGap;
            actionsLp.bottomMargin = (page.paper != null ? 0 : pageGap) + dp(10);
            addView(actions, actionsLp);

            LinearLayout lines = new LinearLayout(context);
            lines.setOrientation(LinearLayout.VERTICAL);
            fillLinkLines(lines);
            FrameLayout.LayoutParams linesLp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT);
            linesLp.gravity = Gravity.BOTTOM | Gravity.START;
            linesLp.leftMargin = dp(10);
            linesLp.bottomMargin = (page.paper != null ? 0 : pageGap) + dp(10);
            addView(lines, linesLp);

            FrameLayout.LayoutParams dashLp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, mark);
            dashLp.gravity = Gravity.BOTTOM;
            addView(new DashedRule(context), dashLp);
            syncEnabled();
        }

        void syncEnabled() {
            boolean enabled = !page.isBlank();
            if (moveButton.isEnabled() != enabled) {
                moveButton.setEnabled(enabled);
                moveButton.setAlpha(enabled ? 1f : 0.35f);
            }
            moreButton.setVisibility(isTrailingBlank(this) ? View.INVISIBLE : View.VISIBLE);
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
            line.setText(getString(outgoing ? R.string.link_outgoing : R.string.link_incoming, ref));
            line.setTextColor(Color.BLACK);
            line.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            line.setPadding(2, 2, 2, 2);
            line.setIncludeFontPadding(false);
            line.setOnClickListener(v -> openLinkedPage(otherId));
            lines.addView(line, wrap());
            linkLines.add(line);
        }
    }

    /** Link and Move are only as big as the word. */
    private static void tighten(Button button) {
        button.setMinimumWidth(0);
        button.setMinimumHeight(0);
        button.setMinWidth(0);
        button.setMinHeight(0);
        button.setPadding(2, 2, 2, 2);
        button.setIncludeFontPadding(false);
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

    /** A black bar along the bottom edge, inset from the sides: the active tool or tab. */
    private static final class Underline extends Drawable {
        private final Paint paint = new Paint();
        private final int thickness;
        private final int inset;

        Underline(int thickness, int inset) {
            this.thickness = thickness;
            this.inset = inset;
            paint.setColor(Color.BLACK);
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
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
