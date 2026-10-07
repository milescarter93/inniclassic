package com.themoon.y1.managers;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.Locale;

// Reads a track's format details straight from its headers (MP3 frame header + Xing/VBRI,
// FLAC STREAMINFO, WAV fmt chunk, Ogg Vorbis id header). Cheap: only the first few KB are read.
// Used by the "Now Playing Info" line ("MP3 · 320 kbps · 44.1 kHz").
public final class TrackFormatInfo {
    public String codec = "";     // "MP3", "FLAC", ...
    public boolean lossless;
    public boolean vbr;
    public int kbps;              // average bitrate, 0 = unknown
    public int sampleRate;        // Hz, 0 = unknown
    public int bitsPerSample;     // lossless only, 0 = unknown
    // True when kbps had to come from file size / duration and no duration was known yet.
    public boolean needsDuration;

    private static final int[][] MP3_BITRATES = {
            // V1 L1, V1 L2, V1 L3
            {0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448},
            {0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384},
            {0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320},
            // V2/V2.5 L1, V2/V2.5 L2 & L3
            {0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256},
            {0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160},
    };
    private static final int[][] MP3_SAMPLE_RATES = {
            {11025, 12000, 8000}, // MPEG 2.5
            {0, 0, 0},            // reserved
            {22050, 24000, 16000}, // MPEG 2
            {44100, 48000, 32000}, // MPEG 1
    };

    // durationMs: the player's duration if known (0 otherwise) - only needed for formats whose
    // headers don't give the bitrate (Ogg, Opus, M4A...).
    public static TrackFormatInfo read(File file, long durationMs) {
        TrackFormatInfo info = new TrackFormatInfo();
        String name = file.getName().toLowerCase(Locale.US);
        int dot = name.lastIndexOf('.');
        String ext = dot >= 0 ? name.substring(dot + 1) : "";
        info.codec = ext.toUpperCase(Locale.US);
        try {
            if (ext.equals("mp3")) readMp3(file, info);
            else if (ext.equals("flac")) readFlac(file, info);
            else if (ext.equals("wav")) readWav(file, info);
            else if (ext.equals("ogg") || ext.equals("oga")) readOggVorbis(file, info);
            else if (ext.equals("opus")) info.sampleRate = 48000; // Opus always decodes at 48 kHz
            else if (ext.equals("ape")) info.lossless = true;
        } catch (Throwable ignored) {
        }
        if (info.kbps <= 0 && !info.lossless) {
            if (durationMs > 0) info.kbps = (int) (file.length() * 8 / durationMs);
            else info.needsDuration = true;
        }
        // An M4A above ~400 kbps is ALAC, not AAC (same rule as the quality capsules).
        if ((ext.equals("m4a") || ext.equals("mp4")) && info.kbps > 400) {
            info.codec = "ALAC";
            info.lossless = true;
        } else if (ext.equals("m4a") || ext.equals("mp4")) {
            info.codec = "AAC";
        }
        return info;
    }

