package com.themoon.y1.managers;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

// 🚀 [Podcasts] Podcasts without typing: Apple's charts (top and by category, per country) and search,
// RSS feeds read and cached on the card (so new episodes show up offline), subscriptions in
// Podcasts/subscriptions.txt ("Name|RSS", the format InniClassic already used), episodes downloaded to
// Podcasts/<Show>/<Title>.mp3 (also the existing names) in the background with Wi-Fi kept awake and cut
// downloads resumed, played state, automatic download of new episodes, clean-up of played ones and OPML
// import / export. All HTTPS is verified (LyricsFetcher's client).
public final class PodcastStore {
    private static final String TAG = "Y1Podcasts";
    public static final File ROOT = new File("/storage/sdcard0/Podcasts");
    private static final File SUBS = new File(ROOT, "subscriptions.txt");
    private static final int MAX_EPISODES = 100;

    public static final class Show {
        public String name = "", author = "", feedUrl = "", artUrl = "";
        public int episodes;
    }

    public static final class Episode {
        public String show = "", title = "", audioUrl = "", description = "", artUrl = "";
        public long pubTime, size;
        public int seconds;
    }

    public static final class Feed {
        public Show show;
        public final List<Episode> episodes = new ArrayList<>();
        public long fetched;
    }

    public static final class Job {
        public final Episode episode;
        public final File file;
        public volatile long done, total;
        public volatile boolean running, cancelled;
        public volatile String error;

        Job(Episode e) {
            episode = e;
            file = episodeFile(e.show, e.title);
            total = e.size;
        }

        public int percent() {
            return total > 0 ? (int) Math.min(100, done * 100 / total) : 0;
        }
    }

    public interface Listener {
        void onProgress(Job job);

        void onFinished(Job job, boolean ok);

        void onRefreshed(int checked, int total, boolean finished);
    }

    private static okhttp3.OkHttpClient http;
    private static volatile Listener listener;
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final List<Job> jobs = new ArrayList<>();
    private static Thread worker;
    private static volatile boolean refreshing;

    private PodcastStore() {
    }

    public static void setListener(Listener l) {
        listener = l;
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("Y1_SETTINGS", Context.MODE_PRIVATE);
    }

    private static synchronized okhttp3.OkHttpClient http(Context c) throws Exception {
        if (http == null)
            http = LyricsFetcher.client(c).newBuilder().readTimeout(45, TimeUnit.SECONDS)
                    .followRedirects(true).followSslRedirects(true).build();
        return http;
    }

    private static okhttp3.Response open(Context c, String url, long from) throws Exception {
        okhttp3.Request.Builder rb = new okhttp3.Request.Builder().url(url)
                .header("User-Agent", "NewOasis/1.0 (Innioasis Y1; podcast player)");
        if (from > 0) rb.header("Range", "bytes=" + from + "-");
        return http(c).newCall(rb.build()).execute();
    }

    private static String get(Context c, String url) throws Exception {
        okhttp3.Response r = open(c, url, 0);
        try {
            if (!r.isSuccessful()) throw new java.io.IOException("HTTP " + r.code());
            return r.body().string();
        } finally {
            r.close();
        }
    }

    public static boolean wifiConnected(Context c) {
        try {
            NetworkInfo ni = ((ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE))
                    .getNetworkInfo(ConnectivityManager.TYPE_WIFI);
            return ni != null && ni.isConnected();
        } catch (Exception e) {
            return false;
        }
    }

    // ---------------------------------------------------------------- names (as InniClassic named them)

    public static String safe(String s) {
        return s == null ? "" : s.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    public static File showDir(String show) {
        return new File(ROOT, safe(show));
    }

    public static File episodeFile(String show, String title) {
        return new File(showDir(show), safe(title) + ".mp3");
    }

    public static String streamKey(Episode e) {
        return "/PODCAST_STREAM/" + safe(e.show) + "/" + safe(e.title) + ".mp3";
    }

    private static String stateKey(Episode e) {
        return safe(e.show) + "/" + safe(e.title);
    }

    // ---------------------------------------------------------------- subscriptions

    public static synchronized List<Show> subscriptions(Context c) {
        List<Show> out = new ArrayList<>();
        if (!SUBS.exists()) return out;
        try {
            BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(SUBS), "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] parts = line.split("\\|");
                if (parts.length < 2 || !parts[1].trim().startsWith("http")) continue;
                Show s = new Show();
                s.name = parts[0].trim();
                s.feedUrl = parts[1].trim();
                s.artUrl = prefs(c).getString("pod_art_" + s.feedUrl, "");
                out.add(s);
            }
            br.close();
        } catch (Exception e) {
            Log.w(TAG, "Can't read subscriptions: " + e);
        }
        return out;
    }

