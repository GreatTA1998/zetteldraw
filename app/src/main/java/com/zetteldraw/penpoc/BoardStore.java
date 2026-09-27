package com.zetteldraw.penpoc;

import android.content.Context;

import com.onyx.android.sdk.data.note.TouchPoint;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Local scratchpad + notebooks. Inbox always ends with a blank board.
 */
final class BoardStore {
    static final String SCRATCHPAD = "scratchpad";
    /** JSON key kept from v4 so existing boards load as scratchpad pages. */
    private static final String SCRATCHPAD_KEY = "inbox";

    private static final String FILE_NAME = "zetteldraw-boards.json";
    private static final int VERSION = 1;

    private final File file;
    private final LinkedHashMap<String, Board> boards = new LinkedHashMap<>();
    private final ArrayList<String> scratchpad = new ArrayList<>();
    private final LinkedHashMap<String, ArrayList<String>> notebooks = new LinkedHashMap<>();

    BoardStore(Context context) {
        file = new File(context.getFilesDir(), FILE_NAME);
        for (Notebook notebook : Notebook.values()) {
            notebooks.put(notebook.id, new ArrayList<>());
        }
        load();
        ensureTrailingBlank();
        persist();
    }

    List<Board> boardsIn(String collectionId) {
        ArrayList<Board> result = new ArrayList<>();
        for (String id : idsIn(collectionId)) {
            Board board = boards.get(id);
            if (board != null) {
                result.add(board);
            }
        }
        return result;
    }

    Board board(String id) {
        return boards.get(id);
    }

    void ensureTrailingBlank() {
        while (scratchpad.size() > 1) {
            Board last = boards.get(scratchpad.get(scratchpad.size() - 1));
            Board prev = boards.get(scratchpad.get(scratchpad.size() - 2));
            if (last != null && last.isBlank() && prev != null && prev.isBlank()) {
                String removed = scratchpad.remove(scratchpad.size() - 1);
                if (!isReferenced(removed)) {
                    boards.remove(removed);
                }
            } else {
                break;
            }
        }
        Board last = scratchpad.isEmpty() ? null : boards.get(scratchpad.get(scratchpad.size() - 1));
        if (last != null && last.isBlank()) {
            return;
        }
        Board blank = Board.blank();
        boards.put(blank.id, blank);
        scratchpad.add(blank.id);
    }

