package com.themoon.y1.managers;

import android.util.Log;

import com.themoon.y1.models.SongItem;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

// Rule-based ("smart") playlists, iPod-style: each is a JSON file in /storage/sdcard0/Y1_Playlists/Smart/
// and its songs are worked out from the library every time it's opened.
//
//   { "name": "Never Played", "match": "all", "sort": "random", "desc": false, "limit": 100,
//     "rules": [ { "field": "plays", "op": "=", "value": 0 } ] }
//
// Text fields: title, artist, album, albumArtist, genre, composer
//   ops: is, is_not, contains, not_contains, starts_with (case and accents ignored)
// Number fields: year, rating (0-5 stars), plays, last_played_days (never played = no value),
//   added_days (days since the file was copied)
//   ops: =, !=, >, >=, <, <=, between (value: [from, to]), has_value, no_value
// match: "all" (every rule) or "any" (at least one)
// sort: random, title, artist, album, year, rating, plays, last_played, added (+ "desc": true to reverse)
// limit: maximum number of songs (0 = no limit)
public final class SmartPlaylist {
    private static final String TAG = "Y1Smart";
    public static final File DIR = new File("/storage/sdcard0/Y1_Playlists/Smart");

    // What the rules need to know about a song beyond its tags.
    public interface Env {
        int rating(SongItem song);                 // 0-5
        PlayHistory.Stats stats(SongItem song);     // null = never played
        long addedMs(SongItem song);               // file time
        int track(SongItem song);                  // track number, 0 if unknown
        int year(SongItem song);                   // 0 if unknown
    }

    private static final class Rule {
        String field, op, text;
        double a, b;
    }

    public final File file;
    public String name;
    private boolean matchAll = true;
    private final List<Rule> rules = new ArrayList<>();
    private String sort = "title";
    private boolean desc;
    private int limit;

    private SmartPlaylist(File file) {
        this.file = file;
    }

    // Every valid smart playlist in DIR, by name. Broken files are logged and left out.
    public static List<SmartPlaylist> loadAll() {
        List<SmartPlaylist> lists = new ArrayList<>();
        File[] files = DIR.listFiles();
        if (files == null) return lists;
        for (File f : files) {
            if (!f.isFile() || !f.getName().toLowerCase(Locale.ROOT).endsWith(".json")) continue;
            try {
                lists.add(load(f));
            } catch (Exception e) {
                Log.w(TAG, "Skipping " + f.getName() + ": " + e);
            }
        }
        Collections.sort(lists, new Comparator<SmartPlaylist>() {
            @Override
            public int compare(SmartPlaylist a, SmartPlaylist b) {
                return a.name.compareToIgnoreCase(b.name);
            }
        });
        return lists;
    }

    public static SmartPlaylist load(File f) throws Exception {
        Reader reader = new InputStreamReader(new FileInputStream(f), "UTF-8");
        StringBuilder sb = new StringBuilder();
        try {
            char[] buf = new char[4096];
            int n;
            while ((n = reader.read(buf)) > 0) sb.append(buf, 0, n);
        } finally {
            reader.close();
        }
        JSONObject o = new JSONObject(sb.toString());
        SmartPlaylist p = new SmartPlaylist(f);
        String fileName = f.getName();
        p.name = o.optString("name", fileName.substring(0, fileName.length() - 5)).trim();
        p.matchAll = !"any".equalsIgnoreCase(o.optString("match", "all"));
        p.sort = o.optString("sort", "title").toLowerCase(Locale.ROOT);
        p.desc = o.optBoolean("desc", false);
        p.limit = Math.max(0, o.optInt("limit", 0));
        JSONArray rules = o.optJSONArray("rules");
        for (int i = 0; rules != null && i < rules.length(); i++) {
            JSONObject r = rules.getJSONObject(i);
            Rule rule = new Rule();
            rule.field = r.getString("field");
            rule.op = r.optString("op", "is").toLowerCase(Locale.ROOT);
            Object v = r.opt("value");
            if (v instanceof JSONArray) {
                rule.a = ((JSONArray) v).getDouble(0);
                rule.b = ((JSONArray) v).getDouble(1);
            } else if (v instanceof Number) {
                rule.a = rule.b = ((Number) v).doubleValue();
            } else if (v != null) {
                rule.text = fold(String.valueOf(v));
                try {
                    rule.a = rule.b = Double.parseDouble(String.valueOf(v));
                } catch (NumberFormatException ignored) {
                }
            }
            p.rules.add(rule);
        }
        return p;
    }

