package com.themoon.y1.managers;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// 🚀 [Library web] Rewrites the text frames of an MP3's ID3v2 tag (title, artist, album...) and keeps every
// other frame as it is (cover art, lyrics, comments...). The tag keeps its version (2.3 or 2.4); a file
// without a tag gets a 2.3 one. Written to a temporary file next to the original, which only replaces the
// original once complete. Unsynchronised tags and ID3v2.2 are refused rather than risked.
public final class Id3Writer {

    // Keys: "title", "artist", "album", "albumArtist", "year", "genre", "track". An empty value removes the
    // frame; a key that's missing leaves it alone.
    public static void write(File mp3, Map<String, String> values) throws IOException {
        write(mp3, values, null);
    }

    // coverJpeg: if not null, replaces the embedded pictures (APIC) with this JPEG as the front cover.
    public static void write(File mp3, Map<String, String> values, byte[] coverJpeg) throws IOException {
        if (!mp3.getName().toLowerCase().endsWith(".mp3")) throw new IOException("Only MP3 files can be edited");
        long fileLen = mp3.length();
        int major = 3;
        long audioStart = 0;
        List<byte[]> kept = new ArrayList<>();

        RandomAccessFile raf = new RandomAccessFile(mp3, "r");
        try {
            byte[] h = new byte[10];
            if (fileLen >= 10) raf.readFully(h);
            if (fileLen >= 10 && h[0] == 'I' && h[1] == 'D' && h[2] == '3') {
                major = h[3];
                int flags = h[5] & 0xFF;
                int size = syncsafe(h, 6);
                if (major != 3 && major != 4) throw new IOException("ID3v2." + major + " tags are not supported");
                if ((flags & 0x80) != 0) throw new IOException("Unsynchronised ID3 tags are not supported");
                if (size > 32 * 1024 * 1024 || 10L + size > fileLen) throw new IOException("Broken ID3 tag");
                audioStart = 10 + size + ((flags & 0x10) != 0 ? 10 : 0);
                byte[] tag = new byte[size];
                raf.readFully(tag);
                int pos = 0;
                if ((flags & 0x40) != 0) { // extended header
                    int ext = major == 4 ? syncsafe(tag, 0) : beInt(tag, 0) + 4;
                    pos = ext;
                }
                java.util.Set<String> replaced = frameIdsFor(values, major);
                if (coverJpeg != null) replaced.add("APIC");
                while (pos + 10 <= size) {
                    if (tag[pos] == 0) break; // padding
                    String id = new String(tag, pos, 4, "ISO-8859-1");
                    int fsize = major == 4 ? syncsafe(tag, pos + 4) : beInt(tag, pos + 4);
                    if (fsize < 0 || pos + 10 + fsize > size) throw new IOException("Broken ID3 frame " + id);
                    if (!replaced.contains(id)) {
                        byte[] frame = new byte[10 + fsize];
                        System.arraycopy(tag, pos, frame, 0, frame.length); // same version: copied as is
                        kept.add(frame);
                    }
                    pos += 10 + fsize;
                }
            }
        } finally {
            raf.close();
        }

        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (Map.Entry<String, String> e : frameValues(values, major).entrySet()) {
            if (e.getValue() == null || e.getValue().trim().isEmpty()) continue;
            body.write(textFrame(e.getKey(), e.getValue().trim(), major));
        }
        if (coverJpeg != null) body.write(pictureFrame(coverJpeg, major));
        for (byte[] f : kept) body.write(f);
        body.write(new byte[1024]); // padding, so the next edit can grow a little

        File tmp = new File(mp3.getParentFile(), "." + mp3.getName() + ".tagtmp");
        OutputStream out = new FileOutputStream(tmp);
        InputStream in = new FileInputStream(mp3);
        try {
            byte[] header = { 'I', 'D', '3', (byte) major, 0, 0, 0, 0, 0, 0 };
            putSyncsafe(header, 6, body.size());
            out.write(header);
            body.writeTo(out);
            long skipped = 0;
            while (skipped < audioStart) {
                long n = in.skip(audioStart - skipped);
                if (n <= 0) throw new IOException("Cannot read the audio");
                skipped += n;
            }
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } catch (IOException e) {
            out.close();
            in.close();
            tmp.delete();
            throw e;
        }
        out.close();
        in.close();

        // Swap: original -> backup, temp -> original, drop the backup (put it back if the second step fails).
        File bak = new File(mp3.getParentFile(), "." + mp3.getName() + ".tagbak");
        bak.delete();
        if (!mp3.renameTo(bak)) {
            tmp.delete();
            throw new IOException("Cannot replace the file");
        }
        if (!tmp.renameTo(mp3)) {
            bak.renameTo(mp3);
            tmp.delete();
            throw new IOException("Cannot replace the file");
        }
        bak.delete();
    }