    public static boolean isSubscribed(Context c, String feedUrl) {
        for (Show s : subscriptions(c)) if (s.feedUrl.equals(feedUrl)) return true;
        return false;
    }

    private static synchronized void writeSubscriptions(List<Show> shows) throws Exception {
        if (!ROOT.isDirectory()) ROOT.mkdirs();
        Writer w = new OutputStreamWriter(new FileOutputStream(SUBS), "UTF-8");
        w.write("# New Oasis podcasts: Name|RSS address, one per line\n");
        for (Show s : shows) w.write(s.name.replace("|", "/") + "|" + s.feedUrl + "\n");
        w.close();
    }

    public static synchronized boolean subscribe(final Context c, final Show s) {
        List<Show> shows = subscriptions(c);
        for (Show x : shows) if (x.feedUrl.equals(s.feedUrl)) return false;
        shows.add(s);
        try {
            writeSubscriptions(shows);
        } catch (Exception e) {
            Log.w(TAG, "Can't save subscriptions: " + e);
            return false;
        }
        if (!s.artUrl.isEmpty()) prefs(c).edit().putString("pod_art_" + s.feedUrl, s.artUrl).commit();
        // the newest episode now is "seen": automatic downloads only take what comes out after this
        prefs(c).edit().putLong("pod_seen_" + s.feedUrl, System.currentTimeMillis()).commit();
        new Thread(() -> saveCover(c, s.name, s.artUrl), "Y1PodCover").start();
        return true;
    }

    public static synchronized void unsubscribe(Context c, String feedUrl) {
        List<Show> shows = subscriptions(c);
        for (int i = shows.size() - 1; i >= 0; i--) if (shows.get(i).feedUrl.equals(feedUrl)) shows.remove(i);
        try {
            writeSubscriptions(shows);
        } catch (Exception e) {
            Log.w(TAG, "Can't save subscriptions: " + e);
        }
    }

    // Podcasts/<Show>/cover.jpg, shown by the player and the lists.
    private static void saveCover(Context c, String show, String artUrl) {
        if (artUrl == null || artUrl.isEmpty()) return;
        File cover = new File(showDir(show), "cover.jpg");
        if (cover.exists()) return;
        try {
            showDir(show).mkdirs();
            okhttp3.Response r = open(c, artUrl.replace("100x100bb", "300x300bb"), 0);
            try {
                if (!r.isSuccessful()) return;
                InputStream in = r.body().byteStream();
                OutputStream out = new FileOutputStream(cover);
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                out.close();
            } finally {
                r.close();
            }
        } catch (Exception e) {
            Log.w(TAG, "No cover for " + show + ": " + e);
        }
    }

    // ---------------------------------------------------------------- Apple's directory

