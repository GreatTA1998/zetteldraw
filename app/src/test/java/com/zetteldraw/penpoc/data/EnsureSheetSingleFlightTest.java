package com.zetteldraw.penpoc.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Dense open must not run parallel full {@code .zdl} replays. Concurrent
 * ensureSheet callers join one flight (v152 Scratchpad 20–30s fix).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class)
public class EnsureSheetSingleFlightTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Device device;
    private ExecutorService pool;

    @Before
    public void setUp() throws Exception {
        device = new Device("single-flight", tmp.newFolder("device"), 4_000_000L);
        pool = Executors.newFixedThreadPool(4);
    }

    @After
    public void tearDown() {
        pool.shutdownNow();
        device.close();
    }

    @Test
    public void concurrentEnsureSheetReplaysOnce() throws Exception {
        NotebookPaper paper = NotebookPaper.empty(Device.PAGE);
        for (int i = 0; i < 24; i++) {
            paper.appendStroke(TestInk.stroke(10f, i * Device.PAGE + 20f, 12));
        }
        writeLog(NotebookPaper.sheetId(null), paper.bytes());
        device.repo.invalidateOpenSheet(null);

        int before = RoomBoardRepository.logReplays.get();
        AtomicInteger structureHits = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(4);
        List<Boolean> results = java.util.Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < 4; i++) {
            pool.execute(() -> {
                try {
                    start.await(2, TimeUnit.SECONDS);
                    boolean ok = device.repo.ensureSheet(null, Device.PAGE, Device.PAGE, 0L, pages -> {
                        structureHits.incrementAndGet();
                        assertTrue(pages.size() > 1);
                    });
                    results.add(ok);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    results.add(false);
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        for (Boolean ok : results) {
            assertTrue(ok);
        }
        assertEquals("one replay for four concurrent ensures", 1,
                RoomBoardRepository.logReplays.get() - before);
        assertTrue("structure callback at least once", structureHits.get() >= 1);
        assertTrue(device.repo.isSheetReady(null));
    }

    private void writeLog(String sheetId, byte[] bytes) throws Exception {
        File file = new File(device.inkDir(), sheetId + ".zdl");
        //noinspection ResultOfMethodCallIgnored
        device.inkDir().mkdirs();
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(bytes);
        }
    }
}
