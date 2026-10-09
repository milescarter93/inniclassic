package com.themoon.y1.managers;

import android.util.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

// Editing an .m3u/.m3u8 playlist in place: remove or move a song, keeping each song's own comment lines
// (#EXTINF...) with it and the header at the top. Positions are "playable" positions - the songs the
// playlist screen shows, which skips entries whose file is missing - so they match what's on screen.
// Also creating, renaming, copying and deleting playlist files.
public final class M3uPlaylist {
    private static final String TAG = "Y1Playlist";
    public static final File DIR = new File("/storage/sdcard0/Y1_Playlists");
    public static final String ON_THE_GO = "On-The-Go.m3u8";

    public static final File CARD = new File("/storage/sdcard0");

    private static final class Entry {
        final List<String> comments = new ArrayList<>(); // #EXTINF etc. right before the path
        String line;
        File target; // where the line points (may not exist)
        boolean playable;
    }

    // Where a playlist line points. Understands absolute paths, "E:\Music\x.mp3" from a PC (the drive is
    // the card), file:// URLs, and relative paths - from the playlist's own folder (the M3U standard),
    // then from Music (what InniClassic used to assume).
    public static File resolve(String line, File playlistDir, File musicRoot) {
        String p = line.trim().replace('\\', '/');
        if (p.regionMatches(true, 0, "file://", 0, 7)) {
            p = p.substring(7);
            try {
                p = java.net.URLDecoder.decode(p.replace("+", "%2B"), "UTF-8");
            } catch (Exception ignored) {
            }
            if (p.startsWith("/") && p.length() > 2 && p.charAt(2) == ':') p = p.substring(1); // file:///E:/...
        }
        if (p.length() > 2 && Character.isLetter(p.charAt(0)) && p.charAt(1) == ':' && p.charAt(2) == '/')
            return new File(CARD, p.substring(3));
        File f = new File(p);
        if (f.isAbsolute()) return f;
        if (playlistDir != null) {
            File rel = new File(playlistDir, p);
            if (rel.exists()) return rel;
        }
        return new File(musicRoot, p);
    }

    private final File file;
    private final List<String> header = new ArrayList<>();
    private final List<Entry> entries = new ArrayList<>();
    private final List<String> trailing = new ArrayList<>();

    private M3uPlaylist(File file) {
        this.file = file;
    }

    // musicRoot: where relative paths point (the same rule as the playlist screen).
    public static M3uPlaylist load(File file, File musicRoot, AudioCheck isAudio) throws Exception {
        M3uPlaylist p = new M3uPlaylist(file);
        BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"));
        try {
            List<String> pending = new ArrayList<>();
            String line;
            boolean first = true;
            while ((line = br.readLine()) != null) {
                if (first && line.startsWith("﻿")) line = line.substring(1); // BOM
                String t = line.trim();
                if (first && t.toUpperCase(Locale.ROOT).startsWith("#EXTM3U")) {
                    p.header.add(line);
                    first = false;
                    continue;
                }
                first = false;
                if (t.isEmpty() || t.startsWith("#")) {
                    pending.add(line);
                    continue;
                }
                Entry e = new Entry();
                e.comments.addAll(pending);
                pending.clear();
                e.line = line;
                e.target = resolve(t, file.getParentFile(), musicRoot);
                e.playable = e.target.exists() && isAudio.isAudio(e.target);
                p.entries.add(e);
            }
            p.trailing.addAll(pending);
        } finally {
            br.close();
        }
        return p;
    }

    public interface AudioCheck {
        boolean isAudio(File f);
    }