    // Frame ids per key: the year is TYER in 2.3 and TDRC in 2.4 (the other one is dropped too).
    private static LinkedHashMap<String, String> frameValues(Map<String, String> v, int major) {
        LinkedHashMap<String, String> m = new LinkedHashMap<>();
        if (v.containsKey("title")) m.put("TIT2", v.get("title"));
        if (v.containsKey("artist")) m.put("TPE1", v.get("artist"));
        if (v.containsKey("album")) m.put("TALB", v.get("album"));
        if (v.containsKey("albumArtist")) m.put("TPE2", v.get("albumArtist"));
        if (v.containsKey("year")) m.put(major == 4 ? "TDRC" : "TYER", v.get("year"));
        if (v.containsKey("genre")) m.put("TCON", v.get("genre"));
        if (v.containsKey("track")) m.put("TRCK", v.get("track"));
        return m;
    }

    private static java.util.Set<String> frameIdsFor(Map<String, String> v, int major) {
        java.util.Set<String> ids = new java.util.HashSet<>(frameValues(v, major).keySet());
        if (v.containsKey("year")) {
            ids.add("TYER");
            ids.add("TDRC");
            ids.add("TDAT");
        }
        return ids;
    }

    private static byte[] textFrame(String id, String text, int major) throws IOException {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        if (major == 4) {
            data.write(3); // UTF-8
            data.write(text.getBytes("UTF-8"));
        } else {
            data.write(1); // UTF-16 with BOM
            data.write(0xFF);
            data.write(0xFE);
            data.write(text.getBytes("UTF-16LE"));
            data.write(0);
            data.write(0);
        }
        byte[] d = data.toByteArray();
        byte[] f = new byte[10 + d.length];
        byte[] idb = id.getBytes("ISO-8859-1");
        System.arraycopy(idb, 0, f, 0, 4);
        if (major == 4) putSyncsafe(f, 4, d.length);
        else putBeInt(f, 4, d.length);
        System.arraycopy(d, 0, f, 10, d.length);
        return f;
    }

    // APIC: text encoding 0, MIME "image/jpeg", picture type 3 (front cover), empty description, the image.
    private static byte[] pictureFrame(byte[] jpeg, int major) throws IOException {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        data.write(0);
        data.write("image/jpeg".getBytes("ISO-8859-1"));
        data.write(0);
        data.write(3);
        data.write(0);
        data.write(jpeg);
        byte[] d = data.toByteArray();
        byte[] f = new byte[10 + d.length];
        System.arraycopy("APIC".getBytes("ISO-8859-1"), 0, f, 0, 4);
        if (major == 4) putSyncsafe(f, 4, d.length);
        else putBeInt(f, 4, d.length);
        System.arraycopy(d, 0, f, 10, d.length);
        return f;
    }

    private static int syncsafe(byte[] b, int at) {
        return ((b[at] & 0x7F) << 21) | ((b[at + 1] & 0x7F) << 14) | ((b[at + 2] & 0x7F) << 7) | (b[at + 3] & 0x7F);
    }

    private static void putSyncsafe(byte[] b, int at, int v) {
        b[at] = (byte) ((v >> 21) & 0x7F);
        b[at + 1] = (byte) ((v >> 14) & 0x7F);
        b[at + 2] = (byte) ((v >> 7) & 0x7F);
        b[at + 3] = (byte) (v & 0x7F);
    }

    private static int beInt(byte[] b, int at) {
        return ((b[at] & 0xFF) << 24) | ((b[at + 1] & 0xFF) << 16) | ((b[at + 2] & 0xFF) << 8) | (b[at + 3] & 0xFF);
    }

    private static void putBeInt(byte[] b, int at, int v) {
        b[at] = (byte) (v >> 24);
        b[at + 1] = (byte) (v >> 16);
        b[at + 2] = (byte) (v >> 8);
        b[at + 3] = (byte) v;
    }
}
