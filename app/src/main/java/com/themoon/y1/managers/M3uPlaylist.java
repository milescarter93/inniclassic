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

    private static final class Entry {
        final List<String> comments = new ArrayList<>(); // #EXTINF etc. right before the path
        String line;
        boolean playable;
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
                String path = t.replace("\\", "/");
                File f = new File(path);
                if (!f.isAbsolute()) f = new File(musicRoot, path);
                e.playable = f.exists() && isAudio.isAudio(f);
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
