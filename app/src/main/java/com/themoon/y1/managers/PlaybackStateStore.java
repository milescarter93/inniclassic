package com.themoon.y1.managers;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// 🚀 [Crash recovery] Remembers what was playing - the queue in its exact (possibly shuffled) order, the
// track, the position and whether it was playing - so playback can resume after the launcher crashes.
//
// The queue can hold the whole library, so it's written to its own file only when it changes; the small,
// frequently updated parts live in SharedPreferences. Kept separate from Y1_SETTINGS so settings backups
// don't carry it.
public class PlaybackStateStore {

    private static final String PREFS = "Y1_PLAYBACK";
    private static final String QUEUE_FILE = "playback_queue.txt";
    private static final String SEPARATOR = "---";
    // Queue writes happen off the UI thread, one at a time and in order.
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor();

    public static class State {
        public final List<File> originalQueue, currentQueue;
        public final int index, positionMs;
        public final boolean wasPlaying;

        State(List<File> originalQueue, List<File> currentQueue, int index, int positionMs, boolean wasPlaying) {
            this.originalQueue = originalQueue;
            this.currentQueue = currentQueue;
            this.index = index;
            this.positionMs = positionMs;
            this.wasPlaying = wasPlaying;
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // Called when a new queue starts playing. originalQueue is the unshuffled order (kept so turning
    // shuffle off after resuming still works); currentQueue is the order actually being played.
    public static void saveQueue(Context context, List<File> originalQueue, List<File> currentQueue) {
        final File target = new File(context.getFilesDir(), QUEUE_FILE);
        final List<String> lines = new ArrayList<>(originalQueue.size() + currentQueue.size() + 1);
        for (File f : originalQueue) lines.add(f.getAbsolutePath());
        lines.add(SEPARATOR);
        for (File f : currentQueue) lines.add(f.getAbsolutePath());
        prefs(context).edit().putBoolean("resumable", true).apply();
        WRITER.execute(new Runnable() {
            @Override
            public void run() {
                File tmp = new File(target.getPath() + ".tmp");
                try {
                    StringBuilder sb = new StringBuilder();
                    for (String line : lines) sb.append(line).append('\n');
                    FileOutputStream out = new FileOutputStream(tmp);
                    try {
                        out.write(sb.toString().getBytes("UTF-8"));
                    } finally {
                        out.close();
                    }
                    tmp.renameTo(target);
                } catch (Exception e) {
                    tmp.delete();
                }
            }
        });
    }

    // Playback moved to something that can't be resumed from the card (a podcast stream).
    public static void markNotResumable(Context context) {
        prefs(context).edit().putBoolean("resumable", false).apply();
    }

    public static void saveProgress(Context context, int index, int positionMs, boolean playing) {
        saveProgress(context, index, positionMs, playing, false);
    }

    // sync = true when the process may die right after (crash handler): commit() instead of apply().
    public static void saveProgress(Context context, int index, int positionMs, boolean playing, boolean sync) {
        SharedPreferences.Editor editor = prefs(context).edit()
                .putInt("index", index)
                .putInt("position_ms", Math.max(0, positionMs))
                .putBoolean("playing", playing);
        if (sync) editor.commit();
        else editor.apply();
    }

    public static State load(Context context) {
        SharedPreferences p = prefs(context);
        if (!p.getBoolean("resumable", false)) return null;
        File file = new File(context.getFilesDir(), QUEUE_FILE);
        if (!file.isFile()) return null;
        List<File> original = new ArrayList<>(), current = new ArrayList<>();
        try {
            BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"));
            try {
                List<File> target = original;
                String line;
                while ((line = in.readLine()) != null) {
                    if (line.equals(SEPARATOR)) {
                        target = current;
                    } else if (!line.isEmpty()) {
                        target.add(new File(line));
                    }
                }
            } finally {
                in.close();
            }
        } catch (Exception e) {
            return null;
        }
        if (current.isEmpty()) return null;
        int index = p.getInt("index", 0);
        if (index < 0 || index >= current.size()) return null;
        return new State(original.isEmpty() ? current : original, current, index,
                p.getInt("position_ms", 0), p.getBoolean("playing", false));
    }

    // A relaunch normally follows within seconds; if it didn't happen (the user opens the app hours
    // later), starting the music by surprise would be wrong, so the request expires.
    private static final long RESUME_WINDOW_MS = 2 * 60 * 1000;

    // Set by the crash handler right before the process dies; read once by the relaunched app.
    public static void markResumeAfterCrash(Context context) {
        prefs(context).edit().putLong("resume_after_crash_at", System.currentTimeMillis()).commit();
    }

    public static boolean consumeResumeAfterCrash(Context context) {
        SharedPreferences p = prefs(context);
        long at = p.getLong("resume_after_crash_at", 0);
        if (at == 0) return false;
        p.edit().remove("resume_after_crash_at").commit();
        long age = System.currentTimeMillis() - at;
        return age >= 0 && age < RESUME_WINDOW_MS;
    }
}
