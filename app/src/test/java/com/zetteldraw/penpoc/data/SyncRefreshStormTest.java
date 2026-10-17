package com.zetteldraw.penpoc.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;

import com.zetteldraw.penpoc.Board;
import com.zetteldraw.penpoc.InkRenderer;
import com.zetteldraw.penpoc.data.db.NotebookLogEntity;
import com.zetteldraw.penpoc.data.db.ZettelDatabase;
import com.zetteldraw.penpoc.sync.SyncClient;
import com.zetteldraw.penpoc.sync.SyncConfig;
import com.zetteldraw.penpoc.sync.SyncEngine;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A sync pass that pulls many pages used to queue one full page-list reload
 * per pulled page onto the main thread. It must reach the UI as one refresh.
 *
 * <p>Ready-only: storm pages are notebook_logs, not BoardEntity stroke swaps.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class)
public class SyncRefreshStormTest {
    private static final int PAGES = 30;
    private static final int PAGE_HEIGHT = 800;

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private ZettelDatabase db;
    private RoomBoardRepository repo;
    /** The UI thread as a queue, so the test sees exactly what would be posted. */
    private final List<Runnable> uiQueue = new ArrayList<>();
    private final AtomicInteger refreshes = new AtomicInteger();
    private TinyServer server;

    @Before
    public void setUp() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        File root = tmp.newFolder("device");
        db = Room.databaseBuilder(context, ZettelDatabase.class, new File(root, "zetteldraw.db").getPath())
                .allowMainThreadQueries()
                .build();
        repo = new RoomBoardRepository(db, new InkFileStore(new File(root, "ink")), InkMirror.NONE,
                Runnable::run, Runnable::run, uiQueue::add, System::currentTimeMillis);
        repo.addRemoteChangeListener(refreshes::incrementAndGet);
        assertTrue(repo.ensureSheet(null, PAGE_HEIGHT, PAGE_HEIGHT, 0L));
    }

    @After
    public void tearDown() {
        if (server != null) {
            server.close();
        }
        db.close();
    }

    @Test
    public void aWholeSyncPassReachesTheUiAsOneRefresh() throws Exception {
        server = new TinyServer(path -> {
            if (path.startsWith("/sync/push")) {
                return "{\"results\": []}";
            }
            long since = Long.parseLong(path.replaceAll(".*since=(\\d+).*", "$1"));
            return pullLogPage(since).toString();
        });
        SyncConfig config = new SyncConfig("http://127.0.0.1:" + server.port(), "t", "d");
        new SyncEngine(repo, new SyncClient(config, ZettelDatabase.SCHEMA_VERSION), config,
                ZettelDatabase.SCHEMA_VERSION, System::currentTimeMillis).run();

        assertEquals("one UI turn for the whole pass, not one per pulled page", 1, uiQueue.size());
        runUi();
        assertEquals(1, refreshes.get());
        assertTrue(repo.isSheetReady(null));
        assertEquals(PAGES, db.dao().notebookLog(NotebookPaper.SCRATCHPAD_ID).rev);
        List<Board> pages = repo.scratchpadPages();
        assertTrue("Ready projection after log storm", pages.size() >= 1);
        assertTrue(pages.get(0).paper.strokes().size() >= 1);
    }

    @Test
    public void pullsOutsideABatchCoalesceWhileTheUiIsBusy() throws Exception {
        for (long rev = 1; rev <= 10; rev++) {
            repo.applyPull(SyncProtocolPages.logPage(rev));
        }
        assertEquals("one pending UI turn however many pulls land meanwhile", 1, uiQueue.size());
        runUi();
        assertEquals(1, refreshes.get());
        repo.applyPull(SyncProtocolPages.logPage(11));
        assertEquals(1, uiQueue.size());
        runUi();
        assertEquals(2, refreshes.get());
    }

    @Test
    public void pulledReadyLogReachesScreensViaListenerNotBoardStrokeSwap() throws Exception {
        Board page = repo.scratchpadPages().get(0);
        String sliceId = page.id;
        NotebookPaper remotePaper = NotebookPaper.empty(PAGE_HEIGHT);
        List<InkRenderer.InkStroke> ink = Collections.singletonList(TestInk.stroke(300f, 300f, 5));
        remotePaper.appendStroke(ink.get(0), sliceId);
        byte[] bytes = remotePaper.bytes();
        NotebookLogEntity remote = new NotebookLogEntity();
        remote.id = NotebookPaper.SCRATCHPAD_ID;
        remote.notebookId = null;
        remote.inkHash = InkFileStore.sha256(bytes);
        remote.inkBytes = bytes.length;
        remote.sliceHeight = PAGE_HEIGHT;
        remote.createdAt = 1L;
        remote.updatedAt = System.currentTimeMillis() + 60_000;
        remote.rev = 9;
        SyncStore.PullPage pulled = new SyncStore.PullPage();
        pulled.logs.add(remote);
        pulled.blobs.put(remote.inkHash, bytes);
        pulled.cursor = 9;
        repo.applyPull(pulled);
        assertEquals(1, uiQueue.size());
        runUi();
        assertEquals(1, refreshes.get());
        Board after = repo.scratchpadPages().get(0);
        TestInk.assertSameInk(ink, after.paper.strokes());
    }

    private void runUi() {
        ArrayList<Runnable> batch = new ArrayList<>(uiQueue);
        uiQueue.clear();
        for (Runnable r : batch) {
            r.run();
        }
    }

    private static JSONObject pullLogPage(long since) throws Exception {
        long rev = since + 1;
        JSONObject response = new JSONObject();
        response.put("schema_version", ZettelDatabase.SCHEMA_VERSION);
        response.put("cursor", rev);
        response.put("has_more", rev < PAGES);
        response.put("notebooks", new JSONArray());
        response.put("boards", new JSONArray());
        NotebookPaper paper = NotebookPaper.empty(PAGE_HEIGHT);
        paper.appendStroke(TestInk.stroke(10f * rev, 20f, 4), "slice-" + rev);
        byte[] bytes = paper.bytes();
        String hash = InkFileStore.sha256(bytes);
        response.put("logs", new JSONArray().put(new JSONObject()
                .put("id", NotebookPaper.SCRATCHPAD_ID)
                .put("notebook_id", JSONObject.NULL)
                .put("ink_hash", hash)
                .put("ink_bytes", bytes.length)
                .put("slice_height", PAGE_HEIGHT)
                .put("conflict_of", JSONObject.NULL)
                .put("created_at", rev)
                .put("updated_at", rev)
                .put("rev", rev)
                .put("deleted_at", JSONObject.NULL)));
        response.put("links", new JSONArray());
        response.put("blobs", new JSONObject().put(hash, Base64.getEncoder().encodeToString(bytes)));
        return response;
    }

    private interface Handler {
        String respond(String path) throws Exception;
    }

    /** Just enough HTTP/1.1 for SyncClient: one request per connection, JSON replies. */
    private static final class TinyServer implements AutoCloseable {
        private final java.net.ServerSocket socket;
        private final Thread thread;

        TinyServer(Handler handler) throws java.io.IOException {
            socket = new java.net.ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"));
            thread = new Thread(() -> {
                while (!socket.isClosed()) {
                    try (java.net.Socket client = socket.accept()) {
                        java.io.BufferedInputStream in = new java.io.BufferedInputStream(client.getInputStream());
                        String requestLine = readLine(in);
                        int length = 0;
                        String line;
                        while (!(line = readLine(in)).isEmpty()) {
                            if (line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:")) {
                                length = Integer.parseInt(line.substring(15).trim());
                            }
                        }
                        for (int i = 0; i < length; i++) {
                            in.read();
                        }
                        String path = requestLine.split(" ")[1];
                        byte[] body = handler.respond(path).getBytes(StandardCharsets.UTF_8);
                        OutputStream out = client.getOutputStream();
                        out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "
                                + body.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                        out.write(body);
                        out.flush();
                    } catch (Exception e) {
                        if (!socket.isClosed()) {
                            e.printStackTrace();
                        }
                    }
                }
            }, "tiny-server");
            thread.setDaemon(true);
            thread.start();
        }

        int port() {
            return socket.getLocalPort();
        }

        private static String readLine(java.io.InputStream in) throws java.io.IOException {
            StringBuilder line = new StringBuilder();
            int c;
            while ((c = in.read()) != -1 && c != '\n') {
                if (c != '\r') {
                    line.append((char) c);
                }
            }
            return line.toString();
        }

        @Override
        public void close() {
            try {
                socket.close();
            } catch (java.io.IOException ignored) {
                // Closing anyway.
            }
        }
    }

    /** Small pulled Ready log pages built directly, without the HTTP layer. */
    private static final class SyncProtocolPages {
        static SyncStore.PullPage logPage(long rev) throws Exception {
            NotebookPaper paper = NotebookPaper.empty(PAGE_HEIGHT);
            paper.appendStroke(TestInk.stroke(10f * rev, 20f, 4), "p" + rev);
            byte[] bytes = paper.bytes();
            NotebookLogEntity log = new NotebookLogEntity();
            log.id = NotebookPaper.SCRATCHPAD_ID;
            log.notebookId = null;
            log.inkHash = InkFileStore.sha256(bytes);
            log.inkBytes = bytes.length;
            log.sliceHeight = PAGE_HEIGHT;
            log.createdAt = rev;
            log.updatedAt = rev;
            log.rev = rev;
            SyncStore.PullPage page = new SyncStore.PullPage();
            page.logs.add(log);
            page.blobs.put(log.inkHash, bytes);
            page.cursor = rev;
            return page;
        }
    }
}
