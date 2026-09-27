package com.zetteldraw.penpoc.sync;

import com.zetteldraw.penpoc.data.SyncStore;
import com.zetteldraw.penpoc.data.db.BoardEntity;
import com.zetteldraw.penpoc.data.db.NotebookEntity;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** JSON shapes for {@code POST /sync/push} and {@code GET /sync/pull}; see server/README.md. */
final class SyncProtocol {
    static final String SCHEMA_HEADER = "X-Zetteldraw-Schema";

    private SyncProtocol() {
    }

    static JSONObject pushBody(SyncStore.PushBatch batch, int schemaVersion, String deviceId) throws JSONException {
        JSONObject body = new JSONObject();
        body.put("schema_version", schemaVersion);
        body.put("device_id", deviceId);
        JSONArray notebooks = new JSONArray();
        for (NotebookEntity n : batch.notebooks) {
            notebooks.put(new JSONObject()
                    .put("id", n.id)
                    .put("title", n.title)
                    .put("position", n.position)
                    .put("created_at", n.createdAt)
                    .put("updated_at", n.updatedAt)
                    .put("deleted_at", orNull(n.deletedAt))
                    .put("base_rev", n.rev));
        }
        body.put("notebooks", notebooks);
        JSONArray boards = new JSONArray();
        for (BoardEntity b : batch.boards) {
            boards.put(boardJson(b).put("base_rev", b.rev));
        }
        body.put("boards", boards);
        JSONObject blobs = new JSONObject();
        for (Map.Entry<String, byte[]> e : batch.blobs.entrySet()) {
            blobs.put(e.getKey(), Base64.getEncoder().encodeToString(e.getValue()));
        }
        body.put("blobs", blobs);
        return body;
    }

    static List<SyncStore.PushResult> pushResults(JSONObject response) throws JSONException {
        ArrayList<SyncStore.PushResult> results = new ArrayList<>();
        JSONArray array = response.getJSONArray("results");
        for (int i = 0; i < array.length(); i++) {
            JSONObject r = array.getJSONObject(i);
            results.add(new SyncStore.PushResult(
                    r.getString("entity"), r.getString("id"), r.getString("status"), r.optLong("rev", 0L)));
        }
        return results;
    }

    static SyncStore.PullPage pullPage(JSONObject response) throws JSONException {
        SyncStore.PullPage page = new SyncStore.PullPage();
        page.cursor = response.getLong("cursor");
        page.hasMore = response.optBoolean("has_more", false);
        JSONArray notebooks = response.optJSONArray("notebooks");
        for (int i = 0; notebooks != null && i < notebooks.length(); i++) {
            JSONObject j = notebooks.getJSONObject(i);
            NotebookEntity n = new NotebookEntity();
            n.id = j.getString("id");
            n.title = j.getString("title");
            n.position = j.getString("position");
            n.createdAt = j.getLong("created_at");
            n.updatedAt = j.getLong("updated_at");
            n.rev = j.getLong("rev");
            n.deletedAt = optLong(j, "deleted_at");
            page.notebooks.add(n);
        }
        JSONArray boards = response.optJSONArray("boards");
        for (int i = 0; boards != null && i < boards.length(); i++) {
            JSONObject j = boards.getJSONObject(i);
            BoardEntity b = new BoardEntity();
            b.id = j.getString("id");
            b.notebookId = optString(j, "notebook_id");
            b.position = j.getString("position");
            b.inkHash = optString(j, "ink_hash");
            b.inkBytes = j.optLong("ink_bytes", 0L);
            b.thumbHash = optString(j, "thumb_hash");
            b.conflictOf = optString(j, "conflict_of");
            b.createdAt = j.getLong("created_at");
            b.updatedAt = j.getLong("updated_at");
            b.rev = j.getLong("rev");
            b.deletedAt = optLong(j, "deleted_at");
            page.boards.add(b);
        }
        JSONObject blobs = response.optJSONObject("blobs");
        if (blobs != null) {
            Iterator<String> keys = blobs.keys();
            while (keys.hasNext()) {
                String hash = keys.next();
                page.blobs.put(hash, Base64.getDecoder().decode(blobs.getString(hash)));
            }
        }
        return page;
    }

    private static JSONObject boardJson(BoardEntity b) throws JSONException {
        return new JSONObject()
                .put("id", b.id)
                .put("notebook_id", orNull(b.notebookId))
                .put("position", b.position)
                .put("ink_hash", orNull(b.inkHash))
                .put("ink_bytes", b.inkBytes)
                .put("thumb_hash", orNull(b.thumbHash))
                .put("conflict_of", orNull(b.conflictOf))
                .put("created_at", b.createdAt)
                .put("updated_at", b.updatedAt)
                .put("deleted_at", orNull(b.deletedAt));
    }

    private static Object orNull(Object value) {
        return value == null ? JSONObject.NULL : value;
    }

    private static String optString(JSONObject j, String key) throws JSONException {
        return j.isNull(key) ? null : j.getString(key);
    }

    private static Long optLong(JSONObject j, String key) throws JSONException {
        return j.isNull(key) ? null : j.getLong(key);
    }
}
