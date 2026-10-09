package com.themoon.y1.managers;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

// 🚀 [Settings backup] Exports/imports the app's SharedPreferences to a JSON file on the SD card, so
// settings survive reinstalling the launcher (a debug build can't replace the system APK without
// uninstalling it first, which wipes /data). Every value is stored with its type so it comes back
// exactly as it was written.
public class SettingsBackupManager {

    public static final File BACKUP_FOLDER = new File("/storage/sdcard0/Y1_Backup");
    public static final File BACKUP_FILE = new File(BACKUP_FOLDER, "inniclassic_settings.json");
    private static final File PREVIOUS_BACKUP_FILE = new File(BACKUP_FOLDER, "inniclassic_settings.prev.json");

    private static final String FORMAT_ID = "inniclassic-settings";
    private static final int FORMAT_VERSION = 1;

    // Y1_LASTFM is left out on purpose: it holds the Last.fm session key, which shouldn't sit in a
    // plain file on a removable card.
    private static final String[] PREF_FILES = { "Y1_SETTINGS", "Y1_AUDIOBOOK_BOOKMARKS" };

    // One-shot flags that must never be restored.
    private static final Set<String> TRANSIENT_KEYS = new HashSet<>();
    static {
        TRANSIENT_KEYS.add("reboot_to_theme");
        TRANSIENT_KEYS.add("reboot_to_settings");
        TRANSIENT_KEYS.add("reboot_settings_focus");
        TRANSIENT_KEYS.add("last_attempted_file");
        TRANSIENT_KEYS.add("last_crash_signature"); // CrashLogger's crash-loop detection state
        TRANSIENT_KEYS.add("last_crash_time");
    }

    public static boolean hasBackup() {
        return BACKUP_FILE.isFile();
    }

    public static long getBackupTime() {
        return BACKUP_FILE.isFile() ? BACKUP_FILE.lastModified() : 0;
    }

    // Returns the number of exported values. The previous backup is kept as *.prev.json.
    public static int exportSettings(Context context) throws Exception {
        JSONObject prefsJson = new JSONObject();
        int count = 0;
        for (String prefName : PREF_FILES) {
            SharedPreferences sp = context.getSharedPreferences(prefName, Context.MODE_PRIVATE);
            JSONObject values = new JSONObject();
            for (Map.Entry<String, ?> entry : sp.getAll().entrySet()) {
                if (TRANSIENT_KEYS.contains(entry.getKey())) continue;
                JSONObject typed = toTypedJson(entry.getValue());
                if (typed != null) {
                    values.put(entry.getKey(), typed);
                    count++;
                }
            }
            prefsJson.put(prefName, values);
        }

        String appVersion = "";
        try {
            appVersion = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Exception e) {}

        JSONObject root = new JSONObject();
        root.put("format", FORMAT_ID);
        root.put("version", FORMAT_VERSION);
        root.put("appVersion", appVersion);
        root.put("exportedAt", System.currentTimeMillis());
        root.put("prefs", prefsJson);

        if (!BACKUP_FOLDER.exists()) BACKUP_FOLDER.mkdirs();
        // Write to a temp file first so a pulled card or a crash never leaves a half-written backup.
        File tmp = new File(BACKUP_FOLDER, BACKUP_FILE.getName() + ".tmp");
        FileOutputStream out = new FileOutputStream(tmp);
        try {
            out.write(root.toString(2).getBytes("UTF-8"));
            out.getFD().sync();
        } finally {
            out.close();
        }
        if (BACKUP_FILE.exists()) {
            PREVIOUS_BACKUP_FILE.delete();
            BACKUP_FILE.renameTo(PREVIOUS_BACKUP_FILE);
        }
        if (!tmp.renameTo(BACKUP_FILE)) throw new Exception("Could not write " + BACKUP_FILE);
        return count;
    }

    // Replaces each preference file found in the backup with its contents. Returns the number of
    // restored values. Preference files missing from the backup are left untouched.
    public static int importSettings(Context context) throws Exception {
        FileInputStream in = new FileInputStream(BACKUP_FILE);
        byte[] data;
        try {
            data = new byte[(int) BACKUP_FILE.length()];
            int read = 0;
            while (read < data.length) {
                int n = in.read(data, read, data.length - read);
                if (n < 0) break;
                read += n;
            }
        } finally {
            in.close();
        }

        JSONObject root = new JSONObject(new String(data, "UTF-8").replace("﻿", ""));
        if (!FORMAT_ID.equals(root.optString("format")) || root.optInt("version", 0) > FORMAT_VERSION) {
            throw new Exception("Unsupported backup file");
        }
        JSONObject prefsJson = root.getJSONObject("prefs");

        int count = 0;
        for (String prefName : PREF_FILES) {
            JSONObject values = prefsJson.optJSONObject(prefName);
            if (values == null) continue;
            SharedPreferences.Editor editor = context.getSharedPreferences(prefName, Context.MODE_PRIVATE).edit();
            editor.clear();
            Iterator<String> keys = values.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (TRANSIENT_KEYS.contains(key)) continue;
                if (putTypedValue(editor, key, values.getJSONObject(key))) count++;
            }
            editor.commit();
        }
        return count;
    }

    private static JSONObject toTypedJson(Object value) throws Exception {
        JSONObject typed = new JSONObject();
        if (value instanceof Boolean) {
            typed.put("type", "boolean");
            typed.put("value", value);
        } else if (value instanceof Integer) {
            typed.put("type", "int");
            typed.put("value", value);
        } else if (value instanceof Long) {
            typed.put("type", "long");
            typed.put("value", value);
        } else if (value instanceof Float) {
            typed.put("type", "float");
            typed.put("value", ((Float) value).doubleValue());
        } else if (value instanceof String) {
            typed.put("type", "string");
            typed.put("value", value);
        } else if (value instanceof Set) {
            JSONArray arr = new JSONArray();
            for (Object o : (Set<?>) value) arr.put(String.valueOf(o));
            typed.put("type", "set");
            typed.put("value", arr);
        } else {
            return null;
        }
        return typed;
    }

    private static boolean putTypedValue(SharedPreferences.Editor editor, String key, JSONObject typed) throws Exception {
        String type = typed.optString("type");
        if ("boolean".equals(type)) {
            editor.putBoolean(key, typed.getBoolean("value"));
        } else if ("int".equals(type)) {
            editor.putInt(key, typed.getInt("value"));
        } else if ("long".equals(type)) {
            editor.putLong(key, typed.getLong("value"));
        } else if ("float".equals(type)) {
            editor.putFloat(key, (float) typed.getDouble("value"));
        } else if ("string".equals(type)) {
            editor.putString(key, typed.getString("value"));
        } else if ("set".equals(type)) {
            JSONArray arr = typed.getJSONArray("value");
            Set<String> set = new HashSet<>();
            for (int i = 0; i < arr.length(); i++) set.add(arr.getString(i));
            editor.putStringSet(key, set);
        } else {
            return false;
        }
        return true;
    }
}
