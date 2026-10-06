package com.themoon.y1.managers;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Date;

// 🚀 [Crash log] Appends uncaught crashes to y1_crash_log.txt on the SD card without letting it grow
// forever: the file is rotated to y1_crash_log.old.txt past MAX_LOG_BYTES, and a crash loop (the same
// stack trace again within REPEAT_WINDOW_MS) adds a one-line note instead of another full report.
public class CrashLogger {

    private static final File LOG_FILE = new File("/storage/sdcard0/y1_crash_log.txt");
    private static final File OLD_LOG_FILE = new File("/storage/sdcard0/y1_crash_log.old.txt");
    private static final long MAX_LOG_BYTES = 256 * 1024;
    private static final long REPEAT_WINDOW_MS = 10 * 60 * 1000;
    // How many stack trace lines identify "the same crash" (exception type, message and where it happened).
    private static final int SIGNATURE_LINES = 6;

    // Returns true if this is the same crash again within REPEAT_WINDOW_MS (a crash loop) - callers use it
    // to avoid relaunching into the same crash over and over.
    public static boolean logCrash(Context context, Throwable e) {
        boolean repeated = false;
        try {
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            String trace = sw.toString();
            String signature = signatureOf(trace);

            // Crash-loop detection state lives in prefs: the process is about to die, so nothing in memory survives.
            SharedPreferences prefs = context.getSharedPreferences("Y1_SETTINGS", Context.MODE_PRIVATE);
            long now = System.currentTimeMillis();
            repeated = signature.equals(prefs.getString("last_crash_signature", null))
                    && Math.abs(now - prefs.getLong("last_crash_time", 0)) < REPEAT_WINDOW_MS;
            prefs.edit().putString("last_crash_signature", signature).putLong("last_crash_time", now).commit();

            if (LOG_FILE.length() > MAX_LOG_BYTES) {
                OLD_LOG_FILE.delete();
                LOG_FILE.renameTo(OLD_LOG_FILE);
            }

            StringBuilder entry = new StringBuilder();
            if (repeated) {
                entry.append("--- 💥 same crash again (").append(new Date()).append(") ---\n");
            } else {
                entry.append("\n\n--- 💥 CRASH REPORT (").append(new Date()).append(") v")
                        .append(appVersion(context)).append(" ---\n").append(trace);
            }
            FileOutputStream fos = new FileOutputStream(LOG_FILE, true);
            try {
                fos.write(entry.toString().getBytes("UTF-8"));
            } finally {
                fos.close();
            }
        } catch (Throwable ignored) {
            // Never let logging a crash cause a second one.
        }
        return repeated;
    }

    private static String signatureOf(String trace) {
        String[] lines = trace.split("\n");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length && i < SIGNATURE_LINES; i++) sb.append(lines[i].trim()).append('\n');
        return String.valueOf(sb.toString().hashCode());
    }

    private static String appVersion(Context context) {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }
}
