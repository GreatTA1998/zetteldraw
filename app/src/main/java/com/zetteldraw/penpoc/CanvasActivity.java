package com.zetteldraw.penpoc;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import java.util.ArrayList;
import java.util.List;

/**
 * Capture-first inbox of boards. Opens on the in-tray; a blank board is
 * always last. File then a notebook name files the current board.
 */
public final class CanvasActivity extends Activity {
    private BoardStore store;
    private String collectionId = BoardStore.INBOX;
    private BoardView boardView;
    private ViewPager2 pager;
    private BoardAdapter adapter;
    private TextView titleView;
    private LinearLayout toolbar;
    private HorizontalScrollView trayScroll;
    private LinearLayout tray;
    private Button fileButton;
    private Button eraserButton;
    private Button wipeButton;
    private boolean eraserMode;
    private boolean fileArmed;
    private boolean browseArmed;
    private String currentBoardId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        InkRenderer.applyBaseWidthMm(getResources().getDisplayMetrics());
        store = new BoardStore(this);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.WHITE);

        pager = new ViewPager2(this);
        pager.setOrientation(ViewPager2.ORIENTATION_VERTICAL);
        pager.setOffscreenPageLimit(1);
        adapter = new BoardAdapter();
        adapter.setHasStableIds(true);
        pager.setAdapter(adapter);
        root.addView(pager, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        boardView = new BoardView(this);
        boardView.setFingerPassthrough(pager);
        boardView.setListener(new BoardView.Listener() {
            @Override
            public void onBoardChanged(Board board) {
                store.persistBoard(board);
                refreshTitle();
            }

            @Override
            public void onBecameNonEmpty(Board board) {
                if (BoardStore.INBOX.equals(collectionId)) {
                    store.onInboxBoardFilled(board.id);
                    reloadPager(board.id);
                } else {
                    store.persistBoard(board);
                }
            }
        });
        root.addView(boardView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        titleView = new TextView(this);
        titleView.setTextColor(Color.BLACK);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        titleView.setPadding(dp(12), dp(10), dp(12), dp(10));
        titleView.setOnClickListener(v -> toggleBrowse());
        FrameLayout.LayoutParams titleLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        titleLp.gravity = Gravity.TOP | Gravity.START;
        root.addView(titleView, titleLp);

        toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        int pad = dp(6);
        toolbar.setPadding(pad, pad, pad, pad);
        fileButton = tinyButton(getString(R.string.file));
        eraserButton = tinyButton(getString(R.string.eraser));
        wipeButton = tinyButton(getString(R.string.wipe));
        fileButton.setOnClickListener(v -> toggleFile());
        eraserButton.setOnClickListener(v -> setEraserMode(!eraserMode));
        wipeButton.setOnClickListener(v -> {
            boardView.wipe();
            store.persistBoard(boardView.getBoard());
            if (BoardStore.INBOX.equals(collectionId)) {
                store.ensureTrailingBlank();
                reloadPager(boardView.getBoard().id);
            }
        });
        toolbar.addView(fileButton, buttonLp(0));
        toolbar.addView(eraserButton, buttonLp(dp(6)));
        toolbar.addView(wipeButton, buttonLp(dp(6)));
        FrameLayout.LayoutParams barLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        barLp.gravity = Gravity.TOP | Gravity.END;
        barLp.topMargin = dp(8);
        barLp.rightMargin = dp(8);
        root.addView(toolbar, barLp);

        trayScroll = new HorizontalScrollView(this);
        trayScroll.setFillViewport(true);
        trayScroll.setHorizontalScrollBarEnabled(false);
        trayScroll.setVisibility(View.GONE);
        tray = new LinearLayout(this);
        tray.setOrientation(LinearLayout.HORIZONTAL);
        tray.setGravity(Gravity.CENTER_VERTICAL);
        tray.setPadding(dp(8), dp(4), dp(8), dp(4));
        trayScroll.addView(tray, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT));
        FrameLayout.LayoutParams trayLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        trayLp.gravity = Gravity.TOP;
        trayLp.topMargin = dp(44);
        root.addView(trayScroll, trayLp);

        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageScrollStateChanged(int state) {
                if (state == ViewPager2.SCROLL_STATE_DRAGGING) {
                    boardView.pauseLive();
                } else if (state == ViewPager2.SCROLL_STATE_IDLE) {
                    boardView.resumeLive();
                }
            }

            @Override
            public void onPageSelected(int position) {
                showBoardAt(position);
            }
        });

        titleView.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> updateExcludeRects());
        toolbar.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> updateExcludeRects());
        trayScroll.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> updateExcludeRects());
        tray.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> updateExcludeRects());

        setContentView(root);
        setEraserMode(false);
        reloadPager(null);
        boardView.setLive(true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        boardView.resumeLive();
    }

    @Override
    protected void onPause() {
        store.persistBoard(boardView.getBoard());
        boardView.pauseLive();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        boardView.close();
        super.onDestroy();
    }

    private void reloadPager(String selectId) {
        List<Board> boards = store.boardsIn(collectionId);
        if (boards.isEmpty() && !BoardStore.INBOX.equals(collectionId)) {
            collectionId = BoardStore.INBOX;
            boards = store.boardsIn(collectionId);
        }
        adapter.setBoards(boards);
        int index = indexOf(boards, selectId);
        if (index < 0) {
            index = 0;
        }
        pager.setCurrentItem(index, false);
        showBoardAt(index);
        refreshTitle();
    }

    private void showBoardAt(int position) {
        List<Board> boards = adapter.boards;
        if (position < 0 || position >= boards.size()) {
            return;
        }
        Board previous = boardView.getBoard();
        if (previous != null && currentBoardId != null && !currentBoardId.equals(boards.get(position).id)) {
            store.persistBoard(previous);
        }
        Board board = store.board(boards.get(position).id);
        if (board == null) {
            return;
        }
        currentBoardId = board.id;
        boardView.bind(board);
        refreshTitle();
    }

    private void toggleFile() {
        fileArmed = !fileArmed;
        browseArmed = false;
        styleButton(fileButton, fileArmed);
        rebuildTray();
    }

    private void toggleBrowse() {
        browseArmed = !browseArmed;
        fileArmed = false;
        styleButton(fileButton, false);
        rebuildTray();
    }

    private void rebuildTray() {
        tray.removeAllViews();
        if (!fileArmed && !browseArmed) {
            trayScroll.setVisibility(View.GONE);
            updateExcludeRects();
            return;
        }
        trayScroll.setVisibility(View.VISIBLE);
        if (browseArmed && !BoardStore.INBOX.equals(collectionId)) {
            tray.addView(trayButton(getString(R.string.inbox), v -> openCollection(BoardStore.INBOX)), buttonLp(0));
        }
        for (Notebook notebook : Notebook.values()) {
            Button button = trayButton(notebook.label, v -> {
                if (fileArmed) {
                    fileCurrent(notebook);
                } else {
                    openCollection(notebook.id);
                }
            });
            tray.addView(button, buttonLp(tray.getChildCount() == 0 ? 0 : dp(6)));
        }
        tray.post(this::updateExcludeRects);
    }

    private void fileCurrent(Notebook notebook) {
        Board current = boardView.getBoard();
        if (current == null || !store.canFile(current.id)) {
            fileArmed = false;
            styleButton(fileButton, false);
            rebuildTray();
            return;
        }
        store.persistBoard(current);
        Board next = store.fileTo(current.id, notebook, collectionId);
        fileArmed = false;
        styleButton(fileButton, false);
        rebuildTray();
        if (!BoardStore.INBOX.equals(collectionId) && store.boardsIn(collectionId).isEmpty()) {
            collectionId = BoardStore.INBOX;
        }
        reloadPager(next == null ? null : next.id);
    }

    private void openCollection(String id) {
        collectionId = id;
        browseArmed = false;
        fileArmed = false;
        styleButton(fileButton, false);
        rebuildTray();
        reloadPager(null);
    }

    private void setEraserMode(boolean on) {
        eraserMode = on;
        styleButton(eraserButton, on);
        boardView.setEraserMode(on);
    }

    private void refreshTitle() {
        List<Board> boards = adapter.boards;
        int index = indexOf(boards, currentBoardId);
        String name = BoardStore.INBOX.equals(collectionId)
                ? getString(R.string.inbox)
                : collectionId;
        if (index >= 0 && !boards.isEmpty()) {
            titleView.setText(name + "  " + (index + 1) + "/" + boards.size());
        } else {
            titleView.setText(name);
        }
    }

    private void updateExcludeRects() {
        ArrayList<Rect> rects = new ArrayList<>();
        addExclude(rects, titleView);
        addExclude(rects, toolbar);
        if (trayScroll.getVisibility() == View.VISIBLE) {
            addExclude(rects, trayScroll);
        }
        boardView.setExtraExcludeRects(rects);
    }

    private void addExclude(List<Rect> rects, View view) {
        if (view == null || view.getWidth() <= 0 || view.getHeight() <= 0) {
            return;
        }
        int[] root = new int[2];
        int[] loc = new int[2];
        boardView.getLocationOnScreen(root);
        view.getLocationOnScreen(loc);
        Rect rect = new Rect(
                loc[0] - root[0],
                loc[1] - root[1],
                loc[0] - root[0] + view.getWidth(),
                loc[1] - root[1] + view.getHeight());
        rect.inset(-dp(4), -dp(4));
        rects.add(rect);
    }

    private static int indexOf(List<Board> boards, String id) {
        if (id == null) {
            return -1;
        }
        for (int i = 0; i < boards.size(); i++) {
            if (id.equals(boards.get(i).id)) {
                return i;
            }
        }
        return -1;
    }

    private LinearLayout.LayoutParams buttonLp(int leftMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = leftMargin;
        return lp;
    }

    private Button trayButton(String label, View.OnClickListener listener) {
        Button button = tinyButton(label);
        button.setOnClickListener(listener);
        return button;
    }

    private Button tinyButton(String label) {
        Button button = new Button(this, null, android.R.attr.borderlessButtonStyle);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        button.setMinHeight(0);
        button.setMinWidth(0);
        button.setMinimumHeight(dp(28));
        button.setMinimumWidth(0);
        button.setPadding(dp(10), dp(4), dp(10), dp(4));
        styleButton(button, false);
        return button;
    }

    private void styleButton(Button button, boolean selected) {
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(4));
        if (selected) {
            background.setColor(Color.BLACK);
            background.setStroke(dp(1), Color.BLACK);
            button.setTextColor(Color.WHITE);
        } else {
            background.setColor(Color.WHITE);
            background.setStroke(dp(1), Color.BLACK);
            button.setTextColor(Color.BLACK);
        }
        button.setBackground(background);
        button.setSelected(selected);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /**
     * Vertical stack of page-sized slots. The live BoardView sits on top;
     * these pages exist so finger-scroll moves between boards.
     */
    private final class BoardAdapter extends RecyclerView.Adapter<BoardAdapter.Holder> {
        final ArrayList<Board> boards = new ArrayList<>();

        void setBoards(List<Board> next) {
            boards.clear();
            boards.addAll(next);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull android.view.ViewGroup parent, int viewType) {
            View page = new View(parent.getContext());
            page.setLayoutParams(new RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.MATCH_PARENT));
            page.setBackgroundColor(Color.TRANSPARENT);
            return new Holder(page);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
        }

        @Override
        public int getItemCount() {
            return boards.size();
        }

        @Override
        public long getItemId(int position) {
            return boards.get(position).id.hashCode();
        }

        final class Holder extends RecyclerView.ViewHolder {
            Holder(View itemView) {
                super(itemView);
            }
        }
    }
}
