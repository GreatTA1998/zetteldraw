package com.zetteldraw.penpoc.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;

import com.zetteldraw.penpoc.Board;
import com.zetteldraw.penpoc.InkRenderer;
import com.zetteldraw.penpoc.data.db.BoardEntity;
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
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A sync pass that pulls many pages used to queue one full page-list reload
 * per pulled page onto the main thread. It must reach the UI as one refresh.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class)
public class SyncRefreshStormTest {
    private static final int PAGES = 30;

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
            return pullPage(since).toString();
        });
        SyncConfig config = new SyncConfig("http://127.0.0.1:" + server.port(), "t", "d");
        SyncEngine.Result result = new SyncEngine(repo, new SyncClient(config, ZettelDatabase.SCHEMA_VERSION), config,
                ZettelDatabase.SCHEMA_VERSION, System::currentTimeMillis).run();

        assertEquals(PAGES, result.pulledBoards);
        assertEquals("one UI turn for the whole pass, not one per pulled page", 1, uiQueue.size());
        runUi();
        assertEquals(1, refreshes.get());
        assertEquals(PAGES + 1, repo.scratchpadPages().size());
    }

    @Test
    public void pullsOutsideABatchCoalesceWhileTheUiIsBusy() throws Exception {
        for (long rev = 1; rev <= 10; rev++) {
            repo.applyPull(SyncProtocolPages.page(rev));
        }
        assertEquals("one pending UI turn however many pulls land meanwhile", 1, uiQueue.size());
        runUi();
        assertEquals(1, refreshes.get());
        repo.applyPull(SyncProtocolPages.page(11));
        assertEquals(1, uiQueue.size());
        runUi();
        assertEquals(2, refreshes.get());
    }

    @Test
    public void pulledStrokesReachTheSharedBoardWithoutTheLock() throws Exception {
        Board page = repo.scratchpadPages().get(0);
        TestInk.draw(page, 10f);
        repo.saveInk(page);
        BoardEntity remote = db.dao().board(page.id).copy();
        List<InkRenderer.InkStroke> ink = Collections.singletonList(TestInk.stroke(300f, 300f, 5));
        byte[] bytes = InkCodec.encode(ink);
        remote.inkHash = InkFileStore.sha256(bytes);
        remote.inkBytes = bytes.length;
        remote.rev = 9;
        remote.updatedAt = System.currentTimeMillis() + 60_000;
        SyncStore.PullPage pulled = new SyncStore.PullPage();
        pulled.boards.add(remote);
        pulled.blobs.put(remote.inkHash, bytes);
        pulled.cursor = 9;
        repo.applyPull(pulled);
        Object lock = lockOf(repo);
        Thread holder = new Thread(() -> {
            synchronized (lock) {
                try {
                    Thread.sleep(3_000);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        holder.start();
        Thread.sleep(100);
        long started = System.nanoTime();
        runUi();
        assertTrue("delivery does not wait for the repository lock",
                (System.nanoTime() - started) / 1_000_000 < 1_000);
        holder.interrupt();
        holder.join();
        TestInk.assertSameInk(ink, page.strokes);
    }

    private void runUi() {
        ArrayList<Runnable> batch = new ArrayList<>(uiQueue);
        uiQueue.clear();
        for (Runnable r : batch) {
            r.run();
        }
    }

    private static Object lockOf(RoomBoardRepository repo) throws Exception {
        java.lang.reflect.Field field = RoomBoardRepository.class.getDeclaredField("lock");
        field.setAccessible(true);
        return field.get(repo);
    }

    private static JSONObject pullPage(long since) throws Exception {
        long rev = since + 1;
        JSONObject response = new JSONObject();
        response.put("schema_version", ZettelDatabase.SCHEMA_VERSION);
        response.put("cursor", rev);
        response.put("has_more", rev < PAGES);
        response.put("notebooks", new JSONArray());
        byte[] bytes = InkCodec.encode(Collections.singletonList(TestInk.stroke(10f * rev, 20f, 4)));
        String hash = InkFileStore.sha256(bytes);
        response.put("boards", new JSONArray().put(new JSONObject()
                .put("id", UUID.nameUUIDFromBytes(("b" + rev).getBytes(StandardCharsets.UTF_8)).toString())
                .put("notebook_id", JSONObject.NULL)
                .put("position", String.format("a%03d", rev))
                .put("ink_hash", hash)
                .put("ink_bytes", bytes.length)
                .put("thumb_hash", JSONObject.NULL)
                .put("conflict_of", JSONObject.NULL)
                .put("created_at", rev)
                .put("updated_at", rev)
                .put("rev", rev)
                .put("deleted_at", JSONObject.NULL)));
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

    /** Small pulled pages built directly, without the HTTP layer. */
    private static final class SyncProtocolPages {
        static SyncStore.PullPage page(long rev) {
            SyncStore.PullPage page = new SyncStore.PullPage();
            BoardEntity board = new BoardEntity();
            board.id = UUID.nameUUIDFromBytes(("p" + rev).getBytes(StandardCharsets.UTF_8)).toString();
            board.position = String.format("b%03d", rev);
            board.createdAt = rev;
            board.updatedAt = rev;
            board.rev = rev;
            page.boards.add(board);
            page.cursor = rev;
            return page;
        }
    }
}