    private boolean isReferenced(String boardId) {
        if (scratchpad.contains(boardId)) {
            return true;
        }
        for (ArrayList<String> ids : notebooks.values()) {
            if (ids.contains(boardId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * After the last scratchpad page gains ink, append a fresh blank page.
     */
    void onScratchpadPageFilled(String boardId) {
        if (!scratchpad.isEmpty() && boardId.equals(scratchpad.get(scratchpad.size() - 1))) {
            ensureTrailingBlank();
        }
        persist();
    }

    void save() {
        persist();
    }

    void persistBoard(Board board) {
        if (board == null) {
            return;
        }
        boards.put(board.id, board);
        persist();
    }

    /** Append the page as the newest page of {@code notebook}. */
    void moveTo(String boardId, Notebook notebook) {
        if (boardId == null || notebook == null || !boards.containsKey(boardId)) {
            return;
        }
        removeFromAll(boardId);
        notebooks.get(notebook.id).add(boardId);
        ensureTrailingBlank();
        persist();
    }

    void wipe(String boardId) {
        Board board = boards.get(boardId);
        if (board == null) {
            return;
        }
        board.strokes.clear();
        ensureTrailingBlank();
        persist();
    }

    private List<String> idsIn(String collectionId) {
        if (SCRATCHPAD.equals(collectionId)) {
            return scratchpad;
        }
        ArrayList<String> ids = notebooks.get(collectionId);
        return ids == null ? new ArrayList<>() : ids;
    }

    private void removeFromAll(String boardId) {
        scratchpad.remove(boardId);
        for (ArrayList<String> ids : notebooks.values()) {
            ids.remove(boardId);
        }
    }

    private void load() {
        if (!file.exists()) {
            return;
        }
        try {
            String raw = readFile();
            if (raw == null || raw.trim().isEmpty()) {
                return;
            }
            JSONObject root = new JSONObject(raw);
            JSONObject boardsJson = root.optJSONObject("boards");
            if (boardsJson != null) {
                JSONArray names = boardsJson.names();
                if (names != null) {
                    for (int i = 0; i < names.length(); i++) {
                        String id = names.getString(i);
                        Board board = boardFromJson(boardsJson.getJSONObject(id));
                        boards.put(board.id, board);
                    }
                }
            }
            fillIds(root.optJSONArray(SCRATCHPAD_KEY), scratchpad);
            for (Notebook notebook : Notebook.values()) {
                ArrayList<String> ids = notebooks.get(notebook.id);
                ids.clear();
                fillIds(root.optJSONArray(notebook.id), ids);
            }
            JSONObject notebooksJson = root.optJSONObject("notebooks");
            if (notebooksJson != null) {
                for (Notebook notebook : Notebook.values()) {
                    ArrayList<String> ids = notebooks.get(notebook.id);
                    if (notebooksJson.has(notebook.id)) {
                        ids.clear();
                        fillIds(notebooksJson.optJSONArray(notebook.id), ids);
                    }
                }
            }
        } catch (JSONException | IOException ignored) {
            boards.clear();
            scratchpad.clear();
            for (ArrayList<String> ids : notebooks.values()) {
                ids.clear();
            }
        }
    }

    private void persist() {
        try {
            JSONObject root = new JSONObject();
            root.put("version", VERSION);
            root.put(SCRATCHPAD_KEY, toIdArray(scratchpad));
            JSONObject notebooksJson = new JSONObject();
            for (Map.Entry<String, ArrayList<String>> entry : notebooks.entrySet()) {
                notebooksJson.put(entry.getKey(), toIdArray(entry.getValue()));
            }
            root.put("notebooks", notebooksJson);
            JSONObject boardsJson = new JSONObject();
            for (Board board : boards.values()) {
                boardsJson.put(board.id, boardToJson(board));
            }
            root.put("boards", boardsJson);
            writeFile(root.toString());
        } catch (JSONException | IOException ignored) {
        }
    }

    private void fillIds(JSONArray array, List<String> dest) {
        dest.clear();
        if (array == null) {
            return;
        }
        for (int i = 0; i < array.length(); i++) {
            String id = array.optString(i, null);
            if (id != null && !id.isEmpty() && boards.containsKey(id) && !dest.contains(id)) {
                dest.add(id);
            }
        }
    }

    private static JSONArray toIdArray(List<String> ids) {
        JSONArray array = new JSONArray();
        for (String id : ids) {
            array.put(id);
        }
        return array;
    }

    private static JSONObject boardToJson(Board board) throws JSONException {
        JSONObject json = new JSONObject();
        json.put("id", board.id);
        json.put("createdAt", board.createdAt);
        JSONArray strokes = new JSONArray();
        for (InkRenderer.InkStroke stroke : board.strokes) {
            JSONObject strokeJson = new JSONObject();
            JSONArray points = new JSONArray();
            for (TouchPoint point : stroke.points) {
                JSONObject p = new JSONObject();
                p.put("x", point.x);
                p.put("y", point.y);
                p.put("pressure", point.pressure);
                p.put("size", point.size);
                p.put("timestamp", point.timestamp);
                p.put("tiltX", point.tiltX);
                p.put("tiltY", point.tiltY);
                points.put(p);
            }
            strokeJson.put("points", points);
            strokes.put(strokeJson);
        }
        json.put("strokes", strokes);
        return json;
    }

    private static Board boardFromJson(JSONObject json) throws JSONException {
        Board board = new Board(json.getString("id"), json.optLong("createdAt", 0L));
        JSONArray strokes = json.optJSONArray("strokes");
        if (strokes == null) {
            return board;
        }
        for (int i = 0; i < strokes.length(); i++) {
            JSONObject strokeJson = strokes.getJSONObject(i);
            JSONArray pointsJson = strokeJson.optJSONArray("points");
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

    private String readFile() throws IOException {
        FileInputStream in = new FileInputStream(file);
        try {
            byte[] buf = new byte[(int) file.length()];
            int read = 0;
            while (read < buf.length) {
                int n = in.read(buf, read, buf.length - read);
                if (n < 0) {
                    break;
                }
                read += n;
            }
            return new String(buf, 0, read, StandardCharsets.UTF_8);
        } finally {
            in.close();
        }
    }

    private void writeFile(String text) throws IOException {
        File tmp = new File(file.getAbsolutePath() + ".tmp");
        FileOutputStream out = new FileOutputStream(tmp);
        try {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            out.write(bytes);
            out.flush();
        } finally {
            out.close();
        }
        if (!tmp.renameTo(file)) {
            FileOutputStream direct = new FileOutputStream(file);
            try {
                direct.write(text.getBytes(StandardCharsets.UTF_8));
            } finally {
                direct.close();
            }
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }
}