    // The chart of a country (es, mx, us...), all podcasts (genre 0) or one category.
    public static List<Show> chart(Context c, String country, int genre) throws Exception {
        String url = "https://itunes.apple.com/" + country + "/rss/toppodcasts/limit=50"
                + (genre > 0 ? "/genre=" + genre : "") + "/json";
        JSONObject feed = new JSONObject(get(c, url)).getJSONObject("feed");
        Object entries = feed.opt("entry");
        JSONArray list = entries instanceof JSONArray ? (JSONArray) entries : new JSONArray();
        if (entries instanceof JSONObject) list.put(entries);
        List<Show> out = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < list.length(); i++) {
            JSONObject e = list.getJSONObject(i);
            Show s = new Show();
            s.name = e.getJSONObject("im:name").optString("label");
            s.author = e.optJSONObject("im:artist") != null ? e.getJSONObject("im:artist").optString("label") : "";
            JSONArray imgs = e.optJSONArray("im:image");
            if (imgs != null && imgs.length() > 0) s.artUrl = imgs.getJSONObject(imgs.length() - 1).optString("label");
            out.add(s);
            ids.add(e.getJSONObject("id").getJSONObject("attributes").optString("im:id"));
        }
        // the RSS addresses, all in one lookup
        if (!ids.isEmpty()) {
            JSONArray results = new JSONObject(get(c, "https://itunes.apple.com/lookup?country=" + country + "&id="
                    + android.text.TextUtils.join(",", ids))).optJSONArray("results");
            java.util.Map<String, JSONObject> byId = new java.util.HashMap<>();
            if (results != null)
                for (int i = 0; i < results.length(); i++) {
                    JSONObject r = results.getJSONObject(i);
                    byId.put(String.valueOf(r.optLong("collectionId")), r);
                }
            for (int i = 0; i < out.size(); i++) {
                JSONObject r = byId.get(ids.get(i));
                if (r == null) continue;
                out.get(i).feedUrl = r.optString("feedUrl");
                out.get(i).episodes = r.optInt("trackCount");
                if (r.has("artworkUrl100")) out.get(i).artUrl = r.optString("artworkUrl100");
            }
        }
        List<Show> playable = new ArrayList<>();
        for (Show s : out) if (!s.feedUrl.isEmpty()) playable.add(s);
        Log.i(TAG, "Chart " + country + "/" + genre + ": " + playable.size());
        return playable;
    }

    public static List<Show> search(Context c, String term, String country) throws Exception {
        String url = "https://itunes.apple.com/search?media=podcast&entity=podcast&limit=40&country=" + country
                + "&term=" + URLEncoder.encode(term, "UTF-8");
        JSONArray results = new JSONObject(get(c, url)).optJSONArray("results");
        List<Show> out = new ArrayList<>();
        if (results != null)
            for (int i = 0; i < results.length(); i++) {
                JSONObject r = results.getJSONObject(i);
                Show s = new Show();
                s.name = r.optString("collectionName");
                s.author = r.optString("artistName");
                s.feedUrl = r.optString("feedUrl");
                s.artUrl = r.optString("artworkUrl100");
                s.episodes = r.optInt("trackCount");
                if (!s.feedUrl.isEmpty() && !s.name.isEmpty()) out.add(s);
            }
        Log.i(TAG, "Search '" + term + "': " + out.size());
        return out;
    }

    // ---------------------------------------------------------------- feeds

    private static File cacheFile(Show s) {
        return new File(showDir(s.name), ".feed.json");
    }

    // Reads the RSS feed (newest episodes first) and keeps a copy on the card for offline use.
    public static Feed fetch(Context c, Show show) throws Exception {
        okhttp3.Response r = open(c, show.feedUrl, 0);
        Feed f;
        try {
            if (!r.isSuccessful()) throw new java.io.IOException("HTTP " + r.code());
            f = parse(r.body().byteStream(), show);
        } finally {
            r.close();
        }
        f.fetched = System.currentTimeMillis();
        if (!f.show.artUrl.isEmpty() && prefs(c).getString("pod_art_" + show.feedUrl, "").isEmpty())
            prefs(c).edit().putString("pod_art_" + show.feedUrl, f.show.artUrl).commit();
        if (isSubscribed(c, show.feedUrl)) {
            saveCache(f);
            saveCover(c, f.show.name, f.show.artUrl);
        }
        return f;
    }

    private static long parseDate(String s) {
        if (s == null) return 0;
        s = s.trim();
        for (String pattern : new String[] { "EEE, d MMM yyyy HH:mm:ss Z", "EEE, d MMM yyyy HH:mm:ss zzz",
                "d MMM yyyy HH:mm:ss Z", "EEE, d MMM yyyy HH:mm Z", "yyyy-MM-dd'T'HH:mm:ssZ", "EEE, d MMM yyyy" }) {
            try {
                Date d = new SimpleDateFormat(pattern, Locale.US).parse(s);
                if (d != null) return d.getTime();
            } catch (Exception ignored) {
            }
        }
        return 0;
    }

    private static int parseDuration(String s) {
        if (s == null || s.trim().isEmpty()) return 0;
        try {
            int total = 0;
            for (String part : s.trim().split(":")) total = total * 60 + (int) Float.parseFloat(part);
            return total;
        } catch (Exception e) {
            return 0;
        }
    }

    private static String clean(String html) {
        if (html == null) return "";
        String t = html.replaceAll("(?s)<[^>]*>", " ").replace("&nbsp;", " ").replace("&amp;", "&")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")
                .replaceAll("\\s+", " ").trim();
        return t.length() > 700 ? t.substring(0, 700) + "…" : t;
    }

    static Feed parse(InputStream in, Show show) throws Exception {
        XmlPullParser p = XmlPullParserFactory.newInstance().newPullParser();
        p.setInput(in, null);
        Feed f = new Feed();
        f.show = new Show();
        f.show.feedUrl = show.feedUrl;
        f.show.name = show.name;
        f.show.artUrl = show.artUrl;
        f.show.author = show.author;
        String channelTitle = "";
        Episode e = null;
        int depth = 0, imageDepth = -1;
        for (int ev = p.getEventType(); ev != XmlPullParser.END_DOCUMENT && f.episodes.size() < MAX_EPISODES; ev = p.next()) {
            if (ev == XmlPullParser.START_TAG) {
                depth++;
                String n = p.getName();
                String ln = n.contains(":") ? n.substring(n.indexOf(':') + 1) : n;
                if ("item".equals(n) || "entry".equals(n)) {
                    e = new Episode();
                } else if (e == null) { // channel
                    if ("title".equals(n) && channelTitle.isEmpty()) channelTitle = p.nextText().trim();
                    else if ("image".equals(n) && n.equals(ln)) imageDepth = depth;
                    else if ("url".equals(n) && imageDepth > 0 && f.show.artUrl.isEmpty()) f.show.artUrl = p.nextText().trim();
                    else if ("image".equals(ln) && !n.equals(ln)) {
                        String href = p.getAttributeValue(null, "href");
                        if (href != null && !href.isEmpty()) f.show.artUrl = href;
                    } else if ("author".equals(ln) && f.show.author.isEmpty()) f.show.author = p.nextText().trim();
                } else {
                    if ("title".equals(n)) e.title = p.nextText().trim();
                    else if ("enclosure".equals(n)) {
                        String url = p.getAttributeValue(null, "url");
                        String type = p.getAttributeValue(null, "type");
                        if (url != null && (type == null || type.startsWith("audio") || e.audioUrl.isEmpty())) {
                            e.audioUrl = url.trim();
                            try {
                                e.size = Long.parseLong(p.getAttributeValue(null, "length").trim());
                            } catch (Exception ignored) {
                            }
                        }
                    } else if ("pubDate".equals(n) || "published".equals(n)) e.pubTime = parseDate(p.nextText());
                    else if ("duration".equals(ln)) e.seconds = parseDuration(p.nextText());
                    else if (("description".equals(n) || "summary".equals(ln) || "encoded".equals(ln)) && e.description.isEmpty())
                        e.description = clean(p.nextText());
                    else if ("image".equals(ln) && !n.equals(ln)) {
                        String href = p.getAttributeValue(null, "href");
                        if (href != null) e.artUrl = href;
                    }
                }
                // nextText() moved to the END_TAG
                if (p.getEventType() == XmlPullParser.END_TAG) depth--;
            } else if (ev == XmlPullParser.END_TAG) {
                String n = p.getName();
                if (("item".equals(n) || "entry".equals(n)) && e != null) {
                    if (!e.audioUrl.isEmpty() && !e.title.isEmpty()) f.episodes.add(e);
                    e = null;
                } else if ("image".equals(n) && depth == imageDepth) imageDepth = -1;
                depth--;
            }
        }
        if (f.show.name.isEmpty()) f.show.name = channelTitle;
        for (Episode x : f.episodes) {
            x.show = f.show.name;
            if (x.artUrl.isEmpty()) x.artUrl = f.show.artUrl;
        }
        // newest first, whatever order the feed uses
        Collections.sort(f.episodes, (a, b) -> Long.compare(b.pubTime, a.pubTime));
        f.show.episodes = f.episodes.size();
        return f;
    }

    private static void saveCache(Feed f) {
        try {
            JSONObject o = new JSONObject();
            o.put("name", f.show.name).put("author", f.show.author).put("feed", f.show.feedUrl)
                    .put("art", f.show.artUrl).put("fetched", f.fetched);
            JSONArray eps = new JSONArray();
            for (Episode e : f.episodes)
                eps.put(new JSONObject().put("t", e.title).put("u", e.audioUrl).put("p", e.pubTime)
                        .put("s", e.seconds).put("z", e.size).put("d", e.description).put("a", e.artUrl));
            o.put("episodes", eps);
            showDir(f.show.name).mkdirs();
            Writer w = new OutputStreamWriter(new FileOutputStream(cacheFile(f.show)), "UTF-8");
            w.write(o.toString());
            w.close();
        } catch (Exception e) {
            Log.w(TAG, "Can't cache " + f.show.name + ": " + e);
        }
    }

    // The copy kept by the last check, or null.
    public static Feed cached(Show show) {
        File file = cacheFile(show);
        if (!file.exists()) return null;
        try {
            BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line);
            br.close();
            JSONObject o = new JSONObject(sb.toString());
            Feed f = new Feed();
            f.show = new Show();
            f.show.name = show.name;
            f.show.feedUrl = show.feedUrl;
            f.show.author = o.optString("author");
            f.show.artUrl = o.optString("art");
            f.fetched = o.optLong("fetched");
            JSONArray eps = o.optJSONArray("episodes");
            if (eps != null)
                for (int i = 0; i < eps.length(); i++) {
                    JSONObject j = eps.getJSONObject(i);
                    Episode e = new Episode();
                    e.show = show.name;
                    e.title = j.optString("t");
                    e.audioUrl = j.optString("u");
                    e.pubTime = j.optLong("p");
                    e.seconds = j.optInt("s");
                    e.size = j.optLong("z");
                    e.description = j.optString("d");
                    e.artUrl = j.optString("a");
                    f.episodes.add(e);
                }
            f.show.episodes = f.episodes.size();
            return f;
        } catch (Exception e) {
            Log.w(TAG, "Bad cache for " + show.name + ": " + e);
            return null;
        }
    }

    // ---------------------------------------------------------------- episode state

    public static boolean isDownloaded(Episode e) {
        File f = episodeFile(e.show, e.title);
        return f.exists() && f.length() > 0;
    }

    // Saved position (ms) of a downloaded or streamed episode, and its length (ms) if known.
    public static int position(SharedPreferences prefs, Episode e) {
        int p = prefs.getInt("book_pos_" + episodeFile(e.show, e.title).getAbsolutePath(), 0);
        return p > 0 ? p : prefs.getInt("book_pos_" + streamKey(e), 0);
    }

    public static int duration(SharedPreferences prefs, Episode e) {
        int d = prefs.getInt("book_dur_" + episodeFile(e.show, e.title).getAbsolutePath(), 0);
        if (d <= 0) d = prefs.getInt("book_dur_" + streamKey(e), 0);
        return d > 0 ? d : e.seconds * 1000;
    }

    public static boolean isPlayed(SharedPreferences prefs, Episode e) {
        if (prefs.getBoolean("pod_played_" + stateKey(e), false)) return true;
        int d = duration(prefs, e), p = position(prefs, e);
        return d > 0 && p > 0 && p >= d - 30000;
    }

    public static void setPlayed(Context c, Episode e, boolean played) {
        SharedPreferences.Editor ed = prefs(c).edit();
        if (played) {
            ed.putBoolean("pod_played_" + stateKey(e), true).putLong("pod_played_at_" + stateKey(e), System.currentTimeMillis());
        } else {
            ed.remove("pod_played_" + stateKey(e)).remove("pod_played_at_" + stateKey(e))
                    .remove("book_pos_" + episodeFile(e.show, e.title).getAbsolutePath()).remove("book_pos_" + streamKey(e));
        }
        ed.commit();
    }

    // Called by the player when an episode (downloaded or streamed) plays to the end.
    public static void markFinished(Context c, String path) {
        File f = new File(path);
        String name = f.getName();
        if (name.toLowerCase(Locale.ROOT).endsWith(".mp3")) name = name.substring(0, name.length() - 4);
        String key = f.getParentFile().getName() + "/" + name;
        prefs(c).edit().putBoolean("pod_played_" + key, true).putLong("pod_played_at_" + key, System.currentTimeMillis()).commit();
        Log.i(TAG, "Played: " + key);
    }

    public static boolean deleteDownload(Episode e) {
        return episodeFile(e.show, e.title).delete();
    }

    // Unplayed episodes of the subscriptions from the last 30 days, newest first (from the cached feeds).
    public static List<Episode> newEpisodes(Context c, int max) {
        List<Episode> out = new ArrayList<>();
        long since = System.currentTimeMillis() - 30L * 86400000L;
        SharedPreferences prefs = prefs(c);
        for (Show s : subscriptions(c)) {
            Feed f = cached(s);
            if (f == null) continue;
            for (Episode e : f.episodes) if (e.pubTime >= since && !isPlayed(prefs, e)) out.add(e);
        }
        Collections.sort(out, (a, b) -> Long.compare(b.pubTime, a.pubTime));
        return out.size() > max ? new ArrayList<>(out.subList(0, max)) : out;
    }

    // Downloaded episodes of every show folder, newest download first; details from the cached feeds.
    public static List<Episode> downloaded(Context c) {
        List<Episode> out = new ArrayList<>();
        final java.util.Map<Episode, Long> when = new java.util.HashMap<>();
        java.util.Map<String, Show> shows = new java.util.HashMap<>();
        for (Show s : subscriptions(c)) shows.put(safe(s.name), s);
        File[] dirs = ROOT.listFiles();
        if (dirs == null) return out;
        for (File dir : dirs) {
            if (!dir.isDirectory()) continue;
            File[] files = dir.listFiles();
            if (files == null) continue;
            Show show = shows.get(dir.getName());
            Feed feed = show != null ? cached(show) : null;
            for (File file : files) {
                if (!file.isFile() || !file.getName().toLowerCase(Locale.ROOT).endsWith(".mp3")) continue;
                Episode e = null;
                if (feed != null)
                    for (Episode x : feed.episodes) if (episodeFile(x.show, x.title).getName().equals(file.getName())) e = x;
                if (e == null) {
                    e = new Episode();
                    e.show = show != null ? show.name : dir.getName();
                    e.title = file.getName().substring(0, file.getName().length() - 4);
                    e.pubTime = file.lastModified();
                    e.size = file.length();
                }
                out.add(e);
                when.put(e, file.lastModified());
            }
        }
        Collections.sort(out, (a, b) -> Long.compare(when.get(b), when.get(a)));
        return out;
    }

    // ---------------------------------------------------------------- checking for new episodes

    public static boolean isRefreshing() {
        return refreshing;
    }

    // Reads every subscription's feed; then downloads what came out since the last check (if set) and
    // deletes played downloads (if set). Runs in the background.
    public static void refreshAll(final Context c) {
        if (refreshing) return;
        refreshing = true;
        new Thread(() -> {
            List<Show> shows = subscriptions(c);
            int auto = prefs(c).getInt("pod_auto_download", 0);
            int done = 0;
            for (Show s : shows) {
                try {
                    Feed f = fetch(c, s);
                    long seen = prefs(c).getLong("pod_seen_" + s.feedUrl, 0);
                    if (auto > 0 && seen > 0) {
                        int queued = 0;
                        for (Episode e : f.episodes) {
                            if (queued >= auto || e.pubTime <= seen) break;
                            if (!isDownloaded(e) && !isPlayed(prefs(c), e)) {
                                enqueue(c, e);
                                queued++;
                            }
                        }
                    }
                    long newest = f.episodes.isEmpty() ? 0 : f.episodes.get(0).pubTime;
                    if (newest > seen || seen == 0)
                        prefs(c).edit().putLong("pod_seen_" + s.feedUrl, Math.max(newest, System.currentTimeMillis() - 60000)).commit();
                } catch (Exception e) {
                    Log.w(TAG, "Can't check " + s.name + ": " + e);
                }
                final int n = ++done;
                main.post(() -> {
                    Listener l = listener;
                    if (l != null) l.onRefreshed(n, shows.size(), false);
                });
            }
            cleanUp(c);
            prefs(c).edit().putLong("pod_last_check", System.currentTimeMillis()).commit();
            refreshing = false;
            final int total = shows.size();
            main.post(() -> {
                Listener l = listener;
                if (l != null) l.onRefreshed(total, total, true);
            });
        }, "Y1PodRefresh").start();
    }

    // Deletes played downloads after the chosen number of days (Podcast Settings; -1 = never).
    public static int cleanUp(Context c) {
        int days = prefs(c).getInt("pod_cleanup_days", -1);
        if (days < 0) return 0;
        int deleted = 0;
        long now = System.currentTimeMillis();
        SharedPreferences prefs = prefs(c);
        for (Episode e : downloaded(c)) {
            if (!isPlayed(prefs, e)) continue;
            long at = prefs.getLong("pod_played_at_" + stateKey(e), 0);
            if (at == 0) at = episodeFile(e.show, e.title).lastModified();
            if (now - at >= days * 86400000L && jobFor(e.audioUrl) == null && deleteDownload(e)) deleted++;
        }
        if (deleted > 0) Log.i(TAG, "Deleted " + deleted + " played episodes");
        return deleted;
    }

    // ---------------------------------------------------------------- OPML

    public static List<File> opmlFiles() {
        List<File> out = new ArrayList<>();
        for (File dir : new File[] { ROOT, new File("/storage/sdcard0") }) {
            File[] files = dir.listFiles();
            if (files != null)
                for (File f : files) if (f.isFile() && f.getName().toLowerCase(Locale.ROOT).endsWith(".opml")) out.add(f);
        }
        return out;
    }

    // Subscribes to every feed in the file; returns how many were new.
    public static int importOpml(Context c, File file) throws Exception {
        XmlPullParser p = XmlPullParserFactory.newInstance().newPullParser();
        InputStream in = new FileInputStream(file);
        int added = 0;
        try {
            p.setInput(in, null);
            for (int ev = p.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = p.next()) {
                if (ev != XmlPullParser.START_TAG || !"outline".equalsIgnoreCase(p.getName())) continue;
                String url = p.getAttributeValue(null, "xmlUrl");
                if (url == null || !url.startsWith("http")) continue;
                Show s = new Show();
                s.feedUrl = url.trim();
                String name = p.getAttributeValue(null, "text");
                if (name == null || name.isEmpty()) name = p.getAttributeValue(null, "title");
                s.name = name != null && !name.trim().isEmpty() ? name.trim() : s.feedUrl;
                if (subscribe(c, s)) added++;
            }
        } finally {
            in.close();
        }
        Log.i(TAG, "Imported " + added + " from " + file.getName());
        return added;
    }

    public static File exportOpml(Context c) throws Exception {
        ROOT.mkdirs();
        File out = new File(ROOT, "New Oasis podcasts.opml");
        Writer w = new OutputStreamWriter(new FileOutputStream(out), "UTF-8");
        w.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<opml version=\"2.0\">\n<head><title>New Oasis podcasts</title></head>\n<body>\n");
        for (Show s : subscriptions(c))
            w.write("  <outline type=\"rss\" text=\"" + xml(s.name) + "\" title=\"" + xml(s.name) + "\" xmlUrl=\"" + xml(s.feedUrl) + "\"/>\n");
        w.write("</body>\n</opml>\n");
        w.close();
        return out;
    }

    private static String xml(String s) {
        return s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ---------------------------------------------------------------- downloads

    public static synchronized List<Job> jobs() {
        return new ArrayList<>(jobs);
    }

    public static synchronized Job jobFor(String audioUrl) {
        if (audioUrl == null || audioUrl.isEmpty()) return null;
        for (Job j : jobs) if (j.episode.audioUrl.equals(audioUrl)) return j;
        return null;
    }

    public static synchronized Job enqueue(Context c, Episode e) {
        Job existing = jobFor(e.audioUrl);
        if (existing != null) return existing;
        Job j = new Job(e);
        jobs.add(j);
        savePending(c);
        if (worker == null) {
            final Context app = c.getApplicationContext();
            worker = new Thread(() -> work(app), "Y1PodDownload");
            worker.start();
        }
        return j;
    }

    public static synchronized void cancel(Context c, String audioUrl) {
        Job j = jobFor(audioUrl);
        if (j == null) return;
        j.cancelled = true;
        if (!j.running) jobs.remove(j);
        savePending(c);
    }

    private static synchronized void savePending(Context c) {
        JSONArray a = new JSONArray();
        try {
            for (Job j : jobs)
                if (!j.cancelled)
                    a.put(new JSONObject().put("show", j.episode.show).put("title", j.episode.title)
                            .put("url", j.episode.audioUrl).put("size", j.episode.size));
        } catch (Exception ignored) {
        }
        prefs(c).edit().putString("podcast_downloads", a.toString()).commit();
    }

    public static void resumePending(Context c) {
        try {
            JSONArray a = new JSONArray(prefs(c).getString("podcast_downloads", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Episode e = new Episode();
                e.show = o.optString("show");
                e.title = o.optString("title");
                e.audioUrl = o.optString("url");
                e.size = o.optLong("size");
                if (!e.audioUrl.isEmpty()) enqueue(c, e);
            }
        } catch (Exception e) {
            Log.w(TAG, "Can't resume downloads: " + e);
        }
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
                    .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "InniClassic:podcasts");
            wifi.acquire();
            wake = ((PowerManager) c.getSystemService(Context.POWER_SERVICE))
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "InniClassic:podcasts");
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
                if (!ok && j.cancelled) new File(j.file.getPath() + ".part").delete();
                synchronized (PodcastStore.class) {
                    jobs.remove(j);
                    savePending(c);
                }
                final Job done = j;
                final boolean success = ok;
                Log.i(TAG, "Episode " + j.episode.title + (ok ? " downloaded" : " stopped: " + j.error));
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

    private static boolean download(Context c, Job j) {
        File dir = j.file.getParentFile();
        if (!dir.isDirectory() && !dir.mkdirs()) {
            j.error = "Can't create " + dir;
            return false;
        }
        File part = new File(j.file.getPath() + ".part");
        byte[] buf = new byte[16 * 1024];
        for (int attempt = 0; ; attempt++) {
            if (j.cancelled) return false;
            try {
                long have = part.exists() ? part.length() : 0;
                okhttp3.Response r = open(c, j.episode.audioUrl, have);
                try {
                    if (r.code() != 416) {
                        if (!r.isSuccessful()) throw new java.io.IOException("HTTP " + r.code());
                        boolean append = have > 0 && r.code() == 206;
                        if (!append) have = 0;
                        long len = r.body().contentLength();
                        if (len > 0) j.total = have + len;
                        InputStream in = r.body().byteStream();
                        OutputStream out = new FileOutputStream(part, append);
                        try {
                            int n;
                            while ((n = in.read(buf)) > 0) {
                                if (j.cancelled) return false;
                                out.write(buf, 0, n);
                                have += n;
                                j.done = have;
                                long now = System.currentTimeMillis();
                                if (now - lastNotify > 700) {
                                    lastNotify = now;
                                    final Job job = j;
                                    main.post(() -> {
                                        Listener l = listener;
                                        if (l != null) l.onProgress(job);
                                    });
                                }
                            }
                        } finally {
                            out.close();
                        }
                    }
                } finally {
                    r.close();
                }
                if (j.total > 0 && part.length() < j.total)
                    throw new java.io.IOException("incomplete " + part.length() + "/" + j.total);
                if (j.file.exists()) j.file.delete();
                if (!part.renameTo(j.file)) throw new java.io.IOException("Can't rename " + part.getName());
                return true;
            } catch (Exception e) {
                if (j.cancelled) return false;
                if (attempt >= 4) {
                    j.error = e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : "");
                    return false;
                }
                Log.w(TAG, j.episode.title + " attempt " + (attempt + 1) + ": " + e);
                try {
                    Thread.sleep((attempt + 1) * 8000L);
                } catch (InterruptedException ignored) {
                }
            }
        }
    }
}
