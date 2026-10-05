package com.zetteldraw.penpoc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
import com.onyx.android.sdk.pen.data.TouchPointList;
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
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
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
        UiExecutors.useSynchronousForTest();
    }

    @Test
    public void aStrokeKeepsThePictureItsPenWentDownOn() {
        Board a = new Board("a", 1L);
        Board b = new Board("b", 2L);
        PageInkView ink = new PageInkView(context);
        ink.setPages(Arrays.asList(a, b), new int[]{1000, 1000}, 24);
        idle();

        ink.rawInput().onBeginRawDrawing(false, points(100f, 900f).get(0));
        ink.setContentScrollY(800);
        idle();
        ink.rawInput().onRawDrawingTouchPointListReceived(list(points(100f, 900f)));
        ink.rawInput().onEndRawDrawing(false, points(100f, 900f).get(0));
        idle();

        assertEquals("pen-up after the scroll applied still maps with pen-down's picture", 1, a.strokes.size());
        assertTrue(b.isBlank());
        assertEquals(900f, a.strokes.get(0).points.get(0).y, 0f);
    }

    @Test
    public void callbacksFromTheReaderThreadMapWithThePenDownPicture() throws Exception {
        Board a = new Board("a", 1L);
        Board b = new Board("b", 2L);
        PageInkView ink = new PageInkView(context);
        ink.setPages(Arrays.asList(a, b), new int[]{1000, 1000}, 24);
        idle();

        Thread reader = new Thread(() -> {
            ink.rawInput().onBeginRawDrawing(false, points(100f, 900f).get(0));
            ink.rawInput().onRawDrawingTouchPointListReceived(list(points(100f, 900f)));
            ink.rawInput().onEndRawDrawing(false, points(100f, 900f).get(0));
        }, "raw-input-reader");
        reader.start();
        reader.join(5_000);
        ink.setContentScrollY(800);
        idle();

        assertEquals("a non-stylus renderer calls from its own thread; same result", 1, a.strokes.size());
        assertEquals(900f, a.strokes.get(0).points.get(0).y, 0f);
        assertTrue(b.isBlank());
    }

    @Test
    public void eachReasonToHoldThePenIsReleasedOnItsOwn() {
        PageInkView ink = new PageInkView(context);
        ink.setPages(Arrays.asList(new Board("a", 1L)), new int[]{1000}, 24);
        ink.setLive(true);
        idle();
        assertTrue(ink.inkEnabled());

        ink.hold(PageInkView.Hold.OVERLAY);
        ink.hold(PageInkView.Hold.SCROLL);
        ink.release(PageInkView.Hold.SCROLL);
        assertFalse("a scroll settling under an open menu keeps the pen off", ink.inkEnabled());
        ink.release(PageInkView.Hold.OVERLAY);
        assertTrue(ink.inkEnabled());

        ink.setContentScrollY(300);
        assertFalse("off until the scrolled picture is up", ink.inkEnabled());
        idle();
        assertTrue(ink.inkEnabled());
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

        assertEquals("the stroke belongs to the Scratchpad page it was drawn on", 1, scratch.paper.strokes().size());
        assertEquals(140f, scratch.paper.strokes().get(0).points.get(0).y, 0f);
        assertEquals("and was saved there", 2, repo.scratchpadPages().size());
        List<Board> comedyPages = repo.notebookPages(Notebook.COMEDY.uuid);
        assertEquals(1, comedyPages.size());
        assertTrue("nothing leaked into the notebook", comedyPages.get(0).isBlank());

        ink.addStroke(points(120f, 140f));
        idle();
        assertEquals("new strokes go to the notebook now shown", 1,
                repo.notebookPages(Notebook.COMEDY.uuid).get(0).paper.strokes().size());
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

    @Test
    public void quickTabTapsNeverWaitForStorageAndInkFollowsWhatIsShown() throws Exception {
        BoardRepository repo = ZettelData.repository(context);
        seed(repo, Notebook.COMEDY.uuid);
        seed(repo, Notebook.JOURNAL.uuid);
        ActivityController<CanvasActivity> controller = Robolectric.buildActivity(CanvasActivity.class).setup();
        View root = controller.get().getWindow().getDecorView();
        settle();
        PageInkView ink = findInk(root);
        Board scratch = repo.scratchpadPages().get(0);
        assertTrue(ink.inkEnabled());

        ExecutorService loader = Executors.newSingleThreadExecutor();
        UiExecutors.loader = loader;
        Object lock = repositoryLock(repo);
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(1);
        Thread sync = new Thread(() -> {
            synchronized (lock) {
                holding.countDown();
                try {
                    done.await(20, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }, "sync-worker");
        sync.start();
        assertTrue(holding.await(5, TimeUnit.SECONDS));
        try {
            long slowest = 0;
            for (String tab : new String[]{"comedy", "journal", "comedy", "journal", "comedy"}) {
                long before = System.nanoTime();
                assertTrue(findText(root, tab).performClick());
                idle();
                slowest = Math.max(slowest, (System.nanoTime() - before) / 1_000_000);
                assertTrue(tab + " is marked at once", ((View) findText(root, tab).getParent()).isSelected());
            }
            assertTrue("a tap never waits for storage (slowest " + slowest + " ms)", slowest < 500);
            assertFalse("pen held while the new list loads", ink.inkEnabled());
        } finally {
            done.countDown();
            sync.join(5_000);
        }

        loader.submit(() -> { }).get(10, TimeUnit.SECONDS);
        settle();
        UiExecutors.useSynchronousForTest();
        loader.shutdown();
        List<String> labels = CanvasActivitySmokeTest.pageLabels(root);
        assertEquals("only the last tap's list is shown", 41, labels.size());
        assertEquals("41/41", labels.get(40));
        assertTrue(ink.inkEnabled());
        Board firstComedy = repo.notebookPages(Notebook.COMEDY.uuid).get(0);
        int before = firstComedy.paper.strokes().size();
        ink.addStroke(points(120f, 200f));
        idle();
        assertEquals(before + 1, firstComedy.paper.strokes().size());
        assertEquals(200f, firstComedy.paper.strokes().get(before).points.get(0).y, 0f);
        assertTrue(scratch.strokes.isEmpty());
        controller.pause().stop().destroy();
    }

    private static void seed(BoardRepository repo, String notebookId) {
        for (int p = 0; p < 40; p++) {
            Board page = repo.notebookPages(notebookId).get(p);
            for (int s = 0; s < 60; s++) {
                page.strokes.add(com.zetteldraw.penpoc.data.TestStrokes.stroke(20f + (s % 10) * 50f, 20f + (s / 10) * 60f));
            }
            repo.saveInk(page);
        }
    }

    private static Object repositoryLock(BoardRepository repo) throws Exception {
        Field field = RoomBoardRepository.class.getDeclaredField("lock");
        field.setAccessible(true);
        return field.get(repo);
    }

    private static TouchPointList list(List<TouchPoint> points) {
        TouchPointList list = new TouchPointList();
        for (TouchPoint point : points) {
            list.add(point);
        }
        return list;
    }

    /** Runs delayed main-thread work too (the scroll-settle timer). */
    private static void settle() {
        ShadowLooper.idleMainLooper(2, TimeUnit.SECONDS);
    }

    private static List<View> byDescription(View view, String description) {
        List<View> out = new ArrayList<>();
        if (view.getVisibility() != View.VISIBLE) {
            return out;
        }
        if (description.contentEquals(String.valueOf(view.getContentDescription()))) {
            out.add(view);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                out.addAll(byDescription(group.getChildAt(i), description));
            }
        }
        return out;
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