    private int entryIndex(int playable) {
        int seen = -1;
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).playable && ++seen == playable) return i;
        }
        return -1;
    }

    public boolean removeAt(int playable) {
        int i = entryIndex(playable);
        if (i < 0) return false;
        entries.remove(i);
        return true;
    }

    // Swaps the song at this position with the next/previous one on screen.
    public boolean moveBy(int playable, int step) {
        int a = entryIndex(playable), b = entryIndex(playable + step);
        if (a < 0 || b < 0) return false;
        Collections.swap(entries, a, b);
        return true;
    }

    // Finds a missing song again: by the file name, then by the "#EXTINF:123,Artist - Title" line.
    public interface SongFinder {
        File find(String fileName, String extinfArtistTitle);
    }

    // Points the entries whose file is missing at the song found in the library. {repaired, still missing}
    public int[] repair(SongFinder finder) {
        int repaired = 0, missing = 0;
        for (Entry e : entries) {
            if (e.playable) continue;
            String name = e.target != null ? e.target.getName() : new File(e.line.trim().replace('\\', '/')).getName();
            String extinf = null;
            for (String c : e.comments) {
                String t = c.trim();
                if (t.toUpperCase(Locale.ROOT).startsWith("#EXTINF:") && t.indexOf(',') > 0)
                    extinf = t.substring(t.indexOf(',') + 1).trim();
            }
            File found = finder.find(name, extinf);
            if (found != null) {
                e.line = found.getAbsolutePath();
                e.target = found;
                e.playable = true;
                repaired++;
            } else {
                missing++;
            }
        }
        Log.i(TAG, "Repair " + file.getName() + ": " + repaired + " repaired, " + missing + " still missing");
        return new int[] { repaired, missing };
    }

    // Rewrites the songs' paths relative to the playlist's folder ("../Music/x.mp3"), so the same file
    // works when the card is opened on a computer. Returns how many lines changed.
    public int makeRelative() {
        File dir = file.getParentFile();
        int changed = 0;
        for (Entry e : entries) {
            if (!e.playable || e.target == null) continue;
            String rel = relativePath(dir, e.target);
            if (rel != null && !rel.equals(e.line.trim())) {
                e.line = rel;
                changed++;
            }
        }
        Log.i(TAG, "Relative paths in " + file.getName() + ": " + changed + " changed");
        return changed;
    }

    static String relativePath(File fromDir, File to) {
        try {
            String[] a = fromDir.getCanonicalPath().replace('\\', '/').split("/");
            String[] b = to.getCanonicalPath().replace('\\', '/').split("/");
            int common = 0;
            while (common < a.length && common < b.length - 1 && a[common].equals(b[common])) common++;
            if (common < 2) return null; // nothing in common but the root: keep it absolute
            StringBuilder sb = new StringBuilder();
            for (int i = common; i < a.length; i++) sb.append("../");
            for (int i = common; i < b.length; i++) {
                sb.append(b[i]);
                if (i < b.length - 1) sb.append('/');
            }
            return sb.toString();
        } catch (Exception ex) {
            return null;
        }
    }

    // For the library web page: each entry as {line as written, resolved absolute path, "1" if playable}.
    public List<String[]> entryInfo() {
        List<String[]> out = new ArrayList<>();
        for (Entry e : entries)
            out.add(new String[] { e.line.trim(), e.target != null ? e.target.getAbsolutePath() : "", e.playable ? "1" : "0" });
        return out;
    }

    public int missingCount() {
        int n = 0;
        for (Entry e : entries) if (!e.playable) n++;
        return n;
    }

    // Playlists a computer put on the card outside Y1_Playlists (the card's top folder, Music, Playlists).
    public static List<File> findImportable() {
        List<File> found = new ArrayList<>();
        File[] dirs = { CARD, new File(CARD, "Music"), new File(CARD, "Playlists"), new File(CARD, "playlists"),
                new File(CARD, "Music/Playlists") };
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        for (File d : dirs) {
            File[] files = d.listFiles();
            if (files == null) continue;
            for (File f : files) {
                String lower = f.getName().toLowerCase(Locale.ROOT);
                if (f.isFile() && (lower.endsWith(".m3u") || lower.endsWith(".m3u8")) && seen.add(f.getAbsolutePath()))
                    found.add(f);
            }
        }
        return found;
    }

    // Copies a playlist from elsewhere on the card into Y1_Playlists (under a free name), with its paths
    // resolved from where it was - so relative paths keep working - and missing songs repaired. The
    // original stays where it is. Returns the new file.
    public static File importFrom(File source, File musicRoot, AudioCheck isAudio, SongFinder finder) throws Exception {
        String base = cleanName(displayName(source));
        if (base == null) base = "Playlist";
        String name = base;
        for (int n = 2; fileFor(name) == null; n++) name = base + " " + n;
        M3uPlaylist p = load(source, musicRoot, isAudio);
        for (Entry e : p.entries) if (e.playable) e.line = e.target.getAbsolutePath();
        p.repair(finder);
        if (!DIR.exists()) DIR.mkdirs();
        File target = new File(DIR, name + ".m3u8");
        p.writeTo(target);
        Log.i(TAG, "Imported " + source.getAbsolutePath() + " -> " + target.getName());
        return target;
    }

    public void clear() {
        entries.clear();
        trailing.clear();
    }

    public void save() throws Exception {
        writeTo(file);
    }

    private void writeTo(File target) throws Exception {
        BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(target, false), "UTF-8"));
        try {
            if (header.isEmpty()) bw.write("#EXTM3U\n");
            for (String h : header) bw.write(h + "\n");
            for (Entry e : entries) {
                for (String c : e.comments) bw.write(c + "\n");
                bw.write(e.line + "\n");
            }
            for (String t : trailing) bw.write(t + "\n");
        } finally {
            bw.close();
        }
    }

    // ================= files =================

    // A name usable as a file name, or null if nothing is left of it.
    public static String cleanName(String name) {
        if (name == null) return null;
        String n = name.replaceAll("[\\\\/:*?\"<>|]", " ").replaceAll("\\s+", " ").trim();
        while (n.startsWith(".")) n = n.substring(1).trim();
        return n.isEmpty() ? null : n;
    }

    public static String displayName(File f) {
        String n = f.getName();
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    // The file a new playlist with this name would use, or null if one with that name already exists.
    public static File fileFor(String name) {
        String clean = cleanName(name);
        if (clean == null) return null;
        File[] existing = DIR.listFiles();
        if (existing != null) {
            for (File f : existing) {
                String lower = f.getName().toLowerCase(Locale.ROOT);
                if ((lower.endsWith(".m3u") || lower.endsWith(".m3u8")) && displayName(f).equalsIgnoreCase(clean))
                    return null;
            }
        }
        return new File(DIR, clean + ".m3u8");
    }

    public static File createEmpty(String name) throws Exception {
        File f = fileFor(name);
        if (f == null) return null;
        if (!DIR.exists()) DIR.mkdirs();
        BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(f), "UTF-8"));
        try {
            bw.write("#EXTM3U\n");
        } finally {
            bw.close();
        }
        Log.i(TAG, "Created " + f.getName());
        return f;
    }

    public static File rename(File from, String name) {
        File to = fileFor(name);
        if (to == null) return null;
        // Keep .m3u files .m3u (a PC tool may expect it).
        if (from.getName().toLowerCase(Locale.ROOT).endsWith(".m3u") && !from.getName().toLowerCase(Locale.ROOT).endsWith(".m3u8"))
            to = new File(DIR, cleanName(name) + ".m3u");
        boolean ok = from.renameTo(to);
        Log.i(TAG, "Rename " + from.getName() + " -> " + to.getName() + ": " + ok);
        return ok ? to : null;
    }

    public static File copy(File from, String name) throws Exception {
        File to = fileFor(name);
        if (to == null) return null;
        FileInputStream in = new FileInputStream(from);
        FileOutputStream out = new FileOutputStream(to);
        try {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            in.close();
            out.close();
        }
        Log.i(TAG, "Copied " + from.getName() + " -> " + to.getName());
        return to;
    }
}