    public List<SongItem> evaluate(List<SongItem> library, final Env env) {
        long started = System.currentTimeMillis();
        List<SongItem> songs = new ArrayList<>();
        for (SongItem song : library) {
            boolean ok = matchAll;
            for (Rule rule : rules) {
                boolean m = matches(rule, song, env);
                if (matchAll && !m) { ok = false; break; }
                if (!matchAll && m) { ok = true; break; }
            }
            if (rules.isEmpty() || ok) songs.add(song);
        }

        if ("random".equals(sort)) {
            Collections.shuffle(songs);
        } else {
            Collections.sort(songs, new Comparator<SongItem>() {
                @Override
                public int compare(SongItem x, SongItem y) {
                    int c = compareBy(sort, x, y, env);
                    if (desc) c = -c;
                    if (c == 0 && !"title".equals(sort)) {
                        // Ties: keep albums together and in track order.
                        c = fold(x.artist).compareTo(fold(y.artist));
                        if (c == 0) c = fold(x.album).compareTo(fold(y.album));
                        if (c == 0) c = Integer.compare(env.track(x), env.track(y));
                    }
                    return c != 0 ? c : fold(x.title).compareTo(fold(y.title));
                }
            });
        }
        if (limit > 0) while (songs.size() > limit) songs.remove(songs.size() - 1);
        Log.i(TAG, "\"" + name + "\": " + songs.size() + " songs in " + (System.currentTimeMillis() - started) + " ms");
        return songs;
    }

    private static int compareBy(String sort, SongItem x, SongItem y, Env env) {
        switch (sort) {
            case "artist": return fold(x.artist).compareTo(fold(y.artist));
            case "album": return fold(x.album).compareTo(fold(y.album));
            case "year": return Integer.compare(env.year(x), env.year(y));
            case "rating": return Integer.compare(env.rating(x), env.rating(y));
            case "plays": return Integer.compare(plays(env.stats(x)), plays(env.stats(y)));
            case "last_played": return Long.compare(lastPlayed(env.stats(x)), lastPlayed(env.stats(y)));
            case "added": return Long.compare(env.addedMs(x), env.addedMs(y));
            default: return 0; // "title": the tie-break
        }
    }

    private static int plays(PlayHistory.Stats s) {
        return s == null ? 0 : s.count;
    }

    private static long lastPlayed(PlayHistory.Stats s) {
        return s == null ? 0 : s.lastPlayedMs;
    }

    private static boolean matches(Rule r, SongItem song, Env env) {
        String text = null;
        double num = Double.NaN; // NaN = the song has no value for this field
        long now = System.currentTimeMillis();
        switch (r.field) {
            case "title": text = song.title; break;
            case "artist": text = song.artist; break;
            case "album": text = song.album; break;
            case "albumArtist": text = song.albumArtist; break;
            case "genre": text = song.genre; break;
            case "composer": text = song.composer; break;
            case "year": { int y = env.year(song); if (y > 0) num = y; break; }
            case "rating": num = env.rating(song); break;
            case "plays": num = plays(env.stats(song)); break;
            case "last_played_days": {
                PlayHistory.Stats s = env.stats(song);
                if (s != null && s.lastPlayedMs > 0) num = (now - s.lastPlayedMs) / 86400000.0;
                break;
            }
            case "added_days": { long t = env.addedMs(song); if (t > 0) num = (now - t) / 86400000.0; break; }
            default: return false;
        }
        if (text != null) {
            String v = fold(text), q = r.text == null ? "" : r.text;
            switch (r.op) {
                case "is": return v.equals(q);
                case "is_not": return !v.equals(q);
                case "contains": return v.contains(q);
                case "not_contains": return !v.contains(q);
                case "starts_with": return v.startsWith(q);
                default: return false;
            }
        }
        if ("has_value".equals(r.op)) return !Double.isNaN(num);
        if ("no_value".equals(r.op)) return Double.isNaN(num);
        if (Double.isNaN(num)) return false;
        switch (r.op) {
            case "=": return num == r.a;
            case "!=": return num != r.a;
            case ">": return num > r.a;
            case ">=": return num >= r.a;
            case "<": return num < r.a;
            case "<=": return num <= r.a;
            case "between": return num >= Math.min(r.a, r.b) && num <= Math.max(r.a, r.b);
            default: return false;
        }
    }

