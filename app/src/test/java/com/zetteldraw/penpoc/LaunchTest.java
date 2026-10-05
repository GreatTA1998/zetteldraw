package com.zetteldraw.penpoc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import com.zetteldraw.penpoc.data.RoomBoardRepository;
import com.zetteldraw.penpoc.data.SyncStore;
import com.zetteldraw.penpoc.data.ZettelData;
import com.zetteldraw.penpoc.data.db.NotebookEntity;
import com.zetteldraw.penpoc.data.db.ZettelDatabase;
import com.zetteldraw.penpoc.sync.SyncClient;
import com.zetteldraw.penpoc.sync.SyncConfig;
import com.zetteldraw.penpoc.sync.SyncEngine;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Opening the app must always show the local notes, whatever storage threads,
 * sync or the network are doing. The first group runs the production threads.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class, qualifiers = "w600dp-h1000dp",
        shadows = IdleSurfaceViewShadow.class)
public class LaunchTest {
    private final Application app = ApplicationProvider.getApplicationContext();

    @Before
    public void freshRepository() {
        ZettelData.resetForTest();
        UiExecutors.useSynchronousForTest();
    }

    @After
    public void synchronousAgain() {
        UiExecutors.useSynchronousForTest();
    }

    private void productionThreads() throws Exception {
        Field sync = ZettelData.class.getDeclaredField("synchronousForTest");
        sync.setAccessible(true);
        sync.setBoolean(null, false);
        UiExecutors.loader = Executors.newSingleThreadExecutor();
        UiExecutors.retryLoader = Executors.newCachedThreadPool();
        UiExecutors.renderer = Executors.newSingleThreadExecutor();
    }

    @Test
    public void coldLaunchShowsThePagesWithProductionThreads() throws Exception {
        productionThreads();
        assertEquals("[1/1]", waitForPages(launch()).toString());
    }

    @Test
    public void aSyncStuckOnADeadNetworkDoesNotDelayLaunch() throws Exception {
        productionThreads();
        RoomBoardRepository repo = ZettelData.repository(app);
        Thread worker = new Thread(() -> {
            try {
                SyncConfig config = new SyncConfig("http://10.255.255.1:81", "t", "d");
                new SyncEngine(repo, new SyncClient(config, ZettelDatabase.SCHEMA_VERSION), config,
                        ZettelDatabase.SCHEMA_VERSION, System::currentTimeMillis).run();
            } catch (Exception ignored) {
                // Times out long after this test.
            }
        }, "sync-worker");
        worker.setDaemon(true);
        worker.start();
        Thread.sleep(200);
        long started = System.currentTimeMillis();
        assertFalse(waitForPages(launch()).isEmpty());
        assertTrue("shown without waiting for the network", System.currentTimeMillis() - started < 3_000);
    }

    @Test
    public void stoppedWhileTheFirstLoadIsInFlightStillShowsIt() throws Exception {
        productionThreads();
        CountDownLatch gate = new CountDownLatch(1);
        Executor real = UiExecutors.loader;
        UiExecutors.loader = task -> real.execute(() -> {
            try {
                gate.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            task.run();
        });
        ActivityController<CanvasActivity> controller = Robolectric.buildActivity(CanvasActivity.class).setup();
        controller.pause().stop();
        gate.countDown();
        Thread.sleep(300);
        ShadowLooper.idleMainLooper();
        controller.restart().start().resume();
        assertFalse(waitForPages(controller).isEmpty());
    }

    @Test
    public void aFirstLoadThatNeverReturnsIsLoadedAgainAfterTheDeadline() {
        UiExecutors.loader = task -> { };
        ActivityController<CanvasActivity> controller = Robolectric.buildActivity(CanvasActivity.class).setup();
        View root = controller.get().getWindow().getDecorView();
        ShadowLooper.idleMainLooper();
        assertTrue("the stuck load shows nothing yet", CanvasActivitySmokeTest.pageLabels(root).isEmpty());
        ShadowLooper.idleMainLooper(1_100, TimeUnit.MILLISECONDS);
        assertEquals("[1/1]", CanvasActivitySmokeTest.pageLabels(root).toString());
        assertNotNull("tabs came with it", findText(root, "comedy"));
        controller.pause().stop().destroy();
    }

    @Test
    public void aFirstLoadThatThrowsIsLoadedAgainInsteadOfLeavingTheScreenEmpty() {
        AtomicInteger attempts = new AtomicInteger();
        UiExecutors.loader = task -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("simulated storage failure");
        };
        UiExecutors.retryLoader = task -> {
            if (attempts.incrementAndGet() < 3) {
                throw new IllegalStateException("simulated storage failure");
            }
            task.run();
        };
        ActivityController<CanvasActivity> controller;
        try {
            controller = Robolectric.buildActivity(CanvasActivity.class).setup();
        } catch (IllegalStateException e) {
            throw new AssertionError("a failed load must not crash the screen", e);
        }
        View root = controller.get().getWindow().getDecorView();
        ShadowLooper.idleMainLooper(4_000, TimeUnit.MILLISECONDS);
        assertEquals("[1/1]", CanvasActivitySmokeTest.pageLabels(root).toString());
        controller.pause().stop().destroy();
    }

