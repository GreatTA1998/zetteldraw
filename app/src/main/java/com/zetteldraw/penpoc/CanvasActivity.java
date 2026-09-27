package com.zetteldraw.penpoc;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
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
import android.widget.TextView;

import com.zetteldraw.penpoc.data.BoardRepository;
import com.zetteldraw.penpoc.data.ZettelData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * One top bar: Scratchpad | notebook tabs | +. Each collection is a continuous
 * vertical scroll of pages; every page has Move (to a notebook) and Wipe.
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
    private PageInkView inkView;
    private PageScroller scroller;
    private LinearLayout pageColumn;
    private HorizontalScrollView notebookScroll;
    private LinearLayout notebookStrip;
    private Button scratchpadTab;
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

    /** Height of a page: the drawing area minus the gap, so a whole page and its controls fit on screen. */
    private int pageHeight;
    /** Full-window page height of v4–v9; old pages whose ink reaches below {@link #pageHeight} keep it. */
    private int legacyPageHeight;
    private long shortPagesSince;
    private int pageGap;
    private boolean scrolling;
    private boolean pagesLoaded;
    private int pendingScrollY = -1;
    private final Runnable scrollSettled = this::onScrollSettled;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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

        topBar = buildTopBar();
        column.addView(topBar, matchWrap());
        column.addView(rule(), ruleLp());
        column.addView(buildToolbar(), matchWrap());
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
                if ((page.isBlank() || last) && pagesIn(collectionId).size() != slots.size()) {
                    reloadPages(scroller.getScrollY());
                }
            }

            @Override
            public void onPageBecameNonEmpty(Board page) {
                // The first stroke stored the trailing blank, and the repository added a new one.
                if (pagesIn(collectionId).size() != slots.size()) {
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
                updateExcludeRects();
            });
        });
        scroller.addView(pageColumn, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT));
        drawingArea.addView(scroller, matchMatch());


        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (pageHeight == 0 && root.getHeight() > 0 && drawingArea.getHeight() > 0) {
                legacyPageHeight = root.getHeight();
                pageHeight = Math.min(legacyPageHeight, drawingArea.getHeight() - pageGap);
                root.post(() -> openCollection(collectionId));
            }
        });

        setContentView(root);
        repository.setRemoteChangeListener(this::onRemoteChange);
        setTool(PageInkView.Tool.PEN);
        syncHistory();
        syncNav();
        inkView.setLive(true);
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
        repository.setRemoteChangeListener(null);
        scroller.removeCallbacks(scrollSettled);
        toolHint.removeCallbacks(clearHint);
        inkView.close();
        super.onDestroy();
    }

    /**
     * Scratchpad pinned left, + pinned right, notebook tabs scrolling between.
     * Only the selected notebook's tab carries a ⋯; long-press works on any tab.
     */
    private LinearLayout buildTopBar() {
        LinearLayout bar = row(Gravity.CENTER_VERTICAL);
        scratchpadTab = new Button(this, null, android.R.attr.borderlessButtonStyle);
        styleTabLabel(scratchpadTab, getString(R.string.scratchpad));
        scratchpadTab.setOnClickListener(v -> openCollection(SCRATCHPAD));
        bar.addView(scratchpadTab, wrap());
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

        bar.addView(barDivider(), barDividerLp());
        addButton = tinyButton(getString(R.string.add_notebook), 22);
        addButton.setContentDescription(getString(R.string.new_notebook));
        addButton.setMinimumWidth(dp(56));
        addButton.setMinimumHeight(dp(48));
        addButton.setPadding(dp(8), 0, dp(8), dp(2));
        addButton.setOnClickListener(v -> showNameForm(null, null, addButton));
        bar.addView(addButton, wrap());
        rebuildNotebookTabs();
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

    /** Active tool: black tile with a white icon. Others: black icon on white with a thin outline. */
    private void styleTool(ImageButton button, boolean selected) {
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(6));
        background.setColor(selected ? Color.BLACK : Color.WHITE);
        background.setStroke(dp(selected ? 2 : 1), Color.BLACK);
        button.setBackground(background);
        button.setImageTintList(ColorStateList.valueOf(selected ? Color.WHITE : Color.BLACK));
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
        notebookStrip.removeAllViews();
        notebookTabs.clear();
        List<BoardRepository.NotebookInfo> notebooks = repository.notebooks();
        if (notebooks.isEmpty()) {
            TextView hint = new TextView(this);
            hint.setText(R.string.no_notebooks_bar);
            hint.setTextColor(Color.BLACK);
            hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            hint.setPadding(dp(4), 0, dp(4), 0);
            notebookStrip.addView(hint, wrap());
        }
        for (BoardRepository.NotebookInfo each : notebooks) {
            NotebookTab tab = new NotebookTab(this, each);
            LinearLayout.LayoutParams lp = wrap();
            if (!notebookTabs.isEmpty()) {
                lp.leftMargin = dp(4);
            }
            notebookStrip.addView(tab, lp);
            notebookTabs.add(tab);
        }
        styleTabs();
    }

    /** Sync pulled changes: notebooks may have been added, renamed or deleted elsewhere. */
    private void onRemoteChange() {
        rebuildNotebookTabs();
        if (!pagesLoaded) {
            syncNav();
            return;
        }
        if (!SCRATCHPAD.equals(collectionId) && tabFor(collectionId) == null) {
            openNotebooks();
        } else {
            reloadPages(scroller.getScrollY());
            syncNav();
        }
    }

    /** Reopens the last notebook, else the first; the Scratchpad when none are left. */
    private void openNotebooks() {
        List<BoardRepository.NotebookInfo> notebooks = repository.notebooks();
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

    private void openNotebook(String id) {
        notebookId = id;
        openCollection(id);
        NotebookTab selected = tabFor(id);
        if (selected != null) {
            notebookScroll.post(() -> revealTab(selected, true));
        }
    }

    /** Scrolls the strip only as far as needed to show the whole tab. */
    private void revealTab(NotebookTab tab, boolean smooth) {
        int viewport = notebookScroll.getWidth();
        if (viewport <= 0) {
            return;
        }
        int x = notebookScroll.getScrollX();
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
            notebookScroll.smoothScrollTo(target, 0);
        } else {
            notebookScroll.scrollTo(target, 0);
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
        dismissOverlay();
        if (!id.equals(collectionId)) {
            inkView.clearHistory();
        }
        if (pageHeight == 0) {
            collectionId = id;
            syncNav();
            return;
        }
        if (pagesLoaded) {
            scrollByCollection.put(collectionId, scroller.getScrollY());
        }
        pagesLoaded = true;
        collectionId = id;
        syncNav();
        Integer saved = scrollByCollection.get(id);
        int target;
        if (saved != null) {
            target = saved;
        } else if (SCRATCHPAD.equals(id)) {
            List<Board> pages = pagesIn(id);
            target = 0;
            for (int i = 0; i < pages.size() - 1; i++) {
                target += heightOf(pages.get(i)) + pageGap;
            }
        } else {
            target = 0;
        }
        reloadPages(target);
    }

    private void reloadPages(int targetScrollY) {
        List<Board> pages = pagesIn(collectionId);
        pageColumn.removeAllViews();
        slots.clear();
        int[] heights = new int[pages.size()];
        for (int i = 0; i < pages.size(); i++) {
            heights[i] = heightOf(pages.get(i));
            PageSlot slot = new PageSlot(this, pages.get(i), i + 1);
            slots.add(slot);
            pageColumn.addView(slot, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, heights[i] + pageGap));
        }
        inkView.setPages(pages, heights, pageGap);
        pendingScrollY = Math.max(0, targetScrollY);
        pageColumn.requestLayout();
    }

    private List<Board> pagesIn(String id) {
        return repository.pages(SCRATCHPAD.equals(id) ? null : id);
    }

    private void onScrolled(int scrollY) {
        if (!scrolling) {
            scrolling = true;
            inkView.hold();
        }
        inkView.setContentScrollY(scrollY);
        scroller.removeCallbacks(scrollSettled);
        scroller.postDelayed(scrollSettled, SCROLL_SETTLE_MS);
    }

    private void onScrollSettled() {
        scrolling = false;
        inkView.setContentScrollY(scroller.getScrollY());
        updateExcludeRects();
        inkView.release();
    }

    /**
     * In-window menu rather than a PopupMenu: a separate popup window does not
     * appear on the Boox over the TouchHelper surface.
     */
    private void showMoveMenu(PageSlot slot) {
        LinearLayout list = panel();
        for (BoardRepository.NotebookInfo each : repository.notebooks()) {
            addPanelButton(list, each.title, () -> moveTo(slot, each.id));
        }
        addPanelButton(list, getString(R.string.new_notebook_item), () -> showNameForm(null, slot, null));

        showOverlay(list, pageAnchoredLp(slot.moveButton, list), drawingArea);
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
        cancel.setMinimumHeight(dp(44));
        cancel.setOnClickListener(v -> dismissOverlay());
        Button confirm = tinyButton(getString(delete ? R.string.delete : R.string.wipe), 15);
        confirm.setMinimumHeight(dp(44));
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

    private void deletePage(PageSlot slot) {
        repository.deletePage(slot.page.id);
        inkView.invalidatePage(slot.page.id);
        reloadPages(scroller.getScrollY());
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
        revealTab(tab, false);
        LinearLayout menu = panel();
        menu.setMinimumWidth(Math.max(dp(200), tab.getWidth()));
        addPanelButton(menu, getString(R.string.rename), () -> showNameForm(id, null, tab));
        addPanelButton(menu, getString(R.string.delete), () -> confirmDelete(id, tab));
        showOverlay(menu, anchoredLp(tab, menu), root);
    }

    /**
     * Create ({@code id == null}) or rename a notebook. With {@code moveAfter},
     * the page is moved into the new notebook once it exists. With an
     * {@code anchor} in the top bar the form hangs under it.
     */
    private void showNameForm(String id, PageSlot moveAfter, View anchor) {
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
            BoardRepository.NotebookInfo created = repository.createNotebook(text);
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
            rebuildNotebookTabs();
            openNotebooks();
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

    private LinearLayout panel() {
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(10), dp(8), dp(10), dp(8));
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(4));
        background.setColor(Color.WHITE);
        background.setStroke(dp(2), Color.BLACK);
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
        Button item = tinyButton(label, 16);
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
        if (list.getChildCount() > 0) {
            lp.topMargin = dp(6);
        }
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
        int[] barAt = new int[2];
        root.getLocationInWindow(rootAt);
        anchor.getLocationInWindow(anchorAt);
        topBar.getLocationInWindow(barAt);
        int left = anchorAt[0] - rootAt[0];
        left = Math.max(dp(8), Math.min(left, root.getWidth() - content.getMeasuredWidth() - dp(8)));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.leftMargin = left;
        lp.topMargin = barAt[1] - rootAt[1] + topBar.getHeight() - dp(4);
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
        inkView.hold();
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
        inkView.release();
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
        boolean scratch = SCRATCHPAD.equals(collectionId);
        styleTab(scratchpadTab, scratch);
        inkLabel(scratchpadTab, scratch);
        for (NotebookTab tab : notebookTabs) {
            tab.setCurrent(!scratch && tab.id.equals(collectionId));
        }
    }

    /** E-ink selection: inverted and bold, no greys. */
    private void styleTab(View tab, boolean selected) {
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(4));
        background.setColor(selected ? Color.BLACK : Color.WHITE);
        tab.setBackground(background);
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
        label.setTextColor(selected ? Color.WHITE : Color.BLACK);
        label.setTypeface(Typeface.DEFAULT, selected ? Typeface.BOLD : Typeface.NORMAL);
    }

    /**
     * One notebook in the top bar. Tap opens it, long-press opens it and its
     * menu; while it is the open notebook it also shows a ⋯ for that menu.
     */
    private final class NotebookTab extends LinearLayout {
        final String id;
        final Button label;
        final View divider;
        final Button more;

        NotebookTab(Context context, BoardRepository.NotebookInfo info) {
            super(context);
            id = info.id;
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

            divider = new View(context);
            divider.setBackgroundColor(Color.WHITE);
            addView(divider, new LinearLayout.LayoutParams(dp(1), dp(24)));

            more = new Button(context, null, android.R.attr.borderlessButtonStyle);
            styleTabLabel(more, getString(R.string.notebook_options));
            more.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
            more.setMinimumWidth(dp(48));
            more.setPadding(dp(10), 0, dp(12), dp(4));
            more.setContentDescription(getString(R.string.notebook_menu, info.title));
            more.setOnClickListener(v -> showNotebookMenu(id));
            addView(more, wrap());
            setCurrent(false);
        }

        void setCurrent(boolean current) {
            styleTab(this, current);
            inkLabel(label, current);
            inkLabel(more, current);
            divider.setVisibility(current ? VISIBLE : GONE);
            more.setVisibility(current ? VISIBLE : GONE);
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
        int top = scroller.getScrollY();
        int bottom = top + scroller.getHeight();
        for (PageSlot slot : slots) {
            if (slot.getBottom() < top || slot.getTop() > bottom) {
                continue;
            }
            View actions = slot.actions;
            if (actions.getWidth() <= 0) {
                continue;
            }
            actions.getLocationOnScreen(loc);
            Rect rect = new Rect(
                    loc[0] - origin[0],
                    loc[1] - origin[1],
                    loc[0] - origin[0] + actions.getWidth(),
                    loc[1] - origin[1] + actions.getHeight());
            rect.inset(-dp(6), -dp(6));
            rects.add(rect);
        }
        inkView.setExtraExcludeRects(rects);
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
    private int heightOf(Board page) {
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
     * page's number (bottom-left), Move and ⋯ (Wipe, Delete) (bottom-right)
     * and the dashed separator below it.
     */
    private final class PageSlot extends FrameLayout {
        final Board page;
        final LinearLayout actions;
        final Button moveButton;
        final Button moreButton;

        PageSlot(Context context, Board page, int number) {
            super(context);
            this.page = page;
            TextView label = new TextView(context);
            label.setText(String.valueOf(number));
            label.setTextColor(Color.DKGRAY);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            label.setContentDescription(getString(R.string.page_number, number));
            FrameLayout.LayoutParams labelLp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT);
            labelLp.gravity = Gravity.BOTTOM | Gravity.START;
            labelLp.leftMargin = dp(14);
            labelLp.bottomMargin = pageGap + dp(12);
            addView(label, labelLp);
            actions = new LinearLayout(context);
            actions.setOrientation(LinearLayout.HORIZONTAL);
            moveButton = tinyButton(getString(R.string.move), 14);
            moveButton.setMinimumHeight(dp(44));
            moreButton = tinyButton(getString(R.string.notebook_options), 18);
            moreButton.setMinimumHeight(dp(44));
            moreButton.setMinimumWidth(dp(48));
            moreButton.setPadding(dp(10), 0, dp(10), dp(4));
            moreButton.setContentDescription(getString(R.string.page_options));
            moveButton.setOnClickListener(v -> showMoveMenu(this));
            moreButton.setOnClickListener(v -> showPageMenu(this));
            actions.addView(moveButton, wrap());
            LinearLayout.LayoutParams lp = wrap();
            lp.leftMargin = dp(6);
            actions.addView(moreButton, lp);
            FrameLayout.LayoutParams actionsLp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT);
            actionsLp.gravity = Gravity.BOTTOM | Gravity.END;
            actionsLp.rightMargin = dp(10);
            actionsLp.bottomMargin = pageGap + dp(10);
            addView(actions, actionsLp);

            FrameLayout.LayoutParams dashLp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, pageGap);
            dashLp.gravity = Gravity.BOTTOM;
            addView(new DashedRule(context), dashLp);
            syncEnabled();
        }

        void syncEnabled() {
            boolean enabled = !page.isBlank();
            if (moveButton.isEnabled() == enabled) {
                return;
            }
            moveButton.setEnabled(enabled);
            moveButton.setAlpha(enabled ? 1f : 0.35f);
        }
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
        button.setMinimumHeight(dp(32));
        button.setMinimumWidth(0);
        button.setPadding(dp(12), dp(4), dp(12), dp(4));
        styleButton(button, false);
        return button;
    }

    private void styleButton(Button button, boolean selected) {
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(4));
        background.setStroke(dp(1), Color.BLACK);
        background.setColor(selected ? Color.BLACK : Color.WHITE);
        button.setTextColor(selected ? Color.WHITE : Color.BLACK);
        button.setBackground(background);
        button.setSelected(selected);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
