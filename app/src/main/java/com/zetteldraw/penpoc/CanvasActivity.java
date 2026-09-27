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
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
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

    private BoardRepository repository;
    private String collectionId = SCRATCHPAD;
    private Notebook notebook = Notebook.values()[0];
    private final HashMap<String, Integer> scrollByCollection = new HashMap<>();

    private FrameLayout root;
    private FrameLayout drawingArea;
    private FrameLayout moveMenu;
    private PageInkView inkView;
    private PageScroller scroller;
    private LinearLayout pageColumn;
    private TextView emptyView;
    private LinearLayout notebookTabs;
    private View notebookTabsRule;
    private Button scratchpadTab;
    private Button notebooksTab;
    private Button penButton;
    private Button eraserButton;
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
        setEraserMode(false);
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
        scroller.removeCallbacks(scrollSettled);
        inkView.close();
        super.onDestroy();
    }

    private View buildNav() {
        LinearLayout nav = row(Gravity.CENTER_VERTICAL | Gravity.START);
        scratchpadTab = tinyButton(getString(R.string.scratchpad), 15);
        notebooksTab = tinyButton(getString(R.string.notebooks), 15);
        scratchpadTab.setOnClickListener(v -> openCollection(SCRATCHPAD));
        notebooksTab.setOnClickListener(v -> openCollection(notebook.id));
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
        penButton = tinyButton(getString(R.string.pen), 13);
        eraserButton = tinyButton(getString(R.string.eraser), 13);
        penButton.setOnClickListener(v -> setEraserMode(false));
        eraserButton.setOnClickListener(v -> setEraserMode(true));
        toolbar.addView(penButton, wrap());
        LinearLayout.LayoutParams lp = wrap();
        lp.leftMargin = dp(6);
        toolbar.addView(eraserButton, lp);
        return toolbar;
    }

    private LinearLayout buildNotebookTabs() {
        LinearLayout tabs = row(Gravity.CENTER_VERTICAL);
        for (Notebook each : Notebook.values()) {
            Button button = tinyButton(each.label, 13);
            button.setSingleLine(true);
            button.setEllipsize(TextUtils.TruncateAt.END);
            button.setOnClickListener(v -> {
                notebook = each;
                openCollection(each.id);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            if (!notebookButtons.isEmpty()) {
                lp.leftMargin = dp(6);
            }
            tabs.addView(button, lp);
            notebookButtons.add(button);
        }
        return tabs;
    }

    private void openCollection(String id) {
        dismissMoveMenu();
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
        emptyView.setVisibility(pages.isEmpty() ? View.VISIBLE : View.GONE);
        inkView.setPages(pages, pageHeight, stride());
        pendingScrollY = Math.max(0, targetScrollY);
        pageColumn.requestLayout();
    }

    private List<Board> pagesIn(String id) {
        if (SCRATCHPAD.equals(id)) {
            return repository.scratchpadPages();
        }
        Notebook each = Notebook.fromId(id);
        return each == null ? new ArrayList<>() : repository.notebookPages(each.uuid);
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
        dismissMoveMenu();
        FrameLayout scrim = new FrameLayout(this);
        scrim.setClickable(true);
        scrim.setOnClickListener(v -> dismissMoveMenu());

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(6), dp(6), dp(6), dp(6));
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(4));
        background.setColor(Color.WHITE);
        background.setStroke(dp(1), Color.BLACK);
        list.setBackground(background);
        list.setClickable(true);
        for (Notebook each : Notebook.values()) {
            Button item = tinyButton(each.label, 15);
            item.setMinimumHeight(dp(40));
            item.setOnClickListener(v -> {
                dismissMoveMenu();
                repository.saveInk(slot.page);
                repository.movePageToNotebook(slot.page.id, each.uuid);
                reloadPages(scroller.getScrollY());
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            if (list.getChildCount() > 0) {
                lp.topMargin = dp(6);
            }
            list.addView(item, lp);
        }

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
        scrim.addView(list, listLp);

        moveMenu = scrim;
        inkView.hold();
        drawingArea.addView(scrim, matchMatch());
        updateExcludeRects();
    }

    private void dismissMoveMenu() {
        if (moveMenu == null) {
            return;
        }
        drawingArea.removeView(moveMenu);
        moveMenu = null;
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

    private void setEraserMode(boolean on) {
        styleButton(penButton, !on);
        styleButton(eraserButton, on);
        inkView.setEraserMode(on);
    }

    private void syncNav() {
        boolean scratch = SCRATCHPAD.equals(collectionId);
        styleButton(scratchpadTab, scratch);
        styleButton(notebooksTab, !scratch);
        notebookTabs.setVisibility(scratch ? View.GONE : View.VISIBLE);
        notebookTabsRule.setVisibility(scratch ? View.GONE : View.VISIBLE);
        Notebook[] all = Notebook.values();
        for (int i = 0; i < all.length; i++) {
            styleButton(notebookButtons.get(i), !scratch && all[i] == notebook);
        }
    }

    private void updateExcludeRects() {
        ArrayList<Rect> rects = new ArrayList<>();
        if (moveMenu != null) {
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