    @Test
    public void pageHeightFollowsTheDrawingArea() throws Exception {
        ActivityController<CanvasActivity> controller = Robolectric.buildActivity(CanvasActivity.class).setup();
        CanvasActivity activity = controller.get();
        ShadowLooper.idleMainLooper();
        Field heightField = CanvasActivity.class.getDeclaredField("pageHeight");
        heightField.setAccessible(true);
        Field areaField = CanvasActivity.class.getDeclaredField("drawingArea");
        areaField.setAccessible(true);
        FrameLayout area = (FrameLayout) areaField.get(activity);
        int before = heightField.getInt(activity);
        assertTrue(before > 0);

        Field barField = CanvasActivity.class.getDeclaredField("topBar");
        barField.setAccessible(true);
        View bar = (View) barField.get(activity);
        int areaBefore = area.getHeight();
        bar.setPadding(bar.getPaddingLeft(), bar.getPaddingTop() + 200, bar.getPaddingRight(), bar.getPaddingBottom());
        ShadowLooper.idleMainLooper();
        ShadowLooper.idleMainLooper();
        assertEquals("the drawing area really shrank", areaBefore - 200, area.getHeight());
        assertEquals("recomputed from the new size, not frozen at the first layout", before - 200,
                heightField.getInt(activity));
        View slot = (View) CanvasActivitySmokeTest.pageLabelViews(activity.getWindow().getDecorView()).get(0).getParent();
        assertEquals("a notebook keeps the slice height it was opened at", before,
                slot.getLayoutParams().height);
        controller.pause().stop().destroy();
    }

    @Test
    public void aReplacedScreenCannotSilenceRemoteRefreshesForTheNewOne() throws Exception {
        ActivityController<CanvasActivity> first = Robolectric.buildActivity(CanvasActivity.class).setup();
        ActivityController<CanvasActivity> second = Robolectric.buildActivity(CanvasActivity.class).setup();
        first.pause().stop().destroy();
        ShadowLooper.idleMainLooper();
        View root = second.get().getWindow().getDecorView();

        RoomBoardRepository repo = ZettelData.repository(app);
        NotebookEntity remote = new NotebookEntity();
        remote.id = java.util.UUID.randomUUID().toString();
        remote.title = "from the web";
        remote.position = "z9";
        remote.createdAt = 1L;
        remote.updatedAt = 2L;
        remote.rev = 7;
        SyncStore.PullPage page = new SyncStore.PullPage();
        page.notebooks.add(remote);
        page.cursor = 7;
        repo.applyPull(page);
        ShadowLooper.idleMainLooper();

        assertNotNull("the open screen still hears about the pull", findText(root, "from the web"));
        second.pause().stop().destroy();
    }

    private ActivityController<CanvasActivity> launch() {
        return Robolectric.buildActivity(CanvasActivity.class).setup();
    }

    private static List<String> waitForPages(ActivityController<CanvasActivity> controller) throws Exception {
        View root = controller.get().getWindow().getDecorView();
        List<String> labels = CanvasActivitySmokeTest.pageLabels(root);
        for (int i = 0; i < 60 && labels.isEmpty(); i++) {
            ShadowLooper.idleMainLooper(50, TimeUnit.MILLISECONDS);
            Thread.sleep(50);
            labels = CanvasActivitySmokeTest.pageLabels(root);
        }
        return labels;
    }

    private static TextView findText(View view, String text) {
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) {
            return (TextView) view;
        }
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
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
