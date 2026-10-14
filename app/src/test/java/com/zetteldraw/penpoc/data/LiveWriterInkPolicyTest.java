package com.zetteldraw.penpoc.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import com.zetteldraw.penpoc.Board;
import com.zetteldraw.penpoc.data.db.NotebookLogEntity;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Tip v86 architecture: live writer never adopts remote ink over local;
 * shorter remote never overwrites longer local disk.
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

    private int seedDense(int inkedSlices) throws Exception {
        assertTrue(repo.ensureSheet(null, PAGE, PAGE, 0L));
        Board blank = repo.scratchpadPages().get(0);
        for (int i = 0; i < inkedSlices; i++) {
            blank.paper.appendStroke(TestInk.stroke(10f, i * PAGE + 20f, 8));
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
            paper.appendStroke(TestInk.stroke(10f, i * PAGE + 20f, 8));
        }
        return paper.bytes();
    }
}
