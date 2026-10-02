package com.zetteldraw.penpoc;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Always-on launch timeline: what happened, in ms since the process started,
 * kept in a small ring-buffer file ({@code files/launch-log.txt}, the last
 * {@link #MAX_LINES} lines across launches). Callers never wait for disk:
 * lines are appended on one background thread. Long-press the Scratchpad
 * icon to read it; sync uploads it.
 */
public final class LaunchLog {
    private static final String TAG = "zd-launch";
    static final String FILE_NAME = "launch-log.txt";
    private static final int MAX_LINES = 800;
    /** A launch that logs more than this keeps only its first lines. */
    private static final int MAX_LINES_PER_LAUNCH = 600;
    private static final String PREFS = "launch_log";
    private static final String KEY_LAUNCH = "launch";
    private static final String KEY_UPLOADED = "uploaded_through";

    private static final Object lock = new Object();
    /** Lines of this launch, also kept in memory for the viewer. */
    private static final ArrayList<String> current = new ArrayList<>();
    /** Earlier launches' lines, as read from the file at start. */
    private static List<String> earlier = new ArrayList<>();
    private static final HashSet<String> onceKeys = new HashSet<>();
    private static ExecutorService io;
    private static File file;
    private static long startUptime = SystemClock.uptimeMillis();
    private static int launch;
    private static Context app;

    private LaunchLog() {
    }

    /** First thing in {@link PenApp}: starts this launch's section. The log must never stop the app. */
    public static void start(Context context) {
        try {
            startOrThrow(context);
        } catch (RuntimeException e) {
            Log.w(TAG, "launch log off for this launch", e);
        }
    }

    private static void startOrThrow(Context context) {
        synchronized (lock) {
            if (io != null) {
                return;
            }
            // Inside Application.attachBaseContext the application context is still null.
            app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
            startUptime = Process.getStartUptimeMillis();
            file = new File(app.getFilesDir(), FILE_NAME);
            io = Executors.newSingleThreadExecutor(r -> {
                Thread thread = new Thread(r, "zd-launch-log");
                thread.setDaemon(true);
                return thread;
            });
            SharedPreferences prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            launch = prefs.getInt(KEY_LAUNCH, 0) + 1;
            prefs.edit().putInt(KEY_LAUNCH, launch).apply();
            String header = "=== launch " + launch + " · v" + BuildConfig.VERSION_CODE + " (" + BuildConfig.VERSION_NAME
                    + ") · " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(new Date())
                    + " · pid " + Process.myPid() + " · app code at +" + sinceStart() + " ms";
            current.add(header);
            io.execute(LaunchLog::openFile);
        }
    }

    /** Records one event. Cheap and safe on any thread, before or after {@link #start}. */
    public static void mark(String event) {
        String line = "+" + sinceStart() + " ms" + thread() + " " + event;
        Log.i(TAG, line);
        synchronized (lock) {
            if (current.size() >= MAX_LINES_PER_LAUNCH) {
                return;
            }
            current.add(line);
            if (io != null) {
                io.execute(LaunchLog::appendNew);
            }
        }
    }

    /** Records {@code event} the first time {@code key} is seen in this launch. */
    public static void once(String key, String event) {
        synchronized (lock) {
            if (!onceKeys.add(key)) {
                return;
            }
        }
        mark(event);
    }

    public static long sinceStart() {
        return SystemClock.uptimeMillis() - startUptime;
    }

    /** This launch, then the earlier ones, newest first; for the on-device viewer. */
    public static String forViewer(int earlierLaunches) {
        synchronized (lock) {
            StringBuilder out = new StringBuilder();
            for (String line : current) {
                out.append(line).append('\n');
            }
            List<List<String>> launches = splitLaunches(earlier);
            for (int i = launches.size() - 1, shown = 0; i >= 0 && shown < earlierLaunches; i--, shown++) {
                out.append('\n');
                for (String line : launches.get(i)) {
                    out.append(line).append('\n');
                }
            }
            return out.toString();
        }
    }

    /** The whole ring buffer (earlier launches, then this one), oldest first; for upload. */
    public static String all() {
        synchronized (lock) {
            StringBuilder out = new StringBuilder();
            for (String line : earlier) {
                out.append(line).append('\n');
            }
            for (String line : current) {
                out.append(line).append('\n');
            }
            return out.toString();
        }
    }

    /** Number of lines logged in this launch; with {@link #launchNumber} marks what was uploaded. */
    public static int lineCount() {
        synchronized (lock) {
            return current.size();
        }
    }

    public static int launchNumber() {
        synchronized (lock) {
            return launch;
        }
    }

    /** True when lines were logged since the last upload. */
    public static boolean hasUnsent() {
        if (app == null) {
            return false;
        }
        String uploaded = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_UPLOADED, "");
        return !uploaded.equals(launchNumber() + ":" + lineCount());
    }

    public static void markSent(int launchNumber, int lines) {
        if (app != null) {
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(KEY_UPLOADED, launchNumber + ":" + lines).apply();
        }
    }

    private static String thread() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return "";
        }
        return " [" + Thread.currentThread().getName() + "]";
    }

    // ---- log thread ----

    private static void openFile() {
        List<String> old = new ArrayList<>();
        try {
            if (file.exists()) {
                old = new ArrayList<>(Files.readAllLines(file.toPath(), StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            Log.w(TAG, "launch log unreadable; starting over", e);
        }
        List<String> mine;
        synchronized (lock) {
            mine = new ArrayList<>(current);
        }
        int keep = Math.max(0, MAX_LINES - mine.size());
        List<String> kept = old.size() > keep ? new ArrayList<>(old.subList(old.size() - keep, old.size())) : old;
        // Drop a launch cut in half by the trim, so every kept launch starts with its header.
        while (!kept.isEmpty() && !kept.get(0).startsWith("===")) {
            kept.remove(0);
        }
        synchronized (lock) {
            earlier = kept;
            mine = new ArrayList<>(current);
        }
        ArrayList<String> all = new ArrayList<>(kept);
        all.addAll(mine);
        File tmp = new File(file.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write((String.join("\n", all) + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            Log.w(TAG, "launch log not written", e);
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        tmp.renameTo(file);
        written = mine.size();
    }

    /** Log thread only: how many lines of {@link #current} are in the file; -1 until it is rewritten at start. */
    private static int written = -1;

    private static void appendNew() {
        if (written < 0) {
            return;
        }
        StringBuilder text = new StringBuilder();
        int upTo;
        synchronized (lock) {
            upTo = current.size();
            for (int i = written; i < upTo; i++) {
                text.append(current.get(i)).append('\n');
            }
        }
        if (text.length() == 0) {
            return;
        }
        try (FileOutputStream out = new FileOutputStream(file, true)) {
            out.write(text.toString().getBytes(StandardCharsets.UTF_8));
            written = upTo;
        } catch (IOException e) {
            Log.w(TAG, "launch log lines not written", e);
        }
    }

    private static List<List<String>> splitLaunches(List<String> lines) {
        ArrayList<List<String>> launches = new ArrayList<>();
        for (String line : lines) {
            if (line.startsWith("===") || launches.isEmpty()) {
                launches.add(new ArrayList<>());
            }
            launches.get(launches.size() - 1).add(line);
        }
        return launches;
    }
}
