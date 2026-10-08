package com.zetteldraw.penpoc.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import com.zetteldraw.penpoc.Board;
import com.zetteldraw.penpoc.data.db.OutboxEntry;
import com.zetteldraw.penpoc.data.db.PageLinkEntity;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.UUID;

/** A link is the two page ids. Deleting either page tombstones it, and a pull does the same. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class)
public class PageLinkRepositoryTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Device device;
    private RoomBoardRepository repo;

    @Before
    public void setUp() throws Exception {
        device = new Device("a", tmp.newFolder("a"), 30_000_000L);
        repo = device.repo;
    }

    @After
    public void tearDown() {
        device.close();
    }

    @Test
    public void samePageAndDuplicateAreRejectedAndDeletingEitherPageRemovesTheLink() {
        BoardRepository.NotebookInfo from = repo.createNotebook("frombook");
        BoardRepository.NotebookInfo to = repo.createNotebook("tobook");
        repo.ensureSheet(from.id, 400, 800, 1L);
        repo.ensureSheet(to.id, 400, 800, 1L);
        Board source = repo.pages(from.id).get(0);
        source.paper.appendStroke(TestStrokes.stroke(1f, 10f), source.id);
        repo.saveInk(source);
        Board target = repo.pages(to.id).get(0);
        target.paper.appendStroke(TestStrokes.stroke(1f, 10f), target.id);
        repo.saveInk(target);

        assertNull(repo.createLink(source.id, source.id));
        BoardRepository.PageLink link = repo.createLink(source.id, target.id);
        assertEquals(source.id, link.sourceId);
        assertEquals(target.id, link.targetId);
        assertNull("a live pair is stored once", repo.createLink(source.id, target.id));
        assertEquals(1, repo.linksTouching(source.id).size());
        assertEquals(OutboxEntry.UPSERT, device.db.dao().outbox(OutboxEntry.LINK, link.id).op);

        repo.deletePage(source.id);
        assertTrue(repo.linksTouching(target.id).isEmpty());
        assertEquals(OutboxEntry.DELETE, device.db.dao().outbox(OutboxEntry.LINK, link.id).op);

        Board sourceAgain = repo.pages(from.id).get(repo.pages(from.id).size() - 1);
        sourceAgain.paper.appendStroke(TestStrokes.stroke(2f, 10f), sourceAgain.id);
        repo.saveInk(sourceAgain);
        BoardRepository.PageLink second = repo.createLink(sourceAgain.id, target.id);
        repo.deletePage(target.id);
        assertTrue(repo.linksTouching(sourceAgain.id).isEmpty());
        assertEquals(OutboxEntry.DELETE, device.db.dao().outbox(OutboxEntry.LINK, second.id).op);
    }

    @Test
    public void pulledLinkAppearsAndATombstoneRemovesIt() throws Exception {
        String source = UUID.randomUUID().toString();
        String target = UUID.randomUUID().toString();
        PageLinkEntity remote = new PageLinkEntity();
        remote.id = UUID.randomUUID().toString();
        remote.sourceId = source;
        remote.targetId = target;
        remote.createdAt = 1L;
        remote.updatedAt = 5L;
        remote.rev = 9L;
        SyncStore.PullPage page = new SyncStore.PullPage();
        page.links.add(remote);
        page.cursor = 9L;
        repo.applyPull(page);
        assertEquals(remote.id, repo.linksTouching(source).get(0).id);

        PageLinkEntity tombstone = remote.copy();
        tombstone.deletedAt = 6L;
        tombstone.updatedAt = 6L;
        tombstone.rev = 10L;
        SyncStore.PullPage next = new SyncStore.PullPage();
        next.links.add(tombstone);
        next.cursor = 10L;
        repo.applyPull(next);
        assertTrue(repo.linksTouching(source).isEmpty());
        assertTrue(repo.linksTouching(target).isEmpty());
    }
}
