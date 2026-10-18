package com.zetteldraw.penpoc.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import com.zetteldraw.penpoc.Board;
import com.zetteldraw.penpoc.InkRenderer;
import com.zetteldraw.penpoc.data.db.BoardEntity;
import com.zetteldraw.penpoc.data.db.NotebookLogEntity;
import com.zetteldraw.penpoc.data.db.OutboxEntry;

import java.util.Collections;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * One live SoT: live writer never adopts remote ink over local (Ready LOG or
 * unmigrated BOARD residue). Shorter remote never overwrites longer local disk.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class)
public class LiveWriterInkPolicyTest {
    private static final int PAGE = 400;

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Device device;
    private RoomBoardRepository repo;

    @Before
    public void setUp() throws Exception {
        device = new Device("live", tmp.newFolder("live"), 5_000_000L);
        repo = device.repo;
    }

    @After
    public void tearDown() {
        device.close();
    }

    @Test
    public void livePushOnlyKeepsLocalWhenRemoteLonger() throws Exception {
        int localPages = seedDense(30);
        String sheetId = NotebookPaper.sheetId(null);
        long localBytes = repo.localInkLogBytes(sheetId);
        assertTrue(localBytes > 0);

        byte[] longer = logBytes(45);
        assertTrue(longer.length > localBytes);

        repo.setInkPullPolicy(SyncStore.InkPullPolicy.LIVE_PUSH_ONLY);
        pullLog(sheetId, longer, 99);

        assertEquals("live must not adopt longer remote", localBytes, repo.localInkLogBytes(sheetId));
        assertEquals(localPages, repo.scratchpadPages().size());
        assertFalse(InkLogApply.writesPrimaryBody(InkLogApply.Decision.KEEP_LIVE_LOCAL));
    }

    @Test
    public void liveStillRefusesShorterRemote() throws Exception {
        int localPages = seedDense(40);
        String sheetId = NotebookPaper.sheetId(null);
        long localBytes = repo.localInkLogBytes(sheetId);

        byte[] shorter = logBytes(15);
        assertTrue(shorter.length < localBytes);

        repo.setInkPullPolicy(SyncStore.InkPullPolicy.LIVE_PUSH_ONLY);
        pullLog(sheetId, shorter, 99);

        assertEquals(localBytes, repo.localInkLogBytes(sheetId));
        assertEquals(localPages, repo.scratchpadPages().size());
    }

    @Test
    public void catchUpStillAdoptsLongerRemote() throws Exception {
        seedDense(20);
        String sheetId = NotebookPaper.sheetId(null);
        long localBytes = repo.localInkLogBytes(sheetId);

        byte[] longer = logBytes(40);
        assertTrue(longer.length > localBytes);

        repo.setInkPullPolicy(SyncStore.InkPullPolicy.CATCH_UP);
        pullLog(sheetId, longer, 99);

        assertEquals(longer.length, repo.localInkLogBytes(sheetId));
        assertTrue(repo.scratchpadPages().size() > 20);
    }

    @Test
    public void livePushOnlyKeepsUnmigratedBoardResidue() throws Exception {
        // Pull never adopts BOARD (live or catch-up); residue stays local.
        Board page = Board.blank();
        TestInk.draw(page, 10f);
        device.seedUnmigrated(page);
        BoardEntity local = device.db.dao().board(page.id);
        assertNotNull(local);
        String localHash = local.inkHash;
        assertNotNull(device.db.dao().outbox(OutboxEntry.BOARD, page.id));

        // Pull has no BOARD channel (bag deleted); cursor-only page must not
        // adopt over local .zdi residue.
        repo.setInkPullPolicy(SyncStore.InkPullPolicy.LIVE_PUSH_ONLY);
        SyncStore.PullPage pulled = new SyncStore.PullPage();
        pulled.cursor = local.rev + 10;
        repo.applyPull(pulled);

        BoardEntity after = device.db.dao().board(page.id);
        assertEquals("pull must not adopt remote BOARD over local .zdi", localHash, after.inkHash);
        assertEquals(local.rev, after.rev);
        TestInk.assertSameInk(page.strokes,
                InkCodec.decode(new InkFileStore(device.inkDir()).read(page.id)));
    }

    private int seedDense(int inkedSlices) throws Exception {
        assertTrue(repo.ensureSheet(null, PAGE, PAGE, 0L));
        if (repo.scratchpadPages().isEmpty()) {
            repo.createPage(null);
        }
        Board blank = repo.scratchpadPages().get(0);
        for (int i = 0; i < inkedSlices; i++) {
            TestInk.appendCovering(blank.paper, TestInk.stroke(10f, i * PAGE + 20f, 8));
        }
        repo.saveInk(blank);
        for (int i = 0; i < 20 && repo.pendingWrites() > 0; i++) {
            Thread.sleep(20);
        }
        int n = repo.scratchpadPages().size();
        assertTrue(n > inkedSlices / 2);
        return n;
    }

    private void pullLog(String sheetId, byte[] bytes, long rev) throws Exception {
        NotebookLogEntity local = device.db.dao().notebookLog(sheetId);
        NotebookLogEntity remote = local == null ? new NotebookLogEntity() : local.copy();
        remote.id = sheetId;
        remote.notebookId = null;
        remote.inkHash = InkFileStore.sha256(bytes);
        remote.inkBytes = bytes.length;
        remote.sliceHeight = PAGE;
        remote.rev = rev;
        remote.updatedAt = device.clock.get() + 60_000;
        remote.deletedAt = null;
        remote.conflictOf = null;
        if (remote.createdAt == 0L) {
            remote.createdAt = device.clock.get();
        }
        SyncStore.PullPage page = new SyncStore.PullPage();
        page.logs.add(remote);
        page.blobs.put(remote.inkHash, bytes);
        page.cursor = rev;
        repo.applyPull(page);
    }

    private static byte[] logBytes(int slices) throws Exception {
        NotebookPaper paper = NotebookPaper.empty(PAGE);
        for (int i = 0; i < slices; i++) {
            TestInk.appendCovering(paper, TestInk.stroke(10f, i * PAGE + 20f, 8));
        }
        return paper.bytes();
    }
}
