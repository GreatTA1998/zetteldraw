package com.zetteldraw.penpoc.data;

import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Plain-file mirror: {@code <root>/index.json} and {@code <root>/ink/<id>.zdi}.
 * Used below Android 10 (public Documents via WRITE_EXTERNAL_STORAGE) and in tests.
 */
public final class DirectoryMirror implements InkMirror {
    private static final String TAG = "zd-mirror";
    private final File root;
    private final File inkDir;

    public DirectoryMirror(File root) {
        this.root = root;
        this.inkDir = new File(root, "ink");
    }

    @Override
    public void writeInk(String boardId, byte[] bytes) {
        write(new File(inkDir, boardId + ".zdi"), bytes);
    }

    @Override
    public void deleteInk(String boardId) {
        //noinspection ResultOfMethodCallIgnored
        new File(inkDir, boardId + ".zdi").delete();
    }

    @Override
    public void writeIndex(String json) {
        write(new File(root, "index.json"), json.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void writeLog(String sheetId, byte[] bytes) {
        write(new File(inkDir, sheetId + ".zdl"), bytes);
    }

    @Override
    public void deleteLog(String sheetId) {
        //noinspection ResultOfMethodCallIgnored
        new File(inkDir, sheetId + ".zdl").delete();
    }

    private static void write(File file, byte[] bytes) {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            Log.w(TAG, "cannot create " + parent);
            return;
        }
        File tmp = new File(file.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(bytes);
        } catch (IOException e) {
            Log.w(TAG, "mirror write failed " + file, e);
            return;
        }
        if (!tmp.renameTo(file)) {
            Log.w(TAG, "mirror rename failed " + file);
        }
    }
}
