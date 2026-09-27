package com.zetteldraw.penpoc.data;

import com.onyx.android.sdk.data.note.TouchPoint;
import com.zetteldraw.penpoc.Board;
import com.zetteldraw.penpoc.InkRenderer;
import com.zetteldraw.penpoc.Notebook;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the v4/v5 {@code zetteldraw-boards.json} (BoardStore) so its boards
 * can be moved into Room on first launch. Board ids are kept, which makes a
 * repeated import harmless.
 */
public final class LegacyBoardImporter {
    public static final String FILE_NAME = "zetteldraw-boards.json";
    public static final String MIGRATED_SUFFIX = ".migrated";
    /** v4 called the scratchpad "inbox"; v5 kept the key. */
    private static final String SCRATCHPAD_KEY = "inbox";

    private LegacyBoardImporter() {
    }

    public static final class Legacy {
        public final List<Board> scratchpad = new ArrayList<>();
        public final Map<Notebook, List<Board>> notebooks = new LinkedHashMap<>();
        /** Boards with ink that no list referenced; imported at the end of the scratchpad. */
        public final List<Board> orphans = new ArrayList<>();
    }

    /**
     * Imports {@code filesDir/zetteldraw-boards.json} if present, then renames
     * it to {@code .migrated} (kept as a fallback, never deleted).
     * Returns the number of boards imported, or -1 when there was nothing to do.
     */
    public static int importIfPresent(File filesDir, RoomBoardRepository repository) throws IOException {
        File file = new File(filesDir, FILE_NAME);
        if (!file.exists()) {
            return -1;
        }
        Legacy legacy;
        try {
            legacy = parse(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
        } catch (JSONException e) {
            File bad = new File(filesDir, FILE_NAME + ".unreadable");
            //noinspection ResultOfMethodCallIgnored
            file.renameTo(bad);
            return 0;
        }
        int count = repository.importLegacy(legacy);
        File done = new File(filesDir, FILE_NAME + MIGRATED_SUFFIX);
        if (!file.renameTo(done)) {
            throw new IOException("could not rename " + file);
        }
        return count;
    }

    public static Legacy parse(String raw) throws JSONException {
        Legacy legacy = new Legacy();
        if (raw == null || raw.trim().isEmpty()) {
            return legacy;
        }
        JSONObject root = new JSONObject(raw);
        LinkedHashMap<String, Board> boards = new LinkedHashMap<>();
        JSONObject boardsJson = root.optJSONObject("boards");
        if (boardsJson != null) {
            JSONArray names = boardsJson.names();
            if (names != null) {
                for (int i = 0; i < names.length(); i++) {
                    Board board = boardFromJson(boardsJson.getJSONObject(names.getString(i)));
                    boards.put(board.id, board);
                }
            }
        }
        HashSet<String> used = new HashSet<>();
        fill(root.optJSONArray(SCRATCHPAD_KEY), boards, used, legacy.scratchpad);
        JSONObject notebooksJson = root.optJSONObject("notebooks");
        for (Notebook notebook : Notebook.values()) {
            JSONArray ids = notebooksJson != null && notebooksJson.has(notebook.id)
                    ? notebooksJson.optJSONArray(notebook.id)
                    : root.optJSONArray(notebook.id);
            ArrayList<Board> list = new ArrayList<>();
            fill(ids, boards, used, list);
            legacy.notebooks.put(notebook, list);
        }
        for (Board board : boards.values()) {
            if (!used.contains(board.id) && !board.isBlank()) {
                legacy.orphans.add(board);
            }
        }
        return legacy;
    }

    private static void fill(JSONArray ids, Map<String, Board> boards, HashSet<String> used, List<Board> dest) {
        if (ids == null) {
            return;
        }
        for (int i = 0; i < ids.length(); i++) {
            String id = ids.optString(i, null);
            Board board = id == null ? null : boards.get(id);
            if (board != null && used.add(id)) {
                dest.add(board);
            }
        }
    }

    private static Board boardFromJson(JSONObject json) throws JSONException {
        Board board = new Board(json.getString("id"), json.optLong("createdAt", 0L));
        JSONArray strokes = json.optJSONArray("strokes");
        if (strokes == null) {
            return board;
        }
        for (int i = 0; i < strokes.length(); i++) {
            JSONArray pointsJson = strokes.getJSONObject(i).optJSONArray("points");
            if (pointsJson == null || pointsJson.length() == 0) {
                continue;
            }
            ArrayList<TouchPoint> points = new ArrayList<>(pointsJson.length());
            for (int j = 0; j < pointsJson.length(); j++) {
                JSONObject p = pointsJson.getJSONObject(j);
                points.add(new TouchPoint(
                        (float) p.optDouble("x"),
                        (float) p.optDouble("y"),
                        (float) p.optDouble("pressure"),
                        (float) p.optDouble("size"),
                        p.optInt("tiltX"),
                        p.optInt("tiltY"),
                        p.optLong("timestamp")));
            }
            board.strokes.add(InkRenderer.strokeFrom(points));
        }
        return board;
    }
}
