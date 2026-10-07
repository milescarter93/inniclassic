package com.themoon.y1.managers;

import android.util.Log;

import com.themoon.y1.models.SongItem;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

// Play counts and last-played times from /storage/sdcard0/.scrobbler.log (written by LastFmScrobbler for
// every track played past half its length or 4 minutes). The log has artist / album / title but no file
// path, so songs are matched to the library by those tags: artist+album+title first, then artist+title
// (same song tagged with another album name). Parsed again only when the file changes.
public final class PlayHistory {
    private static final String TAG = "Y1History";
    private static final File LOG_FILE = new File("/storage/sdcard0/.scrobbler.log");

    public static final class Stats {
        public int count;
        public long lastPlayedMs;

        void add(long playedMs) {
            count++;
            if (playedMs > lastPlayedMs) lastPlayedMs = playedMs;
        }
    }

    private static long loadedStamp = -1;
    private static Map<String, Stats> byFullKey = new HashMap<>();
    private static Map<String, Stats> byArtistTitle = new HashMap<>();
    // Each song's two keys, kept while the SongItem lives: folding ~3000 songs' tags took ~200 ms per list.
    private static final java.util.WeakHashMap<SongItem, String[]> songKeys = new java.util.WeakHashMap<>();

    private static String fold(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    private static String fullKey(String artist, String album, String title) {
        return fold(artist) + '\u0001' + fold(album) + '\u0001' + fold(title);
    }

    private static String artistTitleKey(String artist, String title) {
        return fold(artist) + '\u0001' + fold(title);
    }

    private static synchronized void reloadIfChanged() {
        long stamp = LOG_FILE.exists() ? LOG_FILE.lastModified() * 31 + LOG_FILE.length() : 0;
        if (stamp == loadedStamp) return;
        long started = System.currentTimeMillis();
        Map<String, Stats> full = new HashMap<>();
        Map<String, Stats> artistTitle = new HashMap<>();
        int plays = 0;
        if (stamp != 0) {
            BufferedReader reader = null;
            try {
                reader = new BufferedReader(new InputStreamReader(new FileInputStream(LOG_FILE), "UTF-8"));
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty() || line.charAt(0) == '#') continue;
                    // artist, album, title, track no., duration, rating (L = listened, S = skipped), timestamp, mbid
                    String[] f = line.split("\t", -1);
                    if (f.length < 7 || !"L".equals(f[5])) continue;
                    long playedMs;
                    try {
                        playedMs = Long.parseLong(f[6].trim()) * 1000L;
                    } catch (NumberFormatException e) {
                        continue;
                    }
                    plays++;
                    get(full, fullKey(f[0], f[1], f[2])).add(playedMs);
                    get(artistTitle, artistTitleKey(f[0], f[2])).add(playedMs);
                }
            } catch (Exception e) {
                Log.w(TAG, "Couldn't read the play history", e);
            } finally {
                try {
                    if (reader != null) reader.close();
                } catch (Exception ignored) {
                }
            }
        }
        byFullKey = full;
        byArtistTitle = artistTitle;
        loadedStamp = stamp;
        Log.i(TAG, "Loaded " + plays + " plays of " + full.size() + " songs in "
                + (System.currentTimeMillis() - started) + " ms");
    }

    private static Stats get(Map<String, Stats> map, String key) {
        Stats s = map.get(key);
        if (s == null) map.put(key, s = new Stats());
        return s;
    }

    // Play stats for each library song that has been played (songs never played are left out).
    public static synchronized IdentityHashMap<SongItem, Stats> statsFor(List<SongItem> library) {
        reloadIfChanged();
        IdentityHashMap<SongItem, Stats> result = new IdentityHashMap<>();
        if (byFullKey.isEmpty()) return result;
        int byFallback = 0;
        long started = System.currentTimeMillis();
        for (SongItem song : library) {
            // SongItem has no equals/hashCode, so the WeakHashMap is keyed by the object itself.
            String[] keys = songKeys.get(song);
            if (keys == null) {
                keys = new String[] { fullKey(song.artist, song.album, song.title), artistTitleKey(song.artist, song.title) };
                songKeys.put(song, keys);
            }
            Stats s = byFullKey.get(keys[0]);
            if (s == null) {
                s = byArtistTitle.get(keys[1]);
                if (s != null) byFallback++;
            }
            if (s != null) result.put(song, s);
        }
        Log.i(TAG, "Matched " + result.size() + " library songs (" + byFallback + " by artist + title only) in "
                + (System.currentTimeMillis() - started) + " ms");
        return result;
    }
}
