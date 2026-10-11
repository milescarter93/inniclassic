package com.themoon.y1;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.util.Log;

import com.themoon.y1.managers.Id3Writer;
import com.themoon.y1.managers.M3uPlaylist;
import com.themoon.y1.models.SongItem;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

// 📚 [Library web] The "Library & Playlists" page of the Wi-Fi web server (Y1WebServer): browse and search
// the library, edit MP3 tags (Id3Writer), set an album's cover, delete songs, and edit playlists.
// After a change the library is rescanned in the background (changed files are re-read by their stamp).
final class LibraryWeb {
    private static final String TAG = "Y1LibraryWeb";

    private LibraryWeb() {
    }

    // assets/web/library.html with every {{English text}} replaced by its translation.
    static String page(Context context) throws java.io.IOException {
        return page(context, "web/library.html");
    }

    // (also the Podcasts & Audiobooks page, MediaWeb)
    static String page(Context context, String asset) throws java.io.IOException {
        InputStream in = context.getAssets().open(asset);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        String html = new String(bos.toByteArray(), "UTF-8");
        StringBuilder out = new StringBuilder(html.length());
        int i = 0;
        while (true) {
            int a = html.indexOf("{{", i);
            if (a < 0) break;
            int b = html.indexOf("}}", a);
            if (b < 0) break;
            out.append(html, i, a);
            String key = html.substring(a + 2, b);
            MainActivity main = MainActivity.instance;
            String text = main != null ? main.t(key) : key;
            out.append(text.replace("&", "&amp;").replace("<", "&lt;").replace("'", "&#39;").replace("\"", "&quot;"));
            i = b + 2;
        }
        out.append(html.substring(i));
        return out.toString();
    }

