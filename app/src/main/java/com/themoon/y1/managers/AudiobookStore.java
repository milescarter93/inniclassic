package com.themoon.y1.managers;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.StatFs;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

// 🚀 [Audiobooks] Free LibriVox audiobooks (public domain, read by volunteers), found through the
// Internet Archive, which hosts them and - unlike LibriVox's own API - can filter by language and sort by
// popularity. A book is downloaded chapter by chapter (64 kbps MP3, about 29 MB an hour) into
// Audiobooks/<Author>/<Title>/ with a cover.jpg, so it joins the audiobook library like any copied book
// (LibriVox tags: album = book, artist = author). Downloads run one book at a time in the background,
// keep Wi-Fi awake, resume a cut file where it stopped, and carry on after the app restarts.
public final class AudiobookStore {
    private static final String TAG = "Y1Books";
    public static final String LANG_ES = "es", LANG_EN = "en";
    public static final File ROOT = new File("/storage/sdcard0/Audiobooks");
    private static final String MARKER = ".librivox"; // written in a book's folder once it's complete
    private static final int PAGE_SIZE = 40;

    public static final class Book {
        public String id, title, author;
        public long downloads;
    }

    public static final class Chapter {
        public String name, title;
        public long size;
        public int seconds;
    }

    public static final class Details {
        public Book book;
        public final List<Chapter> chapters = new ArrayList<>();
        public long totalBytes;
        public int totalSeconds;
        public String description = "";
    }

    public static final class Job {
        public final Details details;
        public final File dir;
        public volatile int doneFiles;
        public volatile long doneBytes;
        public volatile boolean cancelled, running;
        public volatile String error;

        Job(Details d) {
            details = d;
            dir = bookDir(d.book);
        }

        public int percent() {
            return details.totalBytes > 0 ? (int) Math.min(100, doneBytes * 100 / details.totalBytes)
                    : details.chapters.isEmpty() ? 0 : doneFiles * 100 / details.chapters.size();
        }
    }

    public interface Listener {
        void onProgress(Job job);

        void onFinished(Job job, boolean ok);
    }

    private static okhttp3.OkHttpClient http;
    private static final List<Job> jobs = new ArrayList<>(); // the running one first
    private static Thread worker;
    private static volatile Listener listener;
    private static final Handler main = new Handler(Looper.getMainLooper());

    private AudiobookStore() {
    }

    public static void setListener(Listener l) {
        listener = l;
    }

    private static synchronized okhttp3.OkHttpClient http(Context c) throws Exception {
        if (http == null)
            http = LyricsFetcher.client(c).newBuilder().readTimeout(40, TimeUnit.SECONDS)
                    .followRedirects(true).followSslRedirects(true).build();
        return http;
    }

    private static String get(Context c, String url) throws Exception {
        okhttp3.Response r = http(c).newCall(new okhttp3.Request.Builder().url(url)
                .header("User-Agent", "InniClassic (Innioasis Y1)").build()).execute();
        try {
            if (!r.isSuccessful()) throw new java.io.IOException("HTTP " + r.code());
            return r.body().string();
        } finally {
            r.close();
        }
    }

