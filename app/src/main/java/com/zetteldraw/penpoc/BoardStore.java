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
 * Local inbox + notebooks. Inbox always ends with a blank board.
 */
final class BoardStore {
    static final String INBOX = "inbox";

    private static final String FILE_NAME = "zetteldraw-boards.json";
    private static final int VERSION = 1;

    private final File file;
    private final LinkedHashMap<String, Board> boards = new LinkedHashMap<>();
    private final ArrayList<String> inbox = new ArrayList<>();
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

    Board currentOrBlank(String collectionId, String preferredId) {
        List<Board> list = boardsIn(collectionId);
        if (list.isEmpty()) {
            return null;
        }
        if (preferredId != null) {
            for (Board board : list) {
                if (board.id.equals(preferredId)) {
                    return board;
                }
            }
        }
        return list.get(0);
    }

    void ensureTrailingBlank() {
        while (inbox.size() > 1) {
            Board last = boards.get(inbox.get(inbox.size() - 1));
            Board prev = boards.get(inbox.get(inbox.size() - 2));
            if (last != null && last.isBlank() && prev != null && prev.isBlank()) {
                String removed = inbox.remove(inbox.size() - 1);
                if (!isReferenced(removed)) {
                    boards.remove(removed);
                }
            } else {
                break;
            }
        }
        Board last = inbox.isEmpty() ? null : boards.get(inbox.get(inbox.size() - 1));
        if (last != null && last.isBlank()) {
            return;
        }
        Board blank = Board.blank();
        boards.put(blank.id, blank);
        inbox.add(blank.id);
    }

    private boolean isReferenced(String boardId) {
        if (inbox.contains(boardId)) {
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
     * After the last inbox board gains ink, append a fresh blank page.
     */
    void onInboxBoardFilled(String boardId) {
        if (inbox.isEmpty() || !boardId.equals(inbox.get(inbox.size() - 1))) {
            persist();
            return;
        }
        Board last = boards.get(boardId);
        if (last == null || last.isBlank()) {
            persist();
            return;
        }
        ensureTrailingBlank();
        persist();
    }

    void persistBoard(Board board) {
        if (board == null) {
            return;
        }
        boards.put(board.id, board);
        persist();
    }

    /**
     * Two-tap file: move the current board into a notebook.
     * Returns the board that should become current in the source collection.
     */
    Board fileTo(String boardId, Notebook notebook, String sourceCollection) {
        if (boardId == null || notebook == null) {
            return currentOrBlank(sourceCollection, null);
        }
        Board board = boards.get(boardId);
        if (board == null || board.isBlank()) {
            return currentOrBlank(sourceCollection, boardId);
        }
        removeFromAll(boardId);
        notebooks.get(notebook.id).add(boardId);
        if (INBOX.equals(sourceCollection)) {
            ensureTrailingBlank();
        }
        persist();
        List<Board> remaining = boardsIn(sourceCollection);
        if (remaining.isEmpty()) {
            return null;
        }
        return remaining.get(0);
    }

    boolean canFile(String boardId) {
        Board board = boards.get(boardId);
        return board != null && !board.isBlank();
    }

    private List<String> idsIn(String collectionId) {
        if (INBOX.equals(collectionId)) {
            return inbox;
        }
        ArrayList<String> ids = notebooks.get(collectionId);
        return ids == null ? new ArrayList<>() : ids;
    }

    private void removeFromAll(String boardId) {
        inbox.remove(boardId);
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
            fillIds(root.optJSONArray(INBOX), inbox);
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
            inbox.clear();
            for (ArrayList<String> ids : notebooks.values()) {
                ids.clear();
            }
        }
    }

    private void persist() {
        try {
            JSONObject root = new JSONObject();
            root.put("version", VERSION);
            root.put(INBOX, toIdArray(inbox));
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