    private static List<SongItem> librarySnapshot() {
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return new ArrayList<>(MainActivity.customLibrary);
            } catch (java.util.ConcurrentModificationException e) {
                // a scan swapped the list in; try again
            }
        }
        return new ArrayList<>();
    }

    private static String rel(File root, File f) {
        String r = root.getAbsolutePath(), p = f.getAbsolutePath();
        return p.startsWith(r + "/") ? p.substring(r.length() + 1) : p;
    }

    // "Unknown Artist" and the like (also translated) mean "no tag".
    private static String tagValue(String v) {
        if (v == null) return "";
        MainActivity main = MainActivity.instance;
        for (String unknown : new String[] { "Unknown Artist", "Unknown Album", "Unknown Year", "Unknown Genre",
                "Unknown Composer" }) {
            if (v.equals(unknown) || (main != null && v.equals(main.t(unknown)))) return "";
        }
        return v;
    }

    static String songsJson(File root) throws Exception {
        JSONArray arr = new JSONArray();
        for (SongItem s : librarySnapshot()) {
            JSONObject o = new JSONObject();
            o.put("p", rel(root, s.file));
            o.put("t", s.title);
            o.put("a", tagValue(s.artist));
            // The tags as written (album names can be adjusted for browsing, see normalizeAlbumGrouping).
            o.put("al", tagValue(s.albumTag != null ? s.albumTag : s.album));
            o.put("aa", tagValue(s.albumArtistTag != null ? s.albumArtistTag : s.albumArtist));
            o.put("y", tagValue(s.year));
            o.put("g", tagValue(s.genre));
            Integer track = MainActivity.trackNumberMap.get(s.file.getAbsolutePath());
            o.put("n", track != null && track > 0 ? String.valueOf(track) : "");
            arr.put(o);
        }
        return arr.toString();
    }

    // The song's picture: embedded, else Y1_Covers/<name>.jpg, else the folder's cover image.
    static byte[] art(File song) {
        try {
            MediaMetadataRetriever mmr = new MediaMetadataRetriever();
            FileInputStream fis = new FileInputStream(song);
            try {
                mmr.setDataSource(fis.getFD());
                byte[] pic = mmr.getEmbeddedPicture();
                if (pic != null && pic.length > 0) return pic;
            } finally {
                fis.close();
                mmr.release();
            }
        } catch (Throwable ignored) {
        }
        String base = song.getName();
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        File cover = new File("/storage/sdcard0/Y1_Covers", base + ".jpg");
        if (!cover.exists() && MainActivity.instance != null)
            cover = com.themoon.y1.managers.ThumbnailCache.folderCover(song.getParentFile(), MainActivity.instance);
        if (cover == null || !cover.exists()) return null;
        try {
            FileInputStream in = new FileInputStream(cover);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            return bos.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    private static String result(boolean ok, String error, int count) {
        try {
            JSONObject o = new JSONObject();
            o.put("ok", ok);
            if (error != null) o.put("error", error);
            if (count >= 0) o.put("count", count);
            return o.toString();
        } catch (Exception e) {
            return "{\"ok\":false}";
        }
    }

    private static void libraryChanged() {
        MainActivity main = MainActivity.instance;
        if (main != null) main.requestLibraryRescanSoon();
    }

    static String saveTags(File song, Map<String, String> values) {
        try {
            if (!song.isFile()) return result(false, "File not found", -1);
            Id3Writer.write(song, values);
            Log.i(TAG, "Tags saved: " + song.getName() + " " + values.keySet());
            libraryChanged();
            return result(true, null, -1);
        } catch (Exception e) {
            Log.w(TAG, "Tags not saved: " + song.getName(), e);
            return result(false, e.getMessage(), -1);
        }
    }

    // Sets the cover of every song of the song's album (same album and album artist): embedded in MP3s,
    // as Y1_Covers/<name>.jpg for other formats. The picture is resized to at most 800 px, as JPEG.
    static String saveAlbumCover(File song, byte[] image) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(image, 0, image.length, bounds);
            if (bounds.outWidth <= 0) return result(false, "Not an image", -1);
            BitmapFactory.Options opts = new BitmapFactory.Options();
            int sample = 1;
            while (bounds.outWidth / sample > 1600 || bounds.outHeight / sample > 1600) sample *= 2;
            opts.inSampleSize = sample;
            Bitmap bmp = BitmapFactory.decodeByteArray(image, 0, image.length, opts);
            int max = Math.max(bmp.getWidth(), bmp.getHeight());
            if (max > 800) {
                Bitmap scaled = Bitmap.createScaledBitmap(bmp, bmp.getWidth() * 800 / max, bmp.getHeight() * 800 / max, true);
                bmp.recycle();
                bmp = scaled;
            }
            ByteArrayOutputStream jpg = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.JPEG, 90, jpg);
            bmp.recycle();
            byte[] jpeg = jpg.toByteArray();

            List<File> targets = new ArrayList<>();
            SongItem me = null;
            List<SongItem> lib = librarySnapshot();
            for (SongItem s : lib) if (s.file.equals(song)) me = s;
            if (me == null) {
                targets.add(song);
            } else {
                for (SongItem s : lib)
                    if (s.album.equals(me.album) && s.albumArtist.equals(me.albumArtist)) targets.add(s.file);
            }
            int done = 0;
            MainActivity main = MainActivity.instance;
            for (File f : targets) {
                try {
                    if (f.getName().toLowerCase(Locale.ROOT).endsWith(".mp3")) {
                        Id3Writer.write(f, new java.util.HashMap<String, String>(), jpeg);
                    } else {
                        String base = f.getName();
                        int dot = base.lastIndexOf('.');
                        if (dot > 0) base = base.substring(0, dot);
                        File dir = new File("/storage/sdcard0/Y1_Covers");
                        dir.mkdirs();
                        FileOutputStream out = new FileOutputStream(new File(dir, base + ".jpg"));
                        out.write(jpeg);
                        out.close();
                    }
                    // A cover downloaded earlier would still win in Cover Flow.
                    if (main != null) main.prefs.edit().remove("album_art_" + f.getAbsolutePath()).apply();
                    done++;
                } catch (Exception e) {
                    Log.w(TAG, "Cover not set on " + f.getName(), e);
                }
            }
            Log.i(TAG, "Album cover set on " + done + "/" + targets.size() + " songs");
            libraryChanged();
            return result(done > 0, done > 0 ? null : "Could not change any file", done);
        } catch (Throwable e) {
            return result(false, e.getMessage(), -1);
        }
    }

    static String deleteSong(File song) {
        String name = song.getName().toLowerCase(Locale.ROOT);
        boolean audio = name.matches(".*\\.(mp3|flac|wav|ogg|opus|m4a|aac|ape|wma|m4b)$");
        if (!song.isFile() || !audio) return result(false, "Not a song", -1);
        boolean ok = song.delete();
        Log.i(TAG, "Delete " + song.getName() + ": " + ok);
        if (ok) libraryChanged();
        return result(ok, ok ? null : "Could not delete the file", -1);
    }

    // ================= playlists =================

    private static File playlistFile(String name) {
        File[] files = M3uPlaylist.DIR.listFiles();
        if (files != null) {
            for (File f : files) {
                String lower = f.getName().toLowerCase(Locale.ROOT);
                if ((lower.endsWith(".m3u") || lower.endsWith(".m3u8")) && M3uPlaylist.displayName(f).equals(name))
                    return f;
            }
        }
        return null;
    }

    private static boolean isAudio(File f) {
        return f.getName().toLowerCase(Locale.ROOT).matches(".*\\.(mp3|flac|wav|ogg|opus|m4a|aac|ape|wma|m4b)$");
    }

    static String playlistsJson() throws Exception {
        JSONArray arr = new JSONArray();
        File[] files = M3uPlaylist.DIR.listFiles();
        List<File> lists = new ArrayList<>();
        if (files != null) {
            for (File f : files) {
                String lower = f.getName().toLowerCase(Locale.ROOT);
                if (f.isFile() && (lower.endsWith(".m3u") || lower.endsWith(".m3u8"))) lists.add(f);
            }
        }
        java.util.Collections.sort(lists, (x, y) -> x.getName().compareToIgnoreCase(y.getName()));
        for (File f : lists) {
            JSONObject o = new JSONObject();
            o.put("name", M3uPlaylist.displayName(f));
            arr.put(o);
        }
        return arr.toString();
    }

    static String playlistJson(File root, String name) throws Exception {
        File f = playlistFile(name);
        JSONArray arr = new JSONArray();
        if (f == null) return arr.toString();
        java.util.HashMap<String, SongItem> byPath = new java.util.HashMap<>();
        for (SongItem s : librarySnapshot()) byPath.put(s.file.getAbsolutePath(), s);
        M3uPlaylist pl = M3uPlaylist.load(f, new File("/storage/sdcard0/Music"), LibraryWeb::isAudio);
        for (String[] e : pl.entryInfo()) {
            JSONObject o = new JSONObject();
            boolean ok = "1".equals(e[2]);
            SongItem s = byPath.get(e[1]);
            o.put("p", ok ? rel(root, new File(e[1])) : e[0]);
            o.put("t", s != null ? s.title : new File(e[1].isEmpty() ? e[0] : e[1]).getName());
            o.put("a", s != null ? tagValue(s.artist) : "");
            o.put("ok", ok);
            arr.put(o);
        }
        return arr.toString();
    }

    // body: one entry per line - a path relative to the card (written absolute), or a line kept as it was
    // (a song the playlist couldn't find).
    static String savePlaylist(File root, String name, String body) {
        try {
            String clean = M3uPlaylist.cleanName(name);
            if (clean == null) return result(false, "Invalid name", -1);
            File f = playlistFile(clean);
            if (f == null) {
                M3uPlaylist.DIR.mkdirs();
                f = new File(M3uPlaylist.DIR, clean + ".m3u8");
            }
            StringBuilder sb = new StringBuilder("#EXTM3U\n");
            int count = 0;
            for (String line : body.split("\n")) {
                line = line.trim();
                if (line.isEmpty()) continue;
                File song = new File(root, line);
                sb.append(song.isFile() && isAudio(song) ? song.getAbsolutePath() : line).append('\n');
                count++;
            }
            FileOutputStream out = new FileOutputStream(f, false);
            out.write(sb.toString().getBytes("UTF-8"));
            out.close();
            Log.i(TAG, "Playlist saved: " + f.getName() + " (" + count + " songs)");
            return result(true, null, count);
        } catch (Exception e) {
            return result(false, e.getMessage(), -1);
        }
    }

    static String deletePlaylist(String name) {
        File f = playlistFile(name);
        boolean ok = f != null && !f.getName().equals(M3uPlaylist.ON_THE_GO) && f.delete();
        Log.i(TAG, "Delete playlist " + name + ": " + ok);
        return result(ok, ok ? null : "Could not delete the playlist", -1);
    }
}
