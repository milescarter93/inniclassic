package com.themoon.y1.managers;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

// Estimates battery time left (on battery) or time to full (charging) from how fast the level has
// been changing. Every level change since the last plug/unplug is recorded with its time; the rate
// is taken between the first and last *change* (the first sample is just when we started watching,
// somewhere inside a level). Samples older than WINDOW_MS are dropped so the estimate follows recent
// use. Kept in its own prefs file (not part of the settings backup) so it survives app restarts.
public final class BatteryEstimator {
    private static final String TAG = "Y1Battery";
    private static final String PREFS = "Y1_BATTERY";
    private static final long WINDOW_MS = 8L * 60 * 60 * 1000;
    private static final long MIN_SPAN_MS = 10L * 60 * 1000;
    private static final int MIN_LEVEL_STEPS = 2;

    private static BatteryEstimator instance;

    private final SharedPreferences prefs;
    private boolean charging;
    private final List<long[]> samples = new ArrayList<>(); // {time ms, level}
    private int level = -1;
    private boolean full;

    public static synchronized BatteryEstimator getInstance(Context context) {
        if (instance == null) instance = new BatteryEstimator(context.getApplicationContext());
        return instance;
    }

    private BatteryEstimator(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        charging = prefs.getBoolean("charging", false);
        String saved = prefs.getString("samples", "");
        for (String part : saved.split(";")) {
            String[] p = part.split(",");
            if (p.length != 2) continue;
            try {
                samples.add(new long[] { Long.parseLong(p[0]), Long.parseLong(p[1]) });
            } catch (NumberFormatException ignored) {
            }
        }
    }

    // Call with every ACTION_BATTERY_CHANGED. plugged = EXTRA_PLUGGED != 0.
    public synchronized void onBatteryChanged(int level, boolean plugged, boolean isFull) {
        if (level < 0) return;
        long now = System.currentTimeMillis();
        this.level = level;
        this.full = isFull;
        boolean changed = false;
        // A clock jump backwards (time set by hand) would make the rate meaningless: start over.
        if (plugged != charging || (!samples.isEmpty() && now < samples.get(samples.size() - 1)[0])) {
            Log.i(TAG, "start " + (plugged ? "charging" : "discharging") + " at " + level + "%");
            charging = plugged;
            samples.clear();
            changed = true;
        }
        if (samples.isEmpty() || samples.get(samples.size() - 1)[1] != level) {
            samples.add(new long[] { now, level });
            changed = true;
        }
        while (samples.size() > 3 && now - samples.get(1)[0] > WINDOW_MS) {
            samples.remove(0);
            changed = true;
        }
        if (changed) {
            StringBuilder sb = new StringBuilder();
            for (long[] s : samples) {
                if (sb.length() > 0) sb.append(';');
                sb.append(s[0]).append(',').append(s[1]);
            }
            prefs.edit().putBoolean("charging", charging).putString("samples", sb.toString()).apply();
            long est = estimateMs();
            Log.i(TAG, "level " + level + "% " + (charging ? "charging" : "on battery") + ", samples "
                    + samples.size() + ", estimate " + (est < 0 ? "-" : (est / 60000) + " min"));
        }
    }

    public synchronized boolean isCharging() {
        return charging;
    }

    // Minutes-precision estimate in ms: time left on battery, or time to full while charging.
    // -1 = not enough data yet (needs a couple of level steps over 10+ minutes). 0 = full.
    public synchronized long estimateMs() {
        if (charging && (full || level >= 100)) return 0;
        if (samples.size() < 3 || level < 0) return -1;
        long[] first = samples.get(1); // first actual level change
        long[] last = samples.get(samples.size() - 1);
        long span = last[0] - first[0];
        long steps = Math.abs(last[1] - first[1]);
        if (span < MIN_SPAN_MS || steps < MIN_LEVEL_STEPS) return -1;
        double msPerLevel = span / (double) steps;
        int levelsToGo = charging ? 100 - level : level;
        return Math.max(0, (long) (levelsToGo * msPerLevel));
    }
}
