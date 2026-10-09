package com.zetteldraw.penpoc.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.app.Application;

import com.zetteldraw.penpoc.Board;
import com.zetteldraw.penpoc.data.db.BoardEntity;
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
        // A: draw, create a notebook, file the page there.
        Board page = a.repo.scratchpadPages().get(0);
        TestInk.draw(page, 42f);
        a.tick();
        a.repo.saveInk(page);
        String title = "e2e " + UUID.randomUUID().toString().substring(0, 8);
        BoardRepository.NotebookInfo notebook = a.repo.createNotebook(title);
        a.tick();
        a.repo.movePageToNotebook(page.id, notebook.id);
        sync(a);
        assertEquals(0, a.repo.outboxSize());

        // B: fresh install pulls the notebook and the page with its ink.
        sync(b);
        assertEquals(title, titleOn(b, notebook.id));
        List<Board> onB = b.repo.notebookPages(notebook.id);
        assertEquals("the page plus the notebook's trailing blank", 2, onB.size());
        assertEquals(page.id, onB.get(0).id);
        TestInk.assertSameInk(page.strokes, onB.get(0).strokes);

        // B renames; A sees it.
        b.tick();
        b.repo.renameNotebook(notebook.id, title + " renamed");
        sync(b);
        sync(a);
        assertEquals(title + " renamed", titleOn(a, notebook.id));

        // A deletes the notebook; on B it disappears and the page is back in the scratchpad.
        a.tick();
        a.repo.deleteNotebook(notebook.id);
        sync(a);
        sync(b);
        assertNull(titleOn(b, notebook.id));
        Board back = find(b.repo.scratchpadPages(), page.id);
        assertNotNull("page survives the notebook delete", back);
        TestInk.assertSameInk(page.strokes, back.strokes);

        // Both edit the page offline; B's edit is newer and wins, A's ink is kept as a hidden copy.
        page.strokes.add(TestInk.stroke(100f, 100f, 6));
        a.tick();
        a.repo.saveInk(page);
        back.strokes.add(TestInk.stroke(300f, 300f, 6));
        b.tick();
        b.repo.saveInk(back);
        String aHash = a.db.dao().board(page.id).inkHash;
        String bHash = b.db.dao().board(page.id).inkHash;

        sync(a);
        sync(b);
        sync(a);

        assertEquals(bHash, a.db.dao().board(page.id).inkHash);
        assertEquals(bHash, b.db.dao().board(page.id).inkHash);
        TestInk.assertSameInk(back.strokes, find(a.repo.scratchpadPages(), page.id).strokes);
        for (Device d : new Device[]{a, b}) {
            boolean kept = false;
            for (BoardEntity copy : d.db.dao().conflictCopies()) {
                kept |= page.id.equals(copy.conflictOf) && aHash.equals(copy.inkHash);
            }
            assertTrue(d.name + " keeps A's losing ink as a conflict copy", kept);
            assertEquals(0, d.repo.outboxSize());
        }

        // A deletes the page; B gets the tombstone and still ends its Scratchpad with a blank.
        a.tick();
        a.repo.deletePage(page.id);
        sync(a);
        sync(b);
        assertNotNull(b.db.dao().board(page.id).deletedAt);
        assertNull(find(b.repo.scratchpadPages(), page.id));
        List<Board> left = b.repo.scratchpadPages();
        assertTrue(left.get(left.size() - 1).isBlank());
    }

    private void sync(Device d) throws Exception {
        SyncConfig config = new SyncConfig(url, token, "e2e-" + d.name);
        new SyncEngine(d.repo, new SyncClient(config, ZettelDatabase.SCHEMA_VERSION), config,
                ZettelDatabase.SCHEMA_VERSION, d.clock::get).run();
    }

    private static String titleOn(Device d, String id) {
        for (BoardRepository.NotebookInfo n : d.repo.notebooks()) {
            if (n.id.equals(id)) {
                return n.title;
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
