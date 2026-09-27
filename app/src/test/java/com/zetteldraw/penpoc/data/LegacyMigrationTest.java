package com.zetteldraw.penpoc.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;

import com.onyx.android.sdk.data.note.TouchPoint;
import com.zetteldraw.penpoc.Board;
import com.zetteldraw.penpoc.Notebook;

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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;

/** v4/v5 BoardStore JSON → Room on first launch, without losing a board. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = Application.class)
public class LegacyMigrationTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File filesDir;
    private Device device;

    private final String s1 = UUID.randomUUID().toString();
    private final String s2 = UUID.randomUUID().toString();
    private final String trailingBlank = UUID.randomUUID().toString();
    private final String c1 = UUID.randomUUID().toString();
    private final String c2 = UUID.randomUUID().toString();
    private final String j1Wiped = UUID.randomUUID().toString();
    private final String orphan = UUID.randomUUID().toString();

    @Before
    public void setUp() throws Exception {
        filesDir = tmp.newFolder("files");
        device = new Device("a", tmp.newFolder("device"), 5_000_000L);
    }

    @After
    public void tearDown() {
        device.close();
    }

    @Test
    public void importsEveryBoardInOrderAndKeepsTheOldFile() throws Exception {
        writeLegacyJson();

        int imported = LegacyBoardImporter.importIfPresent(filesDir, device.repo);
        // s1, s2, orphan (scratchpad) + c1, c2, j1Wiped (notebooks); the trailing blank is dropped.
        assertEquals(6, imported);

        List<Board> scratch = device.repo.scratchpadPages();
        assertEquals(s1, scratch.get(0).id);
        assertEquals(s2, scratch.get(1).id);
        assertEquals("unreferenced boards with ink are not lost", orphan, scratch.get(2).id);
        assertEquals(4, scratch.size());
        assertTrue(scratch.get(3).isBlank());

        List<Board> comedy = device.repo.notebookPages(Notebook.COMEDY.uuid);
        assertEquals(List.of(c1, c2), RoomBoardRepositoryTest.ids(comedy).subList(0, 2));
        assertEquals(3, comedy.size());
        assertTrue(comedy.get(2).isBlank());
        assertEquals(1, device.repo.notebookPages(Notebook.JOURNAL.uuid).size());
        assertTrue(device.repo.notebookPages(Notebook.JOURNAL.uuid).get(0).isBlank());

        assertPoints(scratch.get(0), 3);
        assertPoints(comedy.get(1), 5);
        assertEquals(123L, scratch.get(0).createdAt);

        assertFalse(new File(filesDir, LegacyBoardImporter.FILE_NAME).exists());
        assertTrue(new File(filesDir, LegacyBoardImporter.FILE_NAME + LegacyBoardImporter.MIGRATED_SUFFIX).exists());
        assertEquals("every imported board is queued for the first push",
                4 + 6, device.db.dao().outboxCount());

        device.reopen();
        assertEquals(-1, LegacyBoardImporter.importIfPresent(filesDir, device.repo));
        assertEquals(s1, device.repo.scratchpadPages().get(0).id);
        assertPoints(device.repo.scratchpadPages().get(0), 3);
    }

    @Test
    public void importingTwiceDoesNotDuplicate() throws Exception {
        writeLegacyJson();
        LegacyBoardImporter.importIfPresent(filesDir, device.repo);
        writeLegacyJson();
        assertEquals(0, LegacyBoardImporter.importIfPresent(filesDir, device.repo));
        assertEquals(4, device.repo.scratchpadPages().size());
        assertEquals(3, device.repo.notebookPages(Notebook.COMEDY.uuid).size());
    }

    @Test
    public void unreadableJsonIsKeptAsideNotDeleted() throws Exception {
        Files.write(new File(filesDir, LegacyBoardImporter.FILE_NAME).toPath(), "{not json".getBytes());
        assertEquals(0, LegacyBoardImporter.importIfPresent(filesDir, device.repo));
        assertTrue(new File(filesDir, LegacyBoardImporter.FILE_NAME + ".unreadable").exists());
    }

    private void assertPoints(Board board, int points) {
        assertEquals(1, board.strokes.size());
        List<TouchPoint> p = board.strokes.get(0).points;
        assertEquals(points, p.size());
        assertEquals(10f, p.get(0).x, 0f);
        assertEquals(0.5f, p.get(0).pressure, 1e-6f);
        assertEquals(1_000L, p.get(0).timestamp);
    }

    private void writeLegacyJson() throws Exception {
        JSONObject boards = new JSONObject();
        boards.put(s1, board(s1, 123L, 3));
        boards.put(s2, board(s2, 124L, 4));
        boards.put(trailingBlank, board(trailingBlank, 125L, 0));
        boards.put(c1, board(c1, 100L, 2));
        boards.put(c2, board(c2, 101L, 5));
        boards.put(j1Wiped, board(j1Wiped, 102L, 0));
        boards.put(orphan, board(orphan, 99L, 2));
        JSONObject notebooks = new JSONObject();
        notebooks.put("comedy", new JSONArray().put(c1).put(c2));
        notebooks.put("journal", new JSONArray().put(j1Wiped));
        notebooks.put("actions.life", new JSONArray());
        notebooks.put("miscellaneous", new JSONArray().put("missing-board-id"));
        JSONObject root = new JSONObject()
                .put("version", 1)
                .put("inbox", new JSONArray().put(s1).put(s2).put(trailingBlank))
                .put("notebooks", notebooks)
                .put("boards", boards);
        Files.write(new File(filesDir, LegacyBoardImporter.FILE_NAME).toPath(),
                root.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static JSONObject board(String id, long createdAt, int points) throws Exception {
        JSONArray strokes = new JSONArray();
        if (points > 0) {
            JSONArray pts = new JSONArray();
            for (int i = 0; i < points; i++) {
                pts.put(new JSONObject()
                        .put("x", 10 + i).put("y", 20 + i).put("pressure", 0.5)
                        .put("size", 1).put("timestamp", 1_000 + i).put("tiltX", 0).put("tiltY", 0));
            }
            strokes.put(new JSONObject().put("points", pts));
        }
        return new JSONObject().put("id", id).put("createdAt", createdAt).put("strokes", strokes);
    }
}
