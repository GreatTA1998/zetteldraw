package com.zetteldraw.penpoc.data;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import androidx.annotation.RequiresApi;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Android 10+ mirror into public {@code Documents/zetteldraw/} through
 * MediaStore, which needs no storage permission for files this app created.
 * After a reinstall the old files stay on disk but belong to the previous
 * install, so restoring them means picking them via the system file picker.
 */
@RequiresApi(Build.VERSION_CODES.Q)
public final class DocumentsMirror implements InkMirror {
    private static final String TAG = "zd-mirror";
    static final String ROOT = Environment.DIRECTORY_DOCUMENTS + "/zetteldraw/";
    static final String INK = ROOT + "ink/";

    private final ContentResolver resolver;
    private final Uri collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);

    public DocumentsMirror(ContentResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public void writeInk(String boardId, byte[] bytes) {
        write(INK, boardId + ".zdi", "application/octet-stream", bytes);
    }

    @Override
    public void deleteInk(String boardId) {
        try {
            Uri uri = find(INK, boardId + ".zdi");
            if (uri != null) {
                resolver.delete(uri, null, null);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "mirror delete failed " + boardId, e);
        }
    }

    @Override
    public void writeIndex(String json) {
        write(ROOT, "index.json", "application/json", json.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void writeLog(String sheetId, byte[] bytes) {
        write(INK, sheetId + ".zdl", "application/octet-stream", bytes);
    }

    @Override
    public void deleteLog(String sheetId) {
        try {
            Uri uri = find(INK, sheetId + ".zdl");
            if (uri != null) {
                resolver.delete(uri, null, null);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "mirror log delete failed " + sheetId, e);
        }
    }

    private void write(String relativePath, String name, String mime, byte[] bytes) {
        try {
            Uri uri = find(relativePath, name);
            if (uri == null) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath);
                uri = resolver.insert(collection, values);
            }
            if (uri == null) {
                Log.w(TAG, "mirror insert failed " + relativePath + name);
                return;
            }
            try (OutputStream out = resolver.openOutputStream(uri, "wt")) {
                if (out != null) {
                    out.write(bytes);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "mirror write failed " + relativePath + name, e);
        }
    }

    private Uri find(String relativePath, String name) {
        String[] projection = {MediaStore.MediaColumns._ID};
        String selection = MediaStore.MediaColumns.RELATIVE_PATH + "=? AND "
                + MediaStore.MediaColumns.DISPLAY_NAME + "=?";
        try (Cursor cursor = resolver.query(collection, projection, selection,
                new String[]{relativePath, name}, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                return Uri.withAppendedPath(collection, String.valueOf(cursor.getLong(0)));
            }
        }
        return null;
    }
}