    private static String fold(String s) {
        if (s == null) return "";
        String lower = s.trim().toLowerCase(Locale.ROOT);
        boolean ascii = true;
        for (int i = 0; i < lower.length() && ascii; i++) ascii = lower.charAt(i) < 128;
        if (ascii) return lower;
        return Normalizer.normalize(lower, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
    }

    // ================= starter lists =================

    public interface Translator {
        String t(String text);
    }

    // On first use, creates the folder with a few example lists (named in the UI language) and a short
    // guide to the format, so lists can be added or edited on a computer.
    public static void createStarterListsIfMissing(Translator tr) {
        if (DIR.exists()) return;
        if (!DIR.mkdirs()) return;
        int thisYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR);
        try {
            write(tr.t("Never Played"), "all", "random", false, 100, rule("plays", "=", 0));
            write(tr.t("Forgotten Favorites"), "all", "plays", true, 100,
                    rule("plays", ">=", 2), rule("last_played_days", ">", 30));
            write(tr.t("Top Rated"), "all", "rating", true, 0, rule("rating", ">=", 4));
            write(tr.t("New Releases"), "all", "year", true, 0, rule("year", ">=", thisYear - 1));
            JSONObject decade = rule("year", "between", 0);
            decade.put("value", new JSONArray().put(2000).put(2009));
            write(tr.t("2000s Music"), "all", "random", false, 100, decade);
            writeGuide();
        } catch (Exception e) {
            Log.w(TAG, "Couldn't create the starter lists", e);
        }
    }

    private static JSONObject rule(String field, String op, double value) throws Exception {
        JSONObject r = new JSONObject();
        r.put("field", field);
        r.put("op", op);
        if (value == Math.floor(value)) r.put("value", (long) value);
        else r.put("value", value);
        return r;
    }

    private static void write(String name, String match, String sort, boolean desc, int limit, JSONObject... rules)
            throws Exception {
        JSONObject o = new JSONObject();
        o.put("name", name);
        o.put("match", match);
        JSONArray arr = new JSONArray();
        for (JSONObject r : rules) arr.put(r);
        o.put("rules", arr);
        o.put("sort", sort);
        o.put("desc", desc);
        o.put("limit", limit);
        String fileName = name.replaceAll("[\\\\/:*?\"<>|]", "_") + ".json";
        writeText(new File(DIR, fileName), o.toString(2));
    }

    private static void writeGuide() throws Exception {
        writeText(new File(DIR, "README.txt"),
                "Smart playlists / Listas inteligentes\n"
                        + "=====================================\n\n"
                        + "Each .json file in this folder is a list worked out from your library every time\n"
                        + "you open it (Music > Playlists). Copy one to make your own.\n"
                        + "Cada archivo .json de esta carpeta es una lista que se calcula con tu biblioteca\n"
                        + "cada vez que la abres (Música > Listas). Copia uno para crear la tuya.\n\n"
                        + "{ \"name\": \"Rock 2010s\", \"match\": \"all\", \"sort\": \"random\", \"desc\": false, \"limit\": 100,\n"
                        + "  \"rules\": [ { \"field\": \"genre\", \"op\": \"contains\", \"value\": \"rock\" },\n"
                        + "             { \"field\": \"year\", \"op\": \"between\", \"value\": [2010, 2019] } ] }\n\n"
                        + "Text fields: title, artist, album, albumArtist, genre, composer\n"
                        + "  op: is, is_not, contains, not_contains, starts_with (ignores case and accents)\n"
                        + "Number fields: year, rating (0-5), plays, last_played_days, added_days\n"
                        + "  op: =, !=, >, >=, <, <=, between ([from, to]), has_value, no_value\n"
                        + "  (a never-played song has no last_played_days: use plays = 0 for those)\n"
                        + "match: all (every rule) / any (at least one)\n"
                        + "sort: random, title, artist, album, year, rating, plays, last_played, added\n"
                        + "desc: true = reverse order; limit: maximum songs (0 = all)\n");
    }

    private static void writeText(File f, String text) throws Exception {
        Writer w = new OutputStreamWriter(new FileOutputStream(f), "UTF-8");
        try {
            w.write(text);
        } finally {
            w.close();
        }
    }
}
