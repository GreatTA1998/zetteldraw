package com.zetteldraw.penpoc.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.zetteldraw.penpoc.data.SyncStore;
import com.zetteldraw.penpoc.data.db.NotebookEntity;

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
}