    private static void readMp3(File file, TrackFormatInfo info) throws Exception {
        RandomAccessFile raf = new RandomAccessFile(file, "r");
        try {
            long fileLen = raf.length();
            long audioStart = 0;
            byte[] head = new byte[10];
            raf.readFully(head);
            if (head[0] == 'I' && head[1] == 'D' && head[2] == '3') {
                int size = ((head[6] & 0x7F) << 21) | ((head[7] & 0x7F) << 14) | ((head[8] & 0x7F) << 7) | (head[9] & 0x7F);
                audioStart = 10 + size + ((head[5] & 0x10) != 0 ? 10 : 0);
            }
            long audioEnd = fileLen;
            if (fileLen > 128) {
                byte[] tag = new byte[3];
                raf.seek(fileLen - 128);
                raf.readFully(tag);
                if (tag[0] == 'T' && tag[1] == 'A' && tag[2] == 'G') audioEnd -= 128;
            }

            byte[] buf = new byte[64 * 1024];
            raf.seek(audioStart);
            int len = raf.read(buf);
            if (len <= 4) return;
            for (int i = 0; i + 4 <= len; i++) {
                int h = headerAt(buf, i, len);
                if (h < 0) continue;
                int frameLen = frameLength(buf, i);
                // A real frame is followed by another one; random 0xFFEx bytes in leftover tag data aren't.
                if (frameLen <= 0 || (i + frameLen + 4 <= len && headerAt(buf, i + frameLen, len) < 0)) continue;

                int b1 = buf[i + 1] & 0xFF, b2 = buf[i + 2] & 0xFF, b3 = buf[i + 3] & 0xFF;
                int version = (b1 >> 3) & 3;      // 3 = MPEG1, 2 = MPEG2, 0 = MPEG2.5
                int layer = (b1 >> 1) & 3;        // 3 = L1, 2 = L2, 1 = L3
                boolean mono = ((b3 >> 6) & 3) == 3;
                int sampleRate = MP3_SAMPLE_RATES[version][(b2 >> 2) & 3];
                info.sampleRate = sampleRate;
                info.kbps = bitrateOf(version, layer, (b2 >> 4) & 0xF);

                int samplesPerFrame = layer == 3 ? 384 : (layer == 1 && version != 3 ? 576 : 1152);
                int sideInfo = version == 3 ? (mono ? 17 : 32) : (mono ? 9 : 17);
                int x = i + 4 + sideInfo;
                long frames = 0, bytes = 0;
                if (x + 16 <= len && (match(buf, x, "Xing") || match(buf, x, "Info"))) {
                    info.vbr = match(buf, x, "Xing");
                    int flags = readInt(buf, x + 4);
                    int p = x + 8;
                    if ((flags & 1) != 0 && p + 4 <= len) { frames = readInt(buf, p) & 0xFFFFFFFFL; p += 4; }
                    if ((flags & 2) != 0 && p + 4 <= len) { bytes = readInt(buf, p) & 0xFFFFFFFFL; }
                } else if (i + 4 + 32 + 18 <= len && match(buf, i + 4 + 32, "VBRI")) {
                    info.vbr = true;
                    bytes = readInt(buf, i + 4 + 32 + 10) & 0xFFFFFFFFL;
                    frames = readInt(buf, i + 4 + 32 + 14) & 0xFFFFFFFFL;
                }
                if (info.vbr && frames > 0 && sampleRate > 0) {
                    double seconds = frames * (double) samplesPerFrame / sampleRate;
                    if (bytes <= 0) bytes = audioEnd - (audioStart + i);
                    if (seconds > 0) info.kbps = (int) Math.round(bytes * 8 / seconds / 1000);
                }
                return;
            }
        } finally {
            raf.close();
        }
    }

    // Returns 0 if a valid MPEG audio frame header starts at i, -1 otherwise.
    private static int headerAt(byte[] buf, int i, int len) {
        if (i + 4 > len) return -1;
        int b0 = buf[i] & 0xFF, b1 = buf[i + 1] & 0xFF, b2 = buf[i + 2] & 0xFF;
        if (b0 != 0xFF || (b1 & 0xE0) != 0xE0) return -1;
        if (((b1 >> 3) & 3) == 1 || ((b1 >> 1) & 3) == 0) return -1; // reserved version / layer
        int br = (b2 >> 4) & 0xF;
        if (br == 0 || br == 15 || ((b2 >> 2) & 3) == 3) return -1;   // free/bad bitrate, reserved rate
        return 0;
    }

    private static int frameLength(byte[] buf, int i) {
        int b1 = buf[i + 1] & 0xFF, b2 = buf[i + 2] & 0xFF;
        int version = (b1 >> 3) & 3, layer = (b1 >> 1) & 3;
        int bitrate = bitrateOf(version, layer, (b2 >> 4) & 0xF) * 1000;
        int sampleRate = MP3_SAMPLE_RATES[version][(b2 >> 2) & 3];
        int padding = (b2 >> 1) & 1;
        if (bitrate <= 0 || sampleRate <= 0) return -1;
        if (layer == 3) return (12 * bitrate / sampleRate + padding) * 4;
        if (layer == 1 && version != 3) return 72 * bitrate / sampleRate + padding;
        return 144 * bitrate / sampleRate + padding;
    }