    private static String text(JSONObject o, String key) {
        Object v = o.opt(key);
        if (v instanceof JSONArray) {
            JSONArray a = (JSONArray) v;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < a.length(); i++) sb.append(i > 0 ? ", " : "").append(a.optString(i));
            return sb.toString();
        }
        return v == null ? "" : String.valueOf(v);
    }

    // Most downloaded first; query (optional) matches title or author. page starts at 1.
    public static List<Book> search(Context c, String lang, String query, int page) throws Exception {
        StringBuilder q = new StringBuilder("collection:librivoxaudio AND language:(")
                .append(LANG_ES.equals(lang) ? "spa OR spanish OR es OR Spanish" : "eng OR english OR en OR English")
                .append(")");
        if (query != null) {
            String clean = query.replaceAll("[^\\p{L}\\p{N} ]", " ").trim().replaceAll("\\s+", " ");
            if (!clean.isEmpty()) q.append(" AND (title:(").append(clean).append(") OR creator:(").append(clean).append("))");
        }
        String url = "https://archive.org/advancedsearch.php?q=" + URLEncoder.encode(q.toString(), "UTF-8")
                + "&fl%5B%5D=identifier&fl%5B%5D=title&fl%5B%5D=creator&fl%5B%5D=downloads"
                + "&sort%5B%5D=downloads+desc&rows=" + PAGE_SIZE + "&page=" + page + "&output=json";
        JSONArray docs = new JSONObject(get(c, url)).getJSONObject("response").getJSONArray("docs");
        List<Book> out = new ArrayList<>();
        for (int i = 0; i < docs.length(); i++) {
            JSONObject d = docs.getJSONObject(i);
            Book b = new Book();
            b.id = d.optString("identifier");
            b.title = text(d, "title").trim();
            b.author = text(d, "creator").trim();
            b.downloads = d.optLong("downloads");
            if (!b.id.isEmpty() && !b.title.isEmpty()) out.add(b);
        }
        Log.i(TAG, "Search " + lang + " '" + query + "' page " + page + ": " + out.size());
        return out;
    }

    public static int pageSize() {
        return PAGE_SIZE;
    }

    private static int seconds(String length) {
        if (length == null || length.isEmpty()) return 0;
        try {
            if (length.contains(":")) {
                int s = 0;
                for (String part : length.split(":")) s = s * 60 + (int) Float.parseFloat(part);
                return s;
            }
            return (int) Float.parseFloat(length);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static Details details(Context c, String id) throws Exception {
        JSONObject root = new JSONObject(get(c, "https://archive.org/metadata/" + URLEncoder.encode(id, "UTF-8")));
        JSONObject m = root.getJSONObject("metadata");
        Details d = new Details();
        d.book = new Book();
        d.book.id = id;
        d.book.title = text(m, "title").trim();
        d.book.author = text(m, "creator").trim();
        d.description = text(m, "description").replaceAll("<[^>]*>", " ").replace("&nbsp;", " ")
                .replace("&amp;", "&").replaceAll("\\s+", " ").trim();
        JSONArray files = root.optJSONArray("files");
        for (String format : new String[] { "64Kbps MP3", "VBR MP3", "128Kbps MP3" }) {
            if (files == null || !d.chapters.isEmpty()) break;
            for (int i = 0; i < files.length(); i++) {
                JSONObject f = files.getJSONObject(i);
                if (!format.equals(f.optString("format"))) continue;
                Chapter ch = new Chapter();
                ch.name = f.optString("name");
                ch.title = f.optString("title", ch.name);
                ch.size = f.optLong("size");
                ch.seconds = seconds(f.optString("length"));
                if (ch.name.contains("/")) continue; // only the item's own folder
                d.chapters.add(ch);
            }
        }
        Collections.sort(d.chapters, (a, b) -> a.name.compareToIgnoreCase(b.name));
        for (Chapter ch : d.chapters) {
            d.totalBytes += ch.size;
            d.totalSeconds += ch.seconds;
        }
        return d;
    }

    // Audiobooks/<Author>/<Title>, with names safe for the FAT card.
    public static File bookDir(Book b) {
        return new File(new File(ROOT, safe(b.author.isEmpty() ? "LibriVox" : b.author)), safe(b.title));
    }

    private static String safe(String s) {
        String out = s.replaceAll("[\\\\/:*?\"<>|]", "_").replaceAll("\\s+", " ").trim();
        while (out.endsWith(".")) out = out.substring(0, out.length() - 1);
        if (out.length() > 80) out = out.substring(0, 80).trim();
        return out.isEmpty() ? "_" : out;
    }

    public static boolean isDownloaded(Book b) {
        return new File(bookDir(b), MARKER).exists();
    }

    public static long freeBytes() {
        try {
            StatFs fs = new StatFs("/storage/sdcard0");
            return (long) fs.getAvailableBlocks() * fs.getBlockSize();
        } catch (Exception e) {
            return Long.MAX_VALUE;
        }
    }

    public static boolean deleteBook(Book b) {
        File dir = bookDir(b);
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) f.delete();
        boolean ok = dir.delete();
        File author = dir.getParentFile();
        String[] left = author != null ? author.list() : null;
        if (left != null && left.length == 0) author.delete();
        return ok;
    }

    // ---------------------------------------------------------------- downloads

    public static synchronized List<Job> jobs() {
        return new ArrayList<>(jobs);
    }

    public static synchronized Job jobFor(String id) {
        for (Job j : jobs) if (j.details.book.id.equals(id)) return j;
        return null;
    }

    public static synchronized Job enqueue(Context c, Details d) {
        Job existing = jobFor(d.book.id);
        if (existing != null) return existing;
        Job j = new Job(d);
        jobs.add(j);
        savePending(c);
        if (worker == null) {
            final Context app = c.getApplicationContext();
            worker = new Thread(() -> work(app), "Y1BookDownload");
            worker.start();
        }
        return j;
    }

    public static synchronized void cancel(Context c, String id) {
        Job j = jobFor(id);
        if (j == null) return;
        j.cancelled = true;
        if (!j.running) jobs.remove(j);
        savePending(c);
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("Y1_SETTINGS", Context.MODE_PRIVATE);
    }

    private static synchronized void savePending(Context c) {
        StringBuilder sb = new StringBuilder();
        for (Job j : jobs) if (!j.cancelled) sb.append(sb.length() > 0 ? "\n" : "").append(j.details.book.id);
        prefs(c).edit().putString("audiobook_downloads", sb.toString()).commit();
    }

    // At start-up: carry on with the books that hadn't finished (once the network answers).
    public static void resumePending(final Context c) {
        final String pending = prefs(c).getString("audiobook_downloads", "");
        if (pending.trim().isEmpty()) return;
        new Thread(() -> {
            for (String id : pending.split("\n")) {
                if (id.trim().isEmpty()) continue;
                try {
                    enqueue(c, details(c, id.trim()));
                } catch (Exception e) {
                    Log.w(TAG, "Can't resume " + id + ": " + e);
                }
            }
        }, "Y1BookResume").start();
    }

    private static synchronized Job next() {
        for (Job j : jobs) if (!j.cancelled) return j;
        worker = null;
        return null;
    }

    private static void work(Context c) {
        WifiManager.WifiLock wifi = null;
        PowerManager.WakeLock wake = null;
        try {
            wifi = ((WifiManager) c.getSystemService(Context.WIFI_SERVICE))
                    .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "InniClassic:books");
            wifi.acquire();
            wake = ((PowerManager) c.getSystemService(Context.POWER_SERVICE))
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "InniClassic:books");
            wake.acquire();
        } catch (Exception e) {
            Log.w(TAG, "No Wi-Fi / wake lock: " + e);
        }
        try {
            Job j;
            while ((j = next()) != null) {
                j.running = true;
                boolean ok = download(c, j);
                j.running = false;
                if (!ok && j.cancelled && !isDownloaded(j.details.book)) deleteBook(j.details.book); // no half books
                synchronized (AudiobookStore.class) {
                    jobs.remove(j);
                    savePending(c);
                }
                final Job done = j;
                final boolean success = ok;
                Log.i(TAG, "Book " + j.details.book.id + (ok ? " downloaded" : " stopped: " + j.error));
                main.post(() -> {
                    Listener l = listener;
                    if (l != null) l.onFinished(done, success);
                });
            }
        } finally {
            if (wifi != null && wifi.isHeld()) wifi.release();
            if (wake != null && wake.isHeld()) wake.release();
        }
    }

    private static long lastNotify;

    private static void progress(final Job j, boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastNotify < 700) return;
        lastNotify = now;
        main.post(() -> {
            Listener l = listener;
            if (l != null) l.onProgress(j);
        });
    }

    private static boolean download(Context c, Job j) {
        if (!j.dir.isDirectory() && !j.dir.mkdirs()) {
            j.error = "Can't create " + j.dir;
            return false;
        }
        byte[] buf = new byte[16 * 1024];
        long before = 0;
        for (Chapter ch : j.details.chapters) {
            File target = new File(j.dir, ch.name);
            if (target.exists() && (ch.size <= 0 || target.length() == ch.size)) {
                before += ch.size;
                j.doneFiles++;
                j.doneBytes = before;
                continue;
            }
            int attempt = 0;
            while (true) {
                if (j.cancelled) return false;
                try {
                    fetchFile(c, j, ch, target, buf, before);
                    break;
                } catch (Exception e) {
                    if (j.cancelled) return false;
                    if (++attempt > 4) {
                        j.error = e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : "");
                        return false;
                    }
                    Log.w(TAG, ch.name + " attempt " + attempt + ": " + e);
                    try {
                        Thread.sleep(attempt * 8000L); // Wi-Fi coming back from power saving
                    } catch (InterruptedException ignored) {
                    }
                }
            }
            before += ch.size > 0 ? ch.size : target.length();
            j.doneFiles++;
            j.doneBytes = before;
            progress(j, true);
        }
        // the cover (the Archive's square tile) and the "complete" marker
        File cover = new File(j.dir, "cover.jpg");
        if (!cover.exists()) {
            try {
                okhttp3.Response r = http(c).newCall(new okhttp3.Request.Builder()
                        .url("https://archive.org/services/img/" + URLEncoder.encode(j.details.book.id, "UTF-8")).build()).execute();
                try {
                    if (r.isSuccessful()) {
                        InputStream in = r.body().byteStream();
                        OutputStream out = new FileOutputStream(cover);
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                        out.close();
                    }
                } finally {
                    r.close();
                }
            } catch (Exception e) {
                Log.w(TAG, "No cover: " + e);
            }
        }
        try {
            OutputStream out = new FileOutputStream(new File(j.dir, MARKER));
            out.write(("https://archive.org/details/" + j.details.book.id + "\n").getBytes("UTF-8"));
            out.close();
        } catch (Exception e) {
            Log.w(TAG, "No marker: " + e);
        }
        return true;
    }

    // Downloads one chapter into <name>.part (continuing a part already there) and renames it when complete.
    private static void fetchFile(Context c, Job j, Chapter ch, File target, byte[] buf, long before) throws Exception {
        File part = new File(target.getPath() + ".part");
        long have = part.exists() ? part.length() : 0;
        String url = "https://archive.org/download/" + URLEncoder.encode(j.details.book.id, "UTF-8") + "/"
                + URLEncoder.encode(ch.name, "UTF-8").replace("+", "%20");
        okhttp3.Request.Builder rb = new okhttp3.Request.Builder().url(url).header("User-Agent", "InniClassic (Innioasis Y1)");
        if (have > 0) rb.header("Range", "bytes=" + have + "-");
        okhttp3.Response r = http(c).newCall(rb.build()).execute();
        try {
            if (r.code() == 416) { // already complete
                have = part.length();
            } else {
                if (!r.isSuccessful()) throw new java.io.IOException("HTTP " + r.code());
                boolean append = have > 0 && r.code() == 206;
                if (!append) have = 0;
                InputStream in = r.body().byteStream();
                OutputStream out = new FileOutputStream(part, append);
                try {
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        if (j.cancelled) throw new java.io.IOException("cancelled");
                        out.write(buf, 0, n);
                        have += n;
                        j.doneBytes = before + have;
                        progress(j, false);
                    }
                } finally {
                    out.close();
                }
            }
        } finally {
            r.close();
        }
        if (ch.size > 0 && part.length() != ch.size)
            throw new java.io.IOException(String.format(Locale.US, "incomplete %d/%d", part.length(), ch.size));
        if (target.exists()) target.delete();
        if (!part.renameTo(target)) throw new java.io.IOException("Can't rename " + part.getName());
    }
}
