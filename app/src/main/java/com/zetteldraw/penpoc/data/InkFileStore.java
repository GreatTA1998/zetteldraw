package com.zetteldraw.penpoc.data;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * App-private ink files. Ready SoT is {@code <dir>/<sheetId>.zdl}.
 * {@code .zdi} page-file helpers remain only to retire leftover residue after
 * Ready install. Writing is split so the slow half can run without the
 * repository lock: {@link #stage} + {@link #commit}.
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

    /** Notebook ink log ({@code .zdl}) — Ready SoT. */
    public File logFile(String sheetId) {
        return new File(dir, sheetId + ".zdl");
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
        commitTo(staged, fileFor(boardId));
    }

    public void commitTo(File staged, File target) throws IOException {
        if (!staged.renameTo(target)) {
            throw new IOException("rename failed for " + target);
        }
    }

    /** Appends to the log. Does not rewrite the bytes already in the file. */
    public void appendLog(String sheetId, byte[] suffix) throws IOException {
        if (suffix == null || suffix.length == 0) {
            return;
        }
        try (FileOutputStream out = new FileOutputStream(logFile(sheetId), true)) {
            out.write(suffix);
            out.getFD().sync();
        }
    }

    public void writeLog(String sheetId, byte[] bytes) throws IOException {
        commitTo(stage(sheetId, "zdl", bytes), logFile(sheetId));
    }

    /** True when a notebook ink log file exists (may be empty only if absent). */
    public boolean hasLog(String sheetId) {
        return logFile(sheetId).isFile();
    }

    public byte[] readLog(String sheetId) throws IOException {
        return readFile(logFile(sheetId));
    }

    /**
     * Moves a bad {@code .zdl} aside so open can heal without deleting ink.
     * Returns the quarantine path, or null when there was nothing to move.
     */
    public File quarantineLog(String sheetId) {
        File log = logFile(sheetId);
        if (!log.isFile()) {
            return null;
        }
        File dest = new File(dir, sheetId + ".zdl.corrupt." + System.currentTimeMillis());
        if (!log.renameTo(dest)) {
            return null;
        }
        return dest;
    }

    public void discard(File staged) {
        if (staged != null) {
            //noinspection ResultOfMethodCallIgnored
            staged.delete();
        }
    }

    /** Null when the board has no ink file. */
    public byte[] read(String boardId) throws IOException {
        return readFile(fileFor(boardId));
    }

    /** Null when missing; one read spine for {@code .zdi} and {@code .zdl}. */
    private static byte[] readFile(File file) throws IOException {
        if (!file.exists()) {
            return null;
        }
        long length = file.length();
        if (length < 0 || length > Integer.MAX_VALUE) {
            throw new IOException("ink file too large " + file);
        }
        byte[] buf = new byte[(int) length];
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

    public void delete(String boardId) {
        //noinspection ResultOfMethodCallIgnored
        fileFor(boardId).delete();
    }

    public static String sha256(byte[] bytes) {
        try {
            return toHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Stream a file through SHA-256 without loading it into a byte[]. */
    public static String sha256File(File file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[64 * 1024];
            try (InputStream in = new DigestInputStream(new FileInputStream(file), digest)) {
                while (in.read(buf) >= 0) {
                    // digest updated by DigestInputStream
                }
            }
            return toHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Copy {@code file} to {@code out} while hashing; returns sha256 hex. */
    public static String copyAndSha256(File file, OutputStream out) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[64 * 1024];
            try (InputStream in = new FileInputStream(file)) {
                int n;
                while ((n = in.read(buf)) > 0) {
                    digest.update(buf, 0, n);
                    out.write(buf, 0, n);
                }
            }
            return toHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String toHex(byte[] digest) {
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            hex.append(Character.forDigit((b >> 4) & 0xf, 16));
            hex.append(Character.forDigit(b & 0xf, 16));
        }
        return hex.toString();
    }
}
