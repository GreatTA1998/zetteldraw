package com.zetteldraw.penpoc;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Rect;
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
import android.widget.LinearLayout;
import android.widget.TextView;

import com.zetteldraw.penpoc.data.BoardRepository;
import com.zetteldraw.penpoc.data.ZettelData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Scratchpad | Notebooks. Each section is a continuous vertical scroll of
 * pages; every page has Move (to a notebook) and Wipe.
 */
public final class CanvasActivity extends Activity {
    private static final long SCROLL_SETTLE_MS = 160;
    private static final String SCRATCHPAD = "scratchpad";
    private static final String NO_NOTEBOOK = "no-notebook";

    private BoardRepository repository;
    private String collectionId = SCRATCHPAD;
    /** Last opened notebook; null until one is chosen or when none exist. */
    private String notebookId;
    private final HashMap<String, Integer> scrollByCollection = new HashMap<>();

    private FrameLayout root;
    private FrameLayout drawingArea;
    /** In-window menu or form (Move, notebook options, name, delete confirm). */
    private FrameLayout overlay;
    private PageInkView inkView;
    private PageScroller scroller;
    private LinearLayout pageColumn;
    private TextView emptyView;
    private LinearLayout notebookTabs;
    private HorizontalScrollView notebookScroll;
    private LinearLayout notebookStrip;
    private View notebookTabsRule;
    private Button scratchpadTab;
    private Button notebooksTab;
    private Button penButton;
    private Button eraserButton;
    private Button lassoButton;
    private Button undoButton;
    private TextView toolHint;
    /** Last committed lasso move; the Undo button puts it back. */
    private Lasso.Move lastMove;
    private final Runnable clearHint = () -> toolHint.setText("");
    private final ArrayList<Button> notebookButtons = new ArrayList<>();
    private final ArrayList<PageSlot> slots = new ArrayList<>();

