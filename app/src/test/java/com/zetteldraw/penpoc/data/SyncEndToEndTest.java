package com.zetteldraw.penpoc.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.app.Application;

import com.zetteldraw.penpoc.Board;
import com.zetteldraw.penpoc.data.db.NotebookLogEntity;
import com.zetteldraw.penpoc.data.db.ZettelDatabase;
import com.zetteldraw.penpoc.sync.SyncClient;
import com.zetteldraw.penpoc.sync.SyncConfig;
import com.zetteldraw.penpoc.sync.SyncEngine;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.List;
import java.util.UUID;

/**
 * Two simulated devices sync through the real server. Needs the
 * docker-compose stack: {@code WITH_ANDROID=1 server/scripts/integration.sh}
 * (or set ZD_SYNC_URL / ZD_SYNC_TOKEN yourself). Skipped otherwise.
 *
 * <p>Ready-only: ink via {@code saveInk} / {@code .zdl}; conflict shelf is the
 * Ready log shelf (BoardEntity board shelf deleted).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class)
public class SyncEndToEndTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private String url;
    private String token;
    private Device a;
    private Device b;

    @Before
    public void setUp() throws Exception {
        url = System.getenv("ZD_SYNC_URL");
        token = System.getenv("ZD_SYNC_TOKEN");
        assumeTrue("ZD_SYNC_URL not set", url != null && !url.isEmpty());
        a = new Device("a", tmp.newFolder("a"), 1_800_000_000_000L);
        b = new Device("b", tmp.newFolder("b"), 1_800_000_500_000L);
    }

    @After
    public void tearDown() {
        if (a != null) {
            a.close();
        }
        if (b != null) {
            b.close();
        }
    }

    @Test
    public void notebooksPagesAndConflictsRoundTripBetweenTwoDevices() throws Exception {
        assertTrue(a.ensureReady());
        Board page = a.repo.scratchpadPages().get(0);
        TestInk.draw(page, 42f);
        a.tick();
        a.repo.saveInk(page);
        drain(a);
        String title = "e2e " + UUID.randomUUID().toString().substring(0, 8);
        BoardRepository.NotebookInfo notebook = a.repo.createNotebook(title);
        assertTrue(a.ensureReady(notebook.id()));
        a.tick();
        assertTrue(a.repo.movePageToNotebook(page.id, notebook.id()));
        drain(a);
        sync(a);
        assertEquals(0, a.repo.outboxSize());

        // B: fresh install pulls the notebook log; ensure projects Ready pages.
        sync(b);
        assertTrue(b.ensureReady(notebook.id()));
        assertEquals(title, titleOn(b, notebook.id()));
        List<Board> onB = b.repo.notebookPages(notebook.id());
        assertEquals("the page plus the notebook's trailing blank", 2, onB.size());
        assertEquals(page.id, onB.get(0).id);
        TestInk.assertSameInk(page.strokes, onB.get(0).strokes);

        // B renames; A sees it.
        b.tick();
        b.repo.renameNotebook(notebook.id(), title + " renamed");
        sync(b);
        sync(a);
        assertEquals(title + " renamed", titleOn(a, notebook.id()));

        // A deletes the notebook; on B it disappears and the page is back in the scratchpad.
        a.tick();
        a.repo.deleteNotebook(notebook.id());
        sync(a);
        sync(b);
        assertTrue(b.ensureReady());
        assertNull(titleOn(b, notebook.id()));
        Board back = find(b.repo.scratchpadPages(), page.id);
        assertNotNull("page survives the notebook delete", back);
        TestInk.assertSameInk(page.strokes, back.strokes);

        // Both edit the Scratchpad log offline; B's edit is newer and wins, A's log is shelved.
        assertTrue(a.ensureReady());
        page = find(a.repo.scratchpadPages(), page.id);
        assertNotNull(page);
        page.strokes.add(TestInk.stroke(100f, 100f, 6));
        a.tick();
        a.repo.saveInk(page);
        drain(a);
        back.strokes.add(TestInk.stroke(300f, 300f, 6));
        b.tick();
        b.repo.saveInk(back);
        drain(b);
        String sheetId = NotebookPaper.sheetId(null);
        String aHash = a.db.dao().notebookLog(sheetId).inkHash;
        String bHash = b.db.dao().notebookLog(sheetId).inkHash;

        sync(a);
        sync(b);
        sync(a);

        assertEquals(bHash, a.db.dao().notebookLog(sheetId).inkHash);
        assertEquals(bHash, b.db.dao().notebookLog(sheetId).inkHash);
        TestInk.assertSameInk(back.strokes, find(a.repo.scratchpadPages(), page.id).strokes);
        for (Device d : new Device[]{a, b}) {
            boolean kept = false;
            for (NotebookLogEntity copy : d.db.dao().conflictLogCopies()) {
                kept |= sheetId.equals(copy.conflictOf) && aHash.equals(copy.inkHash);
            }
            assertTrue(d.name + " keeps A's losing log as a conflict copy", kept);
            assertEquals(0, d.repo.outboxSize());
        }

        // A deletes the page; B gets the tombstone and still ends its Scratchpad with a blank.
        a.tick();
        a.repo.deletePage(page.id);
        drain(a);
        sync(a);
        sync(b);
        assertTrue(b.ensureReady());
        assertNull(find(b.repo.scratchpadPages(), page.id));
        List<Board> left = b.repo.scratchpadPages();
        assertTrue(left.get(left.size() - 1).isBlank());
    }

    private static void drain(Device d) throws Exception {
        for (int i = 0; i < 40 && d.repo.pendingWrites() > 0; i++) {
            Thread.sleep(20);
        }
        assertEquals(0, d.repo.pendingWrites());
    }

    private void sync(Device d) throws Exception {
        SyncConfig config = new SyncConfig(url, token, "e2e-" + d.name);
        new SyncEngine(d.repo, new SyncClient(config, ZettelDatabase.SCHEMA_VERSION), config,
                ZettelDatabase.SCHEMA_VERSION, d.clock::get).run();
    }

    private static String titleOn(Device d, String id) {
        for (BoardRepository.NotebookInfo n : d.repo.notebooks()) {
            if (n.id().equals(id)) {
                return n.title();
            }
        }
        return null;
    }

    private static Board find(List<Board> boards, String id) {
        for (Board board : boards) {
            if (board.id.equals(id)) {
                return board;
            }
        }
        return null;
    }
}
