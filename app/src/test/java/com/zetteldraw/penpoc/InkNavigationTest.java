package com.zetteldraw.penpoc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;

import com.onyx.android.sdk.data.note.TouchPoint;
import com.zetteldraw.penpoc.data.BoardRepository;
import com.zetteldraw.penpoc.data.InkCodec;
import com.zetteldraw.penpoc.data.InkFileStore;
import com.zetteldraw.penpoc.data.InkMirror;
import com.zetteldraw.penpoc.data.RoomBoardRepository;
import com.zetteldraw.penpoc.data.SlowInkFileStore;
import com.zetteldraw.penpoc.data.ZettelData;
import com.zetteldraw.penpoc.data.db.ZettelDatabase;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * On a stylus Boox the Onyx SDK posts every pen callback to the main thread.
 * When that thread stalls, strokes wait in its queue while the finger scroll
 * or tab tap that queued up alongside them is handled first. These tests
 * replay exactly that order: a stroke must still land where it was drawn.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class, qualifiers = "w600dp-h1000dp",
        shadows = IdleSurfaceViewShadow.class)
public class InkNavigationTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final Handler main = new Handler(Looper.getMainLooper());
    private Context context;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        ZettelData.resetForTest();
    }

    @Test
    public void aStrokeQueuedBeforeAScrollKeepsTheScrollItWasDrawnAt() {
        Board a = new Board("a", 1L);
        Board b = new Board("b", 2L);
        PageInkView ink = new PageInkView(context);
        ink.setPages(Arrays.asList(a, b), new int[]{1000, 1000}, 24);
        idle();

        main.post(() -> ink.addStroke(points(100f, 900f)));
        ink.setContentScrollY(800);
        idle();

        assertEquals("drawn near the bottom of page 1, stays there", 1, a.strokes.size());
        assertTrue(b.isBlank());
        assertEquals(900f, a.strokes.get(0).points.get(0).y, 0f);

        ink.addStroke(points(100f, 900f));
        assertEquals("once the scrolled picture is up, the same spot is on page 2", 1, b.strokes.size());
        assertEquals(900f + 800f - 1024f, b.strokes.get(0).points.get(0).y, 0f);
    }

    @Test
    public void strokesQueuedDuringAStallIgnoreTheFlingThatFollows() {
        Board a = new Board("a", 1L);
        Board b = new Board("b", 2L);
        PageInkView ink = new PageInkView(context);
        ink.setPages(Arrays.asList(a, b), new int[]{1000, 1000}, 24);
        ink.setContentScrollY(100);
        idle();

        float[] ys = {300f, 600f, 850f};
        for (float y : ys) {
            main.post(() -> ink.addStroke(points(80f, y)));
        }
        for (int frame = 1; frame <= 12; frame++) {
            ink.setContentScrollY(100 + frame * 90);
        }
        idle();

        assertEquals(3, a.strokes.size());
        assertTrue("nothing spills onto the next page", b.isBlank());
        for (int i = 0; i < ys.length; i++) {
            assertEquals(ys[i] + 100f, a.strokes.get(i).points.get(0).y, 0f);
        }
    }

    @Test
    public void aStrokeQueuedBeforeATabSwitchStaysOnItsOwnPage() {
        ActivityController<CanvasActivity> controller = Robolectric.buildActivity(CanvasActivity.class).setup();
        View root = controller.get().getWindow().getDecorView();
        idle();
        BoardRepository repo = ZettelData.repository(context);
        PageInkView ink = findInk(root);
        Board scratch = repo.scratchpadPages().get(0);

        main.post(() -> ink.addStroke(points(120f, 140f)));
        TextView comedy = findText(root, "comedy");
        assertTrue(comedy.performClick());
        idle();

        assertEquals("the stroke belongs to the Scratchpad page it was drawn on", 1, scratch.strokes.size());
        assertEquals(140f, scratch.strokes.get(0).points.get(0).y, 0f);
        assertEquals("and was saved there", 2, repo.scratchpadPages().size());
        List<Board> comedyPages = repo.notebookPages(Notebook.COMEDY.uuid);
        assertEquals(1, comedyPages.size());
        assertTrue("nothing leaked into the notebook", comedyPages.get(0).isBlank());

        ink.addStroke(points(120f, 140f));
        idle();
        assertEquals("new strokes go to the notebook now shown", 1,
                repo.notebookPages(Notebook.COMEDY.uuid).get(0).strokes.size());
        controller.pause().stop().destroy();
    }

    @Test
    public void scrollingWhileASaveIsStuckOnDiskKeepsEveryStrokeInPlace() throws Exception {
        File root = tmp.newFolder("device");
        ZettelDatabase db = Room.databaseBuilder(context, ZettelDatabase.class,
                new File(root, "zetteldraw.db").getPath()).allowMainThreadQueries().build();
        File inkDir = new File(root, "ink");
        SlowInkFileStore disk = new SlowInkFileStore(inkDir);
        ExecutorService writer = Executors.newSingleThreadExecutor();
        RoomBoardRepository repo = new RoomBoardRepository(db, disk, InkMirror.NONE, writer, Runnable::run,
                Runnable::run, System::currentTimeMillis);
        try {
            Board first = repo.scratchpadPages().get(0);
            PageInkView ink = new PageInkView(context);
            ink.setListener(new SavingListener(repo));
            ink.setPages(Arrays.asList(first, new Board("next", 2L)), new int[]{1000, 1000}, 24);
            idle();

            long started = System.nanoTime();
            ink.addStroke(points(100f, 500f));
            disk.awaitStall();
            main.post(() -> ink.addStroke(points(100f, 900f)));
            ink.setContentScrollY(700);
            idle();
            long ms = (System.nanoTime() - started) / 1_000_000;
            assertTrue("the UI never waited for the disk (" + ms + " ms)", ms < 1_000);

            assertEquals(2, first.strokes.size());
            assertEquals(900f, first.strokes.get(1).points.get(0).y, 0f);

            disk.release();
            for (int round = 0; round < 3; round++) {
                writer.submit(() -> { }).get(10, TimeUnit.SECONDS);
            }
            List<InkRenderer.InkStroke> saved = InkCodec.decode(new InkFileStore(inkDir).read(first.id));
            assertEquals(2, saved.size());
            assertEquals(500f, saved.get(0).points.get(0).y, 0f);
            assertEquals(900f, saved.get(1).points.get(0).y, 0f);
        } finally {
            disk.release();
            writer.shutdown();
            writer.awaitTermination(10, TimeUnit.SECONDS);
            db.close();
        }
    }

    private static final class SavingListener implements PageInkView.Listener {
        private final BoardRepository repo;

        SavingListener(BoardRepository repo) {
            this.repo = repo;
        }

        @Override
        public void onPageChanged(Board page) {
            repo.saveInk(page);
        }

        @Override
        public void onPageBecameNonEmpty(Board page) {
        }

        @Override
        public void onLassoSelected(int selected) {
        }

        @Override
        public void onLassoMoved(Lasso.Move move) {
        }

        @Override
        public void onLassoCancelled() {
        }

        @Override
        public void onHistoryChanged() {
        }
    }

    private static ArrayList<TouchPoint> points(float x, float y) {
        ArrayList<TouchPoint> list = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            list.add(new TouchPoint(x + i * 6f, y + i * 4f, 0.5f, 1f, 0, 0, 1L + i));
        }
        return list;
    }

    private static void idle() {
        ShadowLooper.idleMainLooper();
    }

    private static PageInkView findInk(View view) {
        if (view instanceof PageInkView) {
            return (PageInkView) view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                PageInkView found = findInk(group.getChildAt(i));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static TextView findText(View view, String text) {
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) {
            return (TextView) view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView found = findText(group.getChildAt(i), text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
