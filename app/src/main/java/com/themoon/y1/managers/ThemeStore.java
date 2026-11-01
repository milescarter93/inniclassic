package com.themoon.y1.managers;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// 🚀 [Store] The theme store: a catalog.json on the fork's GitHub Pages site lists themes as zips. Installing
// one only puts "<name>.zip" into Y1_Themes - ThemeManager already unpacks any zip it finds there - after
// checking its size and SHA-256 against the catalog. The installed version is remembered, so a newer one in
// the catalog shows as an update.
public class ThemeStore {
    public static final String DEFAULT_URL = "https://milescarter93.github.io/inniclassic/";
    public static final File THEMES_DIR = new File("/storage/sdcard0/Y1_Themes");

    public static class Item {
        public String id, name, title, author, description, file, sha256;
        public int version, minApp;
        public long size;
    }

    public interface Progress {
        void onProgress(int percent);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("Y1_SETTINGS", Context.MODE_PRIVATE);
    }

    // The site, ending in "/". "store_url" in the settings can point elsewhere (a copy being tested).
    public static String baseUrl(Context context) {
        String url = prefs(context).getString("store_url", DEFAULT_URL);
        return url.endsWith("/") ? url : url + "/";
    }

    // The themes this app version can use, in the catalog's order.
    public static List<Item> themes(Context context) throws Exception {
        okhttp3.Request request = new okhttp3.Request.Builder().url(baseUrl(context) + "catalog.json")
                .header("Cache-Control", "no-cache").build();
        String body;
        okhttp3.Response response = LyricsFetcher.client(context).newCall(request).execute();
        try {
            if (!response.isSuccessful()) throw new java.io.IOException("HTTP " + response.code());
            body = response.body().string();
        } finally {
            response.close();
        }
        int appVersion = 0;
        try {
            appVersion = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionCode;
        } catch (Exception ignored) {
        }
        boolean spanish = isSpanish(context);
        List<Item> items = new ArrayList<>();
        JSONArray arr = new JSONObject(body).optJSONArray("themes");
        for (int i = 0; arr != null && i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            Item it = new Item();
            it.id = o.optString("id");
            it.name = o.optString("name");
            it.title = o.optString("title", it.name);
            it.author = o.optString("author");
            JSONObject desc = o.optJSONObject("description");
            it.description = desc == null ? "" : desc.optString(spanish ? "es" : "en", desc.optString("en"));
            it.file = o.optString("file");
            it.sha256 = o.optString("sha256").toLowerCase(Locale.US);
            it.version = o.optInt("version", 1);
            it.minApp = o.optInt("minApp", 0);
            it.size = o.optLong("size", 0);
            if (it.id.isEmpty() || !safeName(it.name) || it.file.isEmpty() || it.minApp > appVersion) continue;
            items.add(it);
        }
        return items;
    }

    private static boolean isSpanish(Context context) {
        String lang = LanguageManager.getInstance(context).currentLangFileName;
        return lang != null && lang.toLowerCase(Locale.US).startsWith("spanish");
    }

    // A folder name: no path separators or characters FAT32 refuses.
    static boolean safeName(String name) {
        return name != null && !name.trim().isEmpty() && !name.contains("..") && !name.matches(".*[\\\\/:*?\"<>|].*");
    }

    public static boolean isInstalled(Item it) {
        return new File(new File(THEMES_DIR, it.name), "config.json").isFile();
    }

    // Installed from the store at an older version than the catalog's. (A theme that came with the app, or
    // was copied by hand, counts as up to date: there is nothing to compare.)
    public static boolean hasUpdate(Context context, Item it) {
        int installed = prefs(context).getInt("store_theme_" + it.id, 0);
        return isInstalled(it) && installed > 0 && installed < it.version;
    }

    // Downloads the zip next to the themes and checks it; ThemeManager unpacks it on its next scan.
    public static void install(Context context, Item it, Progress progress) throws Exception {
        THEMES_DIR.mkdirs();
        File part = new File(THEMES_DIR, it.name + ".zip.part");
        File zip = new File(THEMES_DIR, it.name + ".zip");
        String url = it.file.startsWith("http") ? it.file : baseUrl(context) + it.file;
        okhttp3.Response response = LyricsFetcher.client(context)
                .newCall(new okhttp3.Request.Builder().url(url).build()).execute();
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        long done = 0;
        try {
            if (!response.isSuccessful()) throw new java.io.IOException("HTTP " + response.code());
            long total = it.size > 0 ? it.size : response.body().contentLength();
            InputStream in = response.body().byteStream();
            FileOutputStream out = new FileOutputStream(part);
            try {
                byte[] buf = new byte[16384];
                int n, last = -1;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    sha.update(buf, 0, n);
                    done += n;
                    int pct = total > 0 ? (int) Math.min(99, done * 100 / total) : 0;
                    if (pct != last && progress != null) progress.onProgress(last = pct);
                }
            } finally {
                out.close();
            }
        } catch (Exception e) {
            part.delete();
            throw e;
        } finally {
            response.close();
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : sha.digest()) hex.append(String.format(Locale.US, "%02x", b));
        if ((it.size > 0 && done != it.size) || (!it.sha256.isEmpty() && !it.sha256.equals(hex.toString()))) {
            part.delete();
            throw new DamagedException();
        }
        zip.delete();
        if (!part.renameTo(zip)) {
            part.delete();
            throw new java.io.IOException("rename failed");
        }
        prefs(context).edit().putInt("store_theme_" + it.id, it.version).apply();
    }

    public static class DamagedException extends java.io.IOException {
    }
}
