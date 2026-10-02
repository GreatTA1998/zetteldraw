package com.zetteldraw.penpoc.data;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * App-private ink files: {@code <dir>/<boardId>.zdi}, written atomically.
 * Writing is split so the slow half can run without the repository lock:
 * {@link #stage} writes and fsyncs a temp file, {@link #commit} renames it
 * into place (a metadata-only step).
 */
public class InkFileStore {
    private final File dir;

    public InkFileStore(File dir) {
        this.dir = dir;
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
    }

    public File fileFor(String boardId) {
        return new File(dir, boardId + ".zdi");
    }

    public void write(String boardId, byte[] bytes) throws IOException {
        commit(stage(boardId, "zdi", bytes), boardId);
    }

    /** Writes and fsyncs {@code <boardId>.<tag>.tmp}; nothing is visible until {@link #commit}. */
    public File stage(String boardId, String tag, byte[] bytes) throws IOException {
        File tmp = new File(dir, boardId + "." + tag + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(bytes);
            out.getFD().sync();
        }
        return tmp;
    }

    public void commit(File staged, String boardId) throws IOException {
        File target = fileFor(boardId);
        if (!staged.renameTo(target)) {
            throw new IOException("rename failed for " + target);
        }
    }

    public void discard(File staged) {
        if (staged != null) {
            //noinspection ResultOfMethodCallIgnored
            staged.delete();
        }
    }

    /** Null when the board has no ink file. */
    public byte[] read(String boardId) throws IOException {
        File file = fileFor(boardId);
        if (!file.exists()) {
            return null;
        }
        byte[] buf = new byte[(int) file.length()];
        try (FileInputStream in = new FileInputStream(file)) {
            int read = 0;
            while (read < buf.length) {
                int n = in.read(buf, read, buf.length - read);
                if (n < 0) {
                    throw new IOException("short read " + file);
                }
                read += n;
            }
        }
        return buf;
    }

    /** Writes {@code bytes} to a side file the app never reads or deletes; returns its name, or null. */
    public String preserve(String boardId, byte[] bytes) {
        File copy = new File(dir, boardId + ".unreadable-" + System.currentTimeMillis());
        try (FileOutputStream out = new FileOutputStream(copy)) {
            out.write(bytes);
            out.getFD().sync();
            return copy.getName();
        } catch (IOException e) {
            return null;
        }
    }

    public void delete(String boardId) {
        //noinspection ResultOfMethodCallIgnored
        fileFor(boardId).delete();
    }

    public static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xf, 16));
                hex.append(Character.forDigit(b & 0xf, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
