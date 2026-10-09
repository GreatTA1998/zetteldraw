package com.zetteldraw.penpoc.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;

import com.zetteldraw.penpoc.Board;
import com.zetteldraw.penpoc.data.db.ZettelDatabase;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The first page list reads and decodes ink with the repository lock released.
 * A slow file must not stall notebooks, saves, or any other locked call.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class)
public class InkDecodeLockTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void pagesDecodesInkOutsideTheLock() throws Exception {
        File root = tmp.newFolder("device");
        File inkDir = new File(root, "ink");
        Context context = ApplicationProvider.getApplicationContext();
        AtomicLong clock = new AtomicLong(1_000_000L);

        ZettelDatabase warm = open(context, root);
        RoomBoardRepository writer = repo(warm, new InkFileStore(inkDir), root, clock);
        Board page = writer.scratchpadPages().get(0);
        for (int i = 0; i < 12; i++) {
            page.strokes.add(TestInk.stroke(i * 6f, 40f, 80));
        }
        writer.saveInk(page);
        String pageId = page.id;
        int strokes = page.strokes.size();
        warm.close();

        GatedInk gated = new GatedInk(inkDir);
        gated.gate = true;
        ZettelDatabase cold = open(context, root);
        RoomBoardRepository reader = repo(cold, gated, root, clock);

        AtomicReference<List<Board>> pages = new AtomicReference<>();
        AtomicReference<Throwable> failed = new AtomicReference<>();
        Thread loading = new Thread(() -> {
            try {
                pages.set(reader.pages(null));
            } catch (Throwable t) {
                failed.set(t);
            }
        }, "page-list");
        loading.start();
        assertTrue("decode never reached the ink file", gated.inRead.await(5, TimeUnit.SECONDS));

        // If the read still held the lock, notebooks() would sit here until this releases it.
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(2_000);
            } catch (InterruptedException ignored) {
                return;
            }
            gated.release.countDown();
        }, "ink-gate-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();

        // The file read is parked. Anything that needs the lock must still proceed.
        long started = System.nanoTime();
        List<BoardRepository.NotebookInfo> notebooks = reader.notebooks();
        long waitedMs = (System.nanoTime() - started) / 1_000_000L;
        gated.release.countDown();
        watchdog.interrupt();
        assertEquals(4, notebooks.size());
        assertTrue("notebooks waited " + waitedMs + " ms while ink was being read", waitedMs < 1_000);

        loading.join(5_000);
        assertFalse("page list still running", loading.isAlive());
        assertNull(String.valueOf(failed.get()), failed.get());
        List<Board> shown = pages.get();
        assertEquals(strokes, shown.get(0).strokes.size());
        assertEquals(pageId, shown.get(0).id);
        assertTrue(shown.get(shown.size() - 1).isBlank());
        cold.close();
    }

    private static ZettelDatabase open(Context context, File root) {
        return Room.databaseBuilder(context, ZettelDatabase.class, new File(root, "zetteldraw.db").getPath())
                .allowMainThreadQueries()
                .build();
    }

    private static RoomBoardRepository repo(ZettelDatabase db, InkFileStore ink, File root, AtomicLong clock) {
        return new RoomBoardRepository(db, ink, new DirectoryMirror(new File(root, "mirror")),
                Runnable::run, Runnable::run, clock::get);
    }

    /** Blocks inside {@link #read} so a test can prove the lock is not held there. */
    private static final class GatedInk extends InkFileStore {
        volatile boolean gate;
        final CountDownLatch inRead = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        GatedInk(File dir) {
            super(dir);
        }

        @Override
        public byte[] read(String boardId) throws IOException {
            if (gate) {
                inRead.countDown();
                try {
                    if (!release.await(8, TimeUnit.SECONDS)) {
                        throw new IOException("ink read stayed gated");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("ink read interrupted", e);
                }
            }
            return super.read(boardId);
        }
    }
}
