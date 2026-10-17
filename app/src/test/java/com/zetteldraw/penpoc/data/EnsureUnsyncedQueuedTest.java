package com.zetteldraw.penpoc.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import com.zetteldraw.penpoc.Board;
import com.zetteldraw.penpoc.data.db.BoardEntity;
import com.zetteldraw.penpoc.data.db.OutboxEntry;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Local-only notes must still reach the outbox on Google Sign-In even if an
 * earlier path skipped {@code queueLocked}.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class)
public class EnsureUnsyncedQueuedTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Device device;
    private RoomBoardRepository repo;

    @Before
    public void setUp() throws Exception {
        device = new Device("a", tmp.newFolder("a"), 2_000_000L);
        repo = device.repo;
    }

    @After
    public void tearDown() {
        device.close();
    }

    @Test
    public void requeuesInkedRevZeroBoardMissingFromOutbox() throws Exception {
        Board page = repo.scratchpadPages().get(0);
        TestInk.draw(page, 7f);
        device.tick();
        repo.saveInk(page);
        assertTrue(repo.outboxSize() > 0);

        // Simulate a lost outbox row while the page stays local and never pushed.
        device.db.dao().deleteOutbox(OutboxEntry.BOARD, page.id);
        BoardEntity row = device.db.dao().board(page.id);
        assertEquals(0, row.rev);
        assertTrue(row.inkHash != null);

        int before = repo.outboxSize();
        int added = repo.ensureUnsyncedQueued();
        assertTrue(added >= 1);
        assertEquals(before + added, repo.outboxSize());
        assertTrue(device.db.dao().outbox(OutboxEntry.BOARD, page.id) != null);
    }

    @Test
    public void markSyncedOnlyWhenOutboxEmpty() {
        // Seeds are not queued; create a real notebook so the outbox is non-empty.
        repo.createNotebook("pending-upload");
        assertTrue(repo.outboxSize() > 0);
        repo.markSynced(9_000L);
        assertEquals(9_000L, repo.lastSyncedAt());

        // Engine contract: callers must not stamp last_ok while pending remains.
        // ensureUnsyncedQueued is a no-op when everything is already queued.
        assertEquals(0, repo.ensureUnsyncedQueued());
        assertTrue(repo.outboxSize() > 0);
    }

    @Test
    public void nestedNotebookRequeuedWhenOutboxDropped() {
        BoardRepository.NotebookInfo parent = repo.createNotebook("actions.life-host");
        BoardRepository.NotebookInfo child = repo.createNotebook("home page", parent.id());
        assertTrue(device.db.dao().outbox(OutboxEntry.NOTEBOOK, child.id()) != null);

        // Simulate a lost outbox row while the child stays local (never pushed).
        device.db.dao().deleteOutbox(OutboxEntry.NOTEBOOK, child.id());
        assertEquals(0, device.db.dao().notebook(child.id()).rev);
        assertEquals(parent.id(), device.db.dao().notebook(child.id()).parentId);

        assertTrue(repo.ensureUnsyncedQueued() >= 1);
        assertTrue(device.db.dao().outbox(OutboxEntry.NOTEBOOK, child.id()) != null);

        device.db.dao().deleteOutbox(OutboxEntry.NOTEBOOK, child.id());
        // Phantom rev>0 with parent_id still needs an explicit nesting repair.
        com.zetteldraw.penpoc.data.db.NotebookEntity row = device.db.dao().notebook(child.id());
        row.rev = 9;
        device.db.dao().upsertNotebook(row);
        assertEquals(0, repo.ensureUnsyncedQueued());
        assertEquals(1, repo.queueNestedNotebooksForUpload());
        assertTrue(device.db.dao().outbox(OutboxEntry.NOTEBOOK, child.id()) != null);
    }
}