    private static int bitrateOf(int version, int layer, int index) {
        int table;
        if (version == 3) table = layer == 3 ? 0 : (layer == 2 ? 1 : 2);
        else table = layer == 3 ? 3 : 4;
        return MP3_BITRATES[table][index];
    }

    private static void readFlac(File file, TrackFormatInfo info) throws Exception {
        info.lossless = true;
        RandomAccessFile raf = new RandomAccessFile(file, "r");
        try {
            byte[] b = new byte[42];
            raf.readFully(b);
            long base = 0;
            if (b[0] == 'I' && b[1] == 'D' && b[2] == '3') { // rare, but some taggers prepend ID3 to FLAC
                base = 10 + (((b[6] & 0x7F) << 21) | ((b[7] & 0x7F) << 14) | ((b[8] & 0x7F) << 7) | (b[9] & 0x7F));
                raf.seek(base);
                raf.readFully(b);
            }
            if (!match(b, 0, "fLaC")) return;
            int s = 8; // STREAMINFO follows "fLaC" and its 4-byte block header
            int sampleRate = ((b[s + 10] & 0xFF) << 12) | ((b[s + 11] & 0xFF) << 4) | ((b[s + 12] & 0xF0) >> 4);
            int bits = (((b[s + 12] & 1) << 4) | ((b[s + 13] & 0xF0) >> 4)) + 1;
            long samples = ((long) (b[s + 13] & 0x0F) << 32) | (readInt(b, s + 14) & 0xFFFFFFFFL);
            info.sampleRate = sampleRate;
            info.bitsPerSample = bits;
            if (sampleRate > 0 && samples > 0)
                info.kbps = (int) ((raf.length() - base) * 8 / (samples * 1000.0 / sampleRate));
        } finally {
            raf.close();
        }
    }

    private static void readWav(File file, TrackFormatInfo info) throws Exception {
        info.lossless = true;
        RandomAccessFile raf = new RandomAccessFile(file, "r");
        try {
            byte[] b = new byte[4096];
            int len = raf.read(b);
            if (len < 12 || !match(b, 0, "RIFF") || !match(b, 8, "WAVE")) return;
            int p = 12;
            while (p + 8 <= len) {
                int size = readIntLE(b, p + 4);
                if (match(b, p, "fmt ") && p + 24 <= len) {
                    int channels = (b[p + 10] & 0xFF) | ((b[p + 11] & 0xFF) << 8);
                    info.sampleRate = readIntLE(b, p + 12);
                    info.bitsPerSample = (b[p + 22] & 0xFF) | ((b[p + 23] & 0xFF) << 8);
                    info.kbps = (int) ((long) info.sampleRate * info.bitsPerSample * channels / 1000);
                    return;
                }
                if (size < 0) return;
                p += 8 + size + (size & 1);
            }
        } finally {
            raf.close();
        }
    }

    private static void readOggVorbis(File file, TrackFormatInfo info) throws Exception {
        RandomAccessFile raf = new RandomAccessFile(file, "r");
        try {
            byte[] b = new byte[512];
            int len = raf.read(b);
            for (int i = 0; i + 24 <= len; i++) {
                if (b[i] == 1 && match(b, i + 1, "vorbis")) {
                    info.sampleRate = readIntLE(b, i + 12);
                    int nominal = readIntLE(b, i + 20);
                    if (nominal > 0) info.kbps = nominal / 1000;
                    return;
                }
            }
        } finally {
            raf.close();
        }
    }

    private static boolean match(byte[] b, int at, String s) {
        if (at < 0 || at + s.length() > b.length) return false;
        for (int i = 0; i < s.length(); i++) if (b[at + i] != (byte) s.charAt(i)) return false;
        return true;
    }

    private static int readInt(byte[] b, int at) {
        return ((b[at] & 0xFF) << 24) | ((b[at + 1] & 0xFF) << 16) | ((b[at + 2] & 0xFF) << 8) | (b[at + 3] & 0xFF);
    }

    private static int readIntLE(byte[] b, int at) {
        return (b[at] & 0xFF) | ((b[at + 1] & 0xFF) << 8) | ((b[at + 2] & 0xFF) << 16) | ((b[at + 3] & 0xFF) << 24);
    }
}