    private int pageHeight;
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
        pageGap = dp(24);

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.WHITE);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        root.addView(column, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        column.addView(buildNav(), matchWrap());
        column.addView(rule(), ruleLp());
        column.addView(buildToolbar(), matchWrap());
        column.addView(rule(), ruleLp());
        notebookTabs = buildNotebookTabs();
        column.addView(notebookTabs, matchWrap());
        notebookTabsRule = rule();
        column.addView(notebookTabsRule, ruleLp());

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
            }

            @Override
            public void onPageBecameNonEmpty(Board page) {
                if (SCRATCHPAD.equals(collectionId)) {
                    int before = pagesIn(collectionId).size();
                    repository.createScratchpadPage();
                    if (pagesIn(collectionId).size() != before) {
                        reloadPages(scroller.getScrollY());
                    }
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
                lastMove = move;
                syncUndo();
                showHint("", false);
                setTool(PageInkView.Tool.PEN);
            }

            @Override
            public void onLassoCancelled() {
                showHint("", false);
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

        emptyView = new TextView(this);
        emptyView.setText(R.string.empty_notebook);
        emptyView.setTextColor(Color.DKGRAY);
        emptyView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setPadding(dp(24), dp(24), dp(24), dp(24));
        emptyView.setVisibility(View.GONE);
        drawingArea.addView(emptyView, matchMatch());

        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (pageHeight == 0 && root.getHeight() > 0) {
                // v4 boards were drawn on a full-window surface; keep that page size.
                pageHeight = root.getHeight();
                root.post(() -> openCollection(collectionId));
            }
        });

        setContentView(root);
        repository.setRemoteChangeListener(this::onRemoteChange);
        setTool(PageInkView.Tool.PEN);
        syncUndo();
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

    private View buildNav() {
        LinearLayout nav = row(Gravity.CENTER_VERTICAL | Gravity.START);
        scratchpadTab = tinyButton(getString(R.string.scratchpad), 15);
        notebooksTab = tinyButton(getString(R.string.notebooks), 15);
        scratchpadTab.setOnClickListener(v -> openCollection(SCRATCHPAD));
        notebooksTab.setOnClickListener(v -> openNotebooks());
        TextView divider = new TextView(this);
        divider.setText("|");
        divider.setTextColor(Color.BLACK);
        divider.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        divider.setPadding(dp(8), 0, dp(8), 0);
        nav.addView(scratchpadTab, wrap());
        nav.addView(divider, wrap());
        nav.addView(notebooksTab, wrap());
        return nav;
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
        undoButton = tinyButton(getString(R.string.undo_move), 13);
        undoButton.setOnClickListener(v -> undoLastMove());
        penButton = tinyButton(getString(R.string.pen), 13);
        eraserButton = tinyButton(getString(R.string.eraser), 13);
        lassoButton = tinyButton(getString(R.string.lasso), 13);
        penButton.setOnClickListener(v -> setTool(PageInkView.Tool.PEN));
        eraserButton.setOnClickListener(v -> setTool(PageInkView.Tool.ERASER));
        lassoButton.setOnClickListener(v -> setTool(PageInkView.Tool.LASSO));
        LinearLayout.LayoutParams undoLp = wrap();
        undoLp.leftMargin = dp(6);
        undoLp.rightMargin = dp(12);
        toolbar.addView(undoButton, undoLp);
        toolbar.addView(penButton, wrap());
        LinearLayout.LayoutParams lp = wrap();
        lp.leftMargin = dp(6);
        toolbar.addView(eraserButton, lp);
        LinearLayout.LayoutParams lassoLp = wrap();
        lassoLp.leftMargin = dp(6);
        toolbar.addView(lassoButton, lassoLp);
        return toolbar;
    }

    private void undoLastMove() {
        Lasso.Move move = lastMove;
        lastMove = null;
        syncUndo();
        if (move == null) {
            return;
        }
        if (!inkView.undo(move)) {
            showHint(getString(R.string.undo_nothing), true);
        }
    }

    private void syncUndo() {
        boolean enabled = lastMove != null;
        undoButton.setEnabled(enabled);
        undoButton.setAlpha(enabled ? 1f : 0.35f);
    }

    private void showHint(String text, boolean brief) {
        toolHint.removeCallbacks(clearHint);
        toolHint.setText(text);
        if (brief) {
            toolHint.postDelayed(clearHint, 2500);
        }
    }

    /** Scrolling notebook tabs (five fit on screen) with fixed ⋯ and + on the right. */
    private LinearLayout buildNotebookTabs() {
        LinearLayout tabs = row(Gravity.CENTER_VERTICAL);
        notebookScroll = new HorizontalScrollView(this);
        notebookScroll.setHorizontalScrollBarEnabled(false);
        notebookStrip = new LinearLayout(this);
        notebookStrip.setOrientation(LinearLayout.HORIZONTAL);
        notebookScroll.addView(notebookStrip, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        tabs.addView(notebookScroll, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        Button options = tinyButton(getString(R.string.notebook_options), 15);
        options.setOnClickListener(v -> {
            if (notebookId != null && !SCRATCHPAD.equals(collectionId)) {
                showNotebookOptions(notebookId);
            }
        });
        Button add = tinyButton(getString(R.string.add_notebook), 15);
        add.setOnClickListener(v -> showNameForm(null, null));
        LinearLayout.LayoutParams lp = wrap();
        lp.leftMargin = dp(6);
        tabs.addView(options, lp);
        LinearLayout.LayoutParams addLp = wrap();
        addLp.leftMargin = dp(6);
        tabs.addView(add, addLp);
        rebuildNotebookTabs();
        return tabs;
    }

    private void rebuildNotebookTabs() {
        notebookStrip.removeAllViews();
        notebookButtons.clear();
        int width = getResources().getDisplayMetrics().widthPixels;
        int tabWidth = Math.max(dp(64), (width - dp(16) - dp(110) - 4 * dp(6)) / 5);
        for (BoardRepository.NotebookInfo each : repository.notebooks()) {
            Button button = tinyButton(each.title, 13);
            button.setTag(each.id);
            button.setSingleLine(true);
            button.setEllipsize(TextUtils.TruncateAt.END);
            button.setOnClickListener(v -> openNotebook(each.id));
            button.setOnLongClickListener(v -> {
                openNotebook(each.id);
                showNotebookOptions(each.id);
                return true;
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(tabWidth,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            if (!notebookButtons.isEmpty()) {
                lp.leftMargin = dp(6);
            }
            notebookStrip.addView(button, lp);
            notebookButtons.add(button);
        }
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
        openNotebook(target);
    }

    /** {@code id == null} shows the Notebooks section with no notebook (none exist). */
    private void openNotebook(String id) {
        notebookId = id;
        openCollection(id == null ? NO_NOTEBOOK : id);
        Button selected = tabFor(id);
        if (selected != null) {
            notebookScroll.post(() -> notebookScroll.smoothScrollTo(
                    Math.max(0, selected.getLeft() - dp(24)), 0));
        }
    }

    private Button tabFor(String id) {
        for (Button button : notebookButtons) {
            if (button.getTag().equals(id)) {
                return button;
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
            target = Math.max(0, pagesIn(id).size() - 1) * stride();
        } else {
            target = 0;
        }
        reloadPages(target);
    }

    private void reloadPages(int targetScrollY) {
        List<Board> pages = pagesIn(collectionId);
        pageColumn.removeAllViews();
        slots.clear();
        for (int i = 0; i < pages.size(); i++) {
            PageSlot slot = new PageSlot(this, pages.get(i));
            slots.add(slot);
            pageColumn.addView(slot, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, stride()));
        }
        emptyView.setText(NO_NOTEBOOK.equals(collectionId) ? R.string.no_notebooks : R.string.empty_notebook);
        emptyView.setVisibility(pages.isEmpty() ? View.VISIBLE : View.GONE);
        inkView.setPages(pages, pageHeight, stride());
        pendingScrollY = Math.max(0, targetScrollY);
        pageColumn.requestLayout();
    }

    private List<Board> pagesIn(String id) {
        if (SCRATCHPAD.equals(id)) {
            return repository.scratchpadPages();
        }
        if (NO_NOTEBOOK.equals(id)) {
            return new ArrayList<>();
        }
        return repository.notebookPages(id);
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
        addPanelButton(list, getString(R.string.new_notebook_item), () -> showNameForm(null, slot));

        list.measure(View.MeasureSpec.makeMeasureSpec(drawingArea.getWidth(), View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(drawingArea.getHeight(), View.MeasureSpec.AT_MOST));
        int[] area = new int[2];
        int[] button = new int[2];
        drawingArea.getLocationOnScreen(area);
        slot.moveButton.getLocationOnScreen(button);
        int buttonTop = button[1] - area[1];
        int buttonBottom = buttonTop + slot.moveButton.getHeight();
        int top = buttonTop - dp(6) - list.getMeasuredHeight();
        if (top < 0) {
            top = Math.min(buttonBottom + dp(6), Math.max(0, drawingArea.getHeight() - list.getMeasuredHeight()));
        }
        FrameLayout.LayoutParams listLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        listLp.gravity = Gravity.TOP | Gravity.END;
        listLp.topMargin = top;
        listLp.rightMargin = dp(10);
        showOverlay(list, listLp);
    }

    private void moveTo(PageSlot slot, String targetNotebookId) {
        repository.saveInk(slot.page);
        repository.movePageToNotebook(slot.page.id, targetNotebookId);
        reloadPages(scroller.getScrollY());
    }

    private void showNotebookOptions(String id) {
        LinearLayout list = panel();
        TextView title = panelText(titleOf(id), 15);
        list.addView(title);
        addPanelButton(list, getString(R.string.rename), () -> showNameForm(id, null));
        addPanelButton(list, getString(R.string.delete), () -> confirmDelete(id));
        addPanelButton(list, getString(R.string.cancel), null);
        showOverlay(list, topRightLp());
    }

    /**
     * Create ({@code id == null}) or rename a notebook. With {@code moveAfter},
     * the page is moved into the new notebook once it exists.
     */
    private void showNameForm(String id, PageSlot moveAfter) {
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

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.topMargin = dp(24);
        showOverlay(form, lp);
        name.requestFocus();
        name.post(() -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(name, InputMethodManager.SHOW_IMPLICIT);
            }
        });
    }

    private void confirmDelete(String id) {
        int pages = repository.notebookPages(id).size();
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
        FrameLayout.LayoutParams boxLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        boxLp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        boxLp.topMargin = dp(24);
        showOverlay(box, boxLp);
    }

    private LinearLayout panel() {
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(10), dp(8), dp(10), dp(8));
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(4));
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
        Button item = tinyButton(label, 15);
        item.setMinimumHeight(dp(40));
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

    private FrameLayout.LayoutParams topRightLp() {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.TOP | Gravity.END;
        lp.topMargin = dp(6);
        lp.rightMargin = dp(10);
        return lp;
    }

    private void showOverlay(View content, FrameLayout.LayoutParams lp) {
        dismissOverlay();
        FrameLayout scrim = new FrameLayout(this);
        scrim.setClickable(true);
        scrim.setOnClickListener(v -> dismissOverlay());
        scrim.addView(content, lp);
        overlay = scrim;
        inkView.hold();
        drawingArea.addView(scrim, matchMatch());
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
        drawingArea.removeView(overlay);
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
        styleButton(penButton, tool == PageInkView.Tool.PEN);
        styleButton(eraserButton, tool == PageInkView.Tool.ERASER);
        styleButton(lassoButton, tool == PageInkView.Tool.LASSO);
        inkView.setTool(tool);
        if (tool == PageInkView.Tool.LASSO) {
            showHint(getString(R.string.lasso_hint), false);
        } else if (!inkView.hasSelection()) {
            showHint("", false);
        }
    }

    private void syncNav() {
        boolean scratch = SCRATCHPAD.equals(collectionId);
        styleButton(scratchpadTab, scratch);
        styleButton(notebooksTab, !scratch);
        notebookTabs.setVisibility(scratch ? View.GONE : View.VISIBLE);
        notebookTabsRule.setVisibility(scratch ? View.GONE : View.VISIBLE);
        for (Button button : notebookButtons) {
            styleButton(button, !scratch && button.getTag().equals(collectionId));
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

    private int stride() {
        return pageHeight + pageGap;
    }

    /**
     * Transparent stand-in for one page above the ink surface: carries the
     * page's Move / Wipe buttons and the dashed separator below it.
     */
    private final class PageSlot extends FrameLayout {
        final Board page;
        final LinearLayout actions;
        final Button moveButton;
        final Button wipeButton;

        PageSlot(Context context, Board page) {
            super(context);
            this.page = page;
            actions = new LinearLayout(context);
            actions.setOrientation(LinearLayout.HORIZONTAL);
            moveButton = tinyButton(getString(R.string.move), 13);
            wipeButton = tinyButton(getString(R.string.wipe), 13);
            moveButton.setOnClickListener(v -> showMoveMenu(this));
            wipeButton.setOnClickListener(v -> wipe(this));
            actions.addView(moveButton, wrap());
            LinearLayout.LayoutParams lp = wrap();
            lp.leftMargin = dp(6);
            actions.addView(wipeButton, lp);
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
            wipeButton.setEnabled(enabled);
            moveButton.setAlpha(enabled ? 1f : 0.35f);
            wipeButton.setAlpha(enabled ? 1f : 0.35f);
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
