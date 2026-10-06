package com.themoon.y1.managers;

import com.themoon.y1.MainActivity;
import com.themoon.y1.models.SongItem;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

// 🚀 [Smart shuffle] Builds shuffled play orders.
// - BY_SONGS: random order, then spread out so the same artist doesn't play twice in a row when avoidable
//   (a plain shuffle of a library with a few big artists does that constantly).
// - BY_ALBUMS: the iPod's "Shuffle Albums" - albums in random order, each album whole and in track order.
// The chosen track (if any) always comes first, like on the iPod: picking a song with shuffle on used to
// drop it at a random spot, leaving everything before it unplayed until the queue wrapped.
public class ShuffleHelper {

    public static final String PREF_KEY = "shuffle_by";
    public static final String BY_SONGS = "songs";
    public static final String BY_ALBUMS = "albums";

    public static List<File> shuffle(List<File> files, File first, String mode) {
        long started = System.currentTimeMillis();
        Map<String, SongItem> meta = libraryByPath();
        List<File> result = BY_ALBUMS.equals(mode) ? shuffleAlbums(files, first, meta) : shuffleSongs(files, first, meta);
        android.util.Log.i("Y1Shuffle", mode + " shuffle of " + result.size() + " tracks in "
                + (System.currentTimeMillis() - started) + " ms, same artist back to back: "
                + countArtistRepeats(result, meta));
        return result;
    }

    private static int countArtistRepeats(List<File> list, Map<String, SongItem> meta) {
        Map<String, Integer> ids = new HashMap<>();
        int repeats = 0, previous = -1;
        for (File f : list) {
            int id = artistId(f, meta, ids);
            if (id == previous) repeats++;
            previous = id;
        }
        return repeats;
    }

    private static List<File> shuffleSongs(List<File> files, File first, Map<String, SongItem> meta) {
        List<File> list = new ArrayList<>(files);
        if (first != null) list.remove(first);
        Collections.shuffle(list);

        // Artist names as small ints, so the spreading pass below only compares numbers.
        Map<String, Integer> ids = new HashMap<>();
        int[] artist = new int[list.size()];
        for (int i = 0; i < list.size(); i++) artist[i] = artistId(list.get(i), meta, ids);
        int previous = first != null ? artistId(first, meta, ids) : -1;

        // Greedy pass: wherever a track has the same artist as the one before it, swap in the nearest later
        // track by someone else. If none is left (only that artist remains), the run can't be avoided.
        for (int i = 0; i < list.size(); i++) {
            int before = i == 0 ? previous : artist[i - 1];
            if (artist[i] != before) continue;
            for (int j = i + 1; j < list.size(); j++) {
                if (artist[j] != before) {
                    Collections.swap(list, i, j);
                    int t = artist[i];
                    artist[i] = artist[j];
                    artist[j] = t;
                    break;
                }
            }
        }

        if (first != null) list.add(0, first);
        return list;
    }

    private static List<File> shuffleAlbums(List<File> files, File first, Map<String, SongItem> meta) {
        LinkedHashMap<String, List<File>> albums = new LinkedHashMap<>();
        for (File f : files) {
            String key = albumKey(f, meta);
            List<File> tracks = albums.get(key);
            if (tracks == null) {
                tracks = new ArrayList<>();
                albums.put(key, tracks);
            }
            tracks.add(f);
        }
        for (List<File> tracks : albums.values()) sortByTrack(tracks, meta);

        List<String> order = new ArrayList<>(albums.keySet());
        Collections.shuffle(order);
        if (first != null) {
            // The chosen song's album plays first, starting from that song.
            String firstAlbum = albumKey(first, meta);
            order.remove(firstAlbum);
            order.add(0, firstAlbum);
        }

        List<File> result = new ArrayList<>(files.size());
        for (String key : order) {
            List<File> tracks = albums.get(key);
            if (first != null && key.equals(albumKey(first, meta))) {
                int start = Math.max(0, tracks.indexOf(first));
                result.addAll(tracks.subList(start, tracks.size()));
                result.addAll(tracks.subList(0, start)); // the album's earlier tracks go to its end
            } else {
                result.addAll(tracks);
            }
        }
        return result;
    }

    private static void sortByTrack(List<File> tracks, final Map<String, SongItem> meta) {
        Collections.sort(tracks, (a, b) -> {
            Integer ta = MainActivity.trackNumberMap.get(a.getAbsolutePath());
            Integer tb = MainActivity.trackNumberMap.get(b.getAbsolutePath());
            int na = ta != null ? ta : 0, nb = tb != null ? tb : 0;
            if (na != nb) return Integer.compare(na, nb);
            return a.getName().compareToIgnoreCase(b.getName());
        });
    }

    private static int artistId(File f, Map<String, SongItem> meta, Map<String, Integer> ids) {
        SongItem s = meta.get(f.getAbsolutePath());
        String key = s != null ? s.artist.trim().toLowerCase(Locale.ROOT) : "";
        Integer id = ids.get(key);
        if (id == null) {
            id = ids.size();
            ids.put(key, id);
        }
        return id;
    }

    private static String albumKey(File f, Map<String, SongItem> meta) {
        SongItem s = meta.get(f.getAbsolutePath());
        // Files outside the library (folder playback) are grouped by folder.
        if (s == null) return "folder:" + f.getParent();
        return (s.albumArtist + "\u0000" + s.album).toLowerCase(Locale.ROOT);
    }

    // The visible lists, not the *All ones: those are rewritten by a background library scan, while these are
    // only changed on the UI thread - which is where shuffling happens.
    private static Map<String, SongItem> libraryByPath() {
        Map<String, SongItem> map = new HashMap<>();
        for (SongItem s : MainActivity.customLibrary) map.put(s.file.getAbsolutePath(), s);
        for (SongItem s : MainActivity.audiobookLibrary) map.put(s.file.getAbsolutePath(), s);
        return map;
    }
}
