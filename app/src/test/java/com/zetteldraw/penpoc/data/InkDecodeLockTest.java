package com.zetteldraw.penpoc.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import com.zetteldraw.penpoc.Board;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.List;

/**
 * Ready-only {@code pages()}: empty until ensureSheet; after Ready, projection
 * does not decode legacy {@code .zdi} (that path was deleted with warmCache).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class)
public class InkDecodeLockTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Device device;
    private RoomBoardRepository repo;

    @Before
    public void setUp() throws Exception {
        device = new Device("a", tmp.newFolder("a"), 1_000_000L);
        repo = device.repo;
    }

    @After
    public void tearDown() {
        device.close();
    }

    @Test
    public void pagesEmptyUntilReadyThenProjectsWithoutLegacyDecode() {
        assertTrue(repo.scratchpadPages().isEmpty());
        assertTrue(device.ensureReady());
        // ensureReady = sheet only; Create new page is explicit.
        assertEquals(0, repo.scratchpadPages().size());
        assertTrue(device.ensureReadyWithPage(null));
        List<Board> pages = repo.scratchpadPages();
        assertEquals(1, pages.size());
        assertTrue(pages.get(0).isBlank());
        assertTrue(pages.get(0).paper != null);

        TestInk.draw(pages.get(0), 10f);
        repo.saveInk(pages.get(0));
        List<Board> after = repo.scratchpadPages();
        assertEquals(1, after.size());
        assertEquals(1, TestInk.strokesOf(after.get(0)).size());
        // notebooks() stays cheap (no ink decode) while Ready pages are projected.
        long started = System.nanoTime();
        assertEquals(4, repo.notebooks().size());
        assertTrue((System.nanoTime() - started) / 1_000_000L < 1_000);
    }
}
