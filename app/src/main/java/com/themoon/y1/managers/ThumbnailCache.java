package com.themoon.y1.managers;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.io.File;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// 🚀 [Cover thumbnails] Disk cache for downscaled cover art, so album lists and Cover Flow don't re-open
// every track and decode full-size embedded pictures again after each app start.
//
// An entry's file name is a hash of (key, size, source stamp), where the stamp is derived from the
// modification times of the files the cover could come from. When any of them changes the name changes
// and the cover is rebuilt - this doesn't depend on the device clock, which is often wrong on the Y1.
// Superseded entries are left behind; "Clear Album Art & Info" empties the folder.
public class ThumbnailCache {

    private static final File DIR = new File("/storage/sdcard0/.y1_thumbs");
    // Covers are decoded on a small fixed pool instead of a new thread per list row.
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2);
    // Folder cover lookups list the whole folder; with a flat Music folder that's thousands of entries per
    // call, so the result per folder is remembered for the session.
    private static final ConcurrentHashMap<String, String> folderCoverMemo = new ConcurrentHashMap<>();
    private static final String NO_FOLDER_COVER = "";

    public static void execute(Runnable task) {
        EXECUTOR.execute(task);
    }

    // Returns the cached thumbnail, or null if there's none for this exact source stamp.
    public static Bitmap load(String key, int size, long stamp) {
        File f = entry(key, size, stamp, ".jpg");
        if (!f.isFile()) return null;
        try {
            return BitmapFactory.decodeFile(f.getAbsolutePath());
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    // True if an earlier lookup with this stamp found no cover at all.
    public static boolean isKnownMissing(String key, int size, long stamp) {
        return entry(key, size, stamp, ".none").isFile();
    }

    public static void save(String key, int size, long stamp, Bitmap bmp) {
        if (bmp == null || !ensureDir()) return;
        File target = entry(key, size, stamp, ".jpg");
        File tmp = new File(target.getPath() + ".tmp");
        try {
            FileOutputStream out = new FileOutputStream(tmp);
            try {
                bmp.compress(Bitmap.CompressFormat.JPEG, 88, out);
            } finally {
                out.close();
            }
            tmp.renameTo(target);
        } catch (Exception e) {
            tmp.delete();
        }
    }

    public static void saveMissing(String key, int size, long stamp) {
        if (!ensureDir()) return;
        try {
            new FileOutputStream(entry(key, size, stamp, ".none")).close();
        } catch (Exception e) {
        }
    }

    // Returns how many cached files were deleted.
    public static int clear() {
        folderCoverMemo.clear();
        int count = 0;
        File[] files = DIR.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isFile() && !f.getName().equals(".nomedia") && f.delete()) count++;
            }
        }
        return count;
    }

    // Memoized cover.jpg / folder.jpg lookup (see folderCoverMemo); null if the folder has none.
    public static File folderCover(File folder, com.themoon.y1.MainActivity main) {
        if (folder == null) return null;
        String dir = folder.getAbsolutePath();
        String found = folderCoverMemo.get(dir);
        if (found == null) {
            File cover = main.findFolderCover(folder);
            found = cover != null ? cover.getAbsolutePath() : NO_FOLDER_COVER;
            folderCoverMemo.put(dir, found);
        }
        return found.isEmpty() ? null : new File(found);
    }

    // Decodes an image downsampled to roughly targetSize per side (the smallest power-of-two sample size
    // that still keeps both sides >= targetSize). Returns null on failure, including out of memory.
    public static Bitmap decodeSampled(byte[] data, int targetSize) {
        if (data == null || data.length == 0) return null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, targetSize);
            return BitmapFactory.decodeByteArray(data, 0, data.length, opts);
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    public static Bitmap decodeSampled(String path, int targetSize) {
        if (path == null) return null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, bounds);
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, targetSize);
            return BitmapFactory.decodeFile(path, opts);
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    private static int sampleSize(int width, int height, int targetSize) {
        int sample = 1;
        if (width <= 0 || height <= 0 || targetSize <= 0) return sample;
        while (width / (sample * 2) >= targetSize && height / (sample * 2) >= targetSize) sample *= 2;
        return sample;
    }

    // Adds a file's modification time to a running source stamp (nothing if it doesn't exist). A plain sum,
    // so the stamp doesn't depend on the order the files come in (library order can change between scans).
    public static long mixStamp(long stamp, File f) {
        long t = (f != null && f.exists()) ? f.lastModified() : 0;
        return stamp + t;
    }

    private static File entry(String key, int size, long stamp, String ext) {
        return new File(DIR, md5(key + "|" + size + "|" + stamp) + ext);
    }

    private static boolean ensureDir() {
        if (!DIR.isDirectory() && !DIR.mkdirs()) return false;
        File noMedia = new File(DIR, ".nomedia");
        if (!noMedia.exists()) {
            try {
                new FileOutputStream(noMedia).close();
            } catch (Exception e) {
            }
        }
        return true;
    }

    private static String md5(String s) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(s.hashCode());
        }
    }
}
