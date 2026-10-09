package com.zetteldraw.penpoc.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.zetteldraw.penpoc.data.SyncStore;
import com.zetteldraw.penpoc.data.db.NotebookEntity;
import com.zetteldraw.penpoc.data.db.PageLinkEntity;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, application = android.app.Application.class)
public class SyncProtocolTest {
    @Test
    public void notebookParentRoundTrips() throws Exception {
        NotebookEntity notebook = new NotebookEntity();
        notebook.id = "aaaaaaaa-1111-4111-8111-111111111111";
        notebook.title = "chapter";
        notebook.position = "a0";
        notebook.parentId = "bbbbbbbb-2222-4222-8222-222222222222";
        notebook.createdAt = 1L;
        notebook.updatedAt = 2L;

        SyncStore.PushBatch batch = new SyncStore.PushBatch();
        batch.notebooks.add(notebook);
        JSONObject body = SyncProtocol.pushBody(batch, 3, "device-a");
        JSONObject pushed = body.getJSONArray("notebooks").getJSONObject(0);
        assertEquals(notebook.parentId, pushed.getString("parent_id"));

        JSONObject row = new JSONObject(pushed.toString());
        row.put("rev", 4);
        row.remove("base_rev");
        JSONObject response = new JSONObject();
        response.put("cursor", 4);
        response.put("notebooks", new JSONArray().put(row));
        SyncStore.PullPage page = SyncProtocol.pullPage(response);
        assertEquals(notebook.parentId, page.notebooks.get(0).parentId);

        row.remove("parent_id");
        response.put("notebooks", new JSONArray().put(row));
        assertNull(SyncProtocol.pullPage(response).notebooks.get(0).parentId);
    }

    @Test
    public void pageLinkRoundTrips() throws Exception {
        PageLinkEntity link = new PageLinkEntity();
        link.id = "cccccccc-3333-4333-8333-333333333333";
        link.sourceId = "aaaaaaaa-1111-4111-8111-111111111111";
        link.targetId = "bbbbbbbb-2222-4222-8222-222222222222";
        link.createdAt = 3L;
        link.updatedAt = 4L;

        SyncStore.PushBatch batch = new SyncStore.PushBatch();
        batch.links.add(link);
        JSONObject body = SyncProtocol.pushBody(batch, 4, "device-a");
        JSONObject pushed = body.getJSONArray("links").getJSONObject(0);
        assertEquals(link.sourceId, pushed.getString("source_id"));
        assertEquals(link.targetId, pushed.getString("target_id"));
        assertEquals(0L, pushed.getLong("base_rev"));

        JSONObject row = new JSONObject(pushed.toString());
        row.put("rev", 6);
        row.remove("base_rev");
        JSONObject response = new JSONObject();
        response.put("cursor", 6);
        response.put("links", new JSONArray().put(row));
        PageLinkEntity pulled = SyncProtocol.pullPage(response).links.get(0);
        assertEquals(link.sourceId, pulled.sourceId);
        assertEquals(link.targetId, pulled.targetId);
        assertEquals(6L, pulled.rev);
    }
}
