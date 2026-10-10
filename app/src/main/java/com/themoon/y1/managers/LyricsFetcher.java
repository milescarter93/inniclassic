package com.themoon.y1.managers;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URLEncoder;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

// 🚀 [Lyrics] Synced lyrics from LRCLIB (https://lrclib.net, free and open), saved as "<song>.lrc" next to
// the song - where the lyrics view already looks. Plain (unsynced) lyrics are saved the same way, and an
// instrumental gets a one-line ♪ file. Songs LRCLIB doesn't know are remembered for 30 days so they aren't
// asked for again.
// HTTPS is verified: the system's roots plus a few bundled ones (assets/certs), since Android 4.2's store
// predates the roots LRCLIB's certificates chain to.
public final class LyricsFetcher {
    private static final String TAG = "Y1Lyrics";
    private static final String UA = "InniClassic-Y1 (https://github.com/milescarter93/inniclassic)";
    private static final long MISSING_RETRY_MS = 30L * 24 * 60 * 60 * 1000;

    public static final int FOUND = 1, NOT_FOUND = 2;

    // LRCLIB answered "busy" / "too many requests": wait and try again later.
    public static final class BusyException extends Exception {
        BusyException(String msg) {
            super(msg);
        }
    }

    private static okhttp3.OkHttpClient client;
    private static volatile boolean loggedValidator;

    // (also used by AudiobookStore: same verified HTTPS, for archive.org)
    public static synchronized okhttp3.OkHttpClient client(Context context) throws Exception {
        if (client != null) return client;
        final X509TrustManager system = defaultTrustManager(null);
        KeyStore extra = KeyStore.getInstance(KeyStore.getDefaultType());
        extra.load(null, null);
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        String[] files = context.getAssets().list("certs");
        int n = 0;
        if (files != null) {
            for (String f : files) {
                InputStream in = context.getAssets().open("certs/" + f);
                try {
                    extra.setCertificateEntry(f, cf.generateCertificate(in));
                    n++;
                } finally {
                    in.close();
                }
            }
        }
        // The system store plus the bundled roots, validated by Conscrypt: Android 4.2's own validator can't
        // check the ECDSA signatures of today's certificates (LRCLIB: WE1 / GTS Root R4), so it reported
        // "Trust anchor for certification path not found" although the chain leads to GlobalSign Root CA.
        KeyStore all = KeyStore.getInstance(KeyStore.getDefaultType());
        all.load(null, null);
        try {
            KeyStore androidCa = KeyStore.getInstance("AndroidCAStore");
            androidCa.load(null, null);
            java.util.Enumeration<String> aliases = androidCa.aliases();
            while (aliases.hasMoreElements()) {
                String a = aliases.nextElement();
                java.security.cert.Certificate cert = androidCa.getCertificate(a);
                if (cert != null) all.setCertificateEntry(a, cert);
            }
        } catch (Exception e) {
            Log.w(TAG, "No AndroidCAStore: " + e);
        }
        // AndroidCAStore on this device only lists part of its roots (63 of 179, without GlobalSign Root CA):
        // read the system's certificate files too.
        int fromFiles = 0, failed = 0;
        String firstError = null;
        File[] caFiles = new File("/system/etc/security/cacerts").listFiles();
        if (caFiles != null) {
            for (File f : caFiles) {
                try {
                    X509Certificate cert = pemFile(cf, f);
                    if (cert != null) {
                        all.setCertificateEntry("file:" + f.getName(), cert);
                        fromFiles++;
                        if (f.getName().equals("b0f3e76e.0"))
                            Log.i(TAG, "GlobalSign Root CA read: " + cert.getSubjectX500Principal().getName() + " entries now " + all.size());
                    }
                } catch (Exception e) {
                    failed++;
                    if (firstError == null) firstError = f.getName() + ": " + e;
                }
            }
        }
        Log.i(TAG, "Trusted roots: " + all.size() + " (" + fromFiles + " of " + (caFiles == null ? 0 : caFiles.length)
                + " system files read, " + failed + " failed" + (firstError != null ? ", e.g. " + firstError : "") + ")");
        java.util.Enumeration<String> mine = extra.aliases();
        while (mine.hasMoreElements()) {
            String a = mine.nextElement();
            all.setCertificateEntry("bundled:" + a, extra.getCertificate(a));
        }
        // (Conscrypt's own validator needs Android 7: here the chain is checked by ChainCheck below, with
        // the signatures verified through the installed providers - Conscrypt first, which knows ECDSA.)
        final X509TrustManager modern = new ChainCheck(all);
        final X509TrustManager bundled = defaultTrustManager(extra);
        X509TrustManager both = new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) throws java.security.cert.CertificateException {
                system.checkClientTrusted(chain, authType);
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) throws java.security.cert.CertificateException {
                // Each is a real chain check; the first that accepts the chain wins.
                java.security.cert.CertificateException last = null;
                StringBuilder reasons = new StringBuilder();
                for (X509TrustManager tm : new X509TrustManager[] { system, modern, bundled }) {
                    if (tm == null) continue;
                    try {
                        tm.checkServerTrusted(chain, authType);
                        if (!loggedValidator) {
                            loggedValidator = true;
                            Log.i(TAG, "Certificate accepted by " + (tm == modern ? "the chain check" : tm == system ? "the system" : "the bundled roots"));
                        }
                        return;
                    } catch (java.security.cert.CertificateException e) {
                        last = e;
                        reasons.append("\n  ").append(tm == modern ? "chain check" : tm == system ? "system" : "bundled")
                                .append(": ").append(e.getMessage());
                    }
                }
                Log.w(TAG, "Certificate rejected:" + reasons);
                throw last;
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return system.getAcceptedIssuers();
            }
        };
        SSLContext ssl;
        try {
            ssl = SSLContext.getInstance("TLS", "Conscrypt"); // TLS 1.2 and ECDSA on Android 4.2
        } catch (Exception e) {
            ssl = SSLContext.getInstance("TLS");
        }
        ssl.init(null, new TrustManager[] { both }, null);
        // 🚀 [Podcasts] Streams (ExoPlayer uses HttpsURLConnection) get the same check: the system first,
        // then the chain check and the bundled roots - Android 4.2's own validator alone rejects today's
        // ECDSA chains.
        javax.net.ssl.HttpsURLConnection.setDefaultSSLSocketFactory(ssl.getSocketFactory());
        client = new okhttp3.OkHttpClient.Builder()
                .sslSocketFactory(ssl.getSocketFactory(), both)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build();
        Log.i(TAG, "HTTPS client ready (" + n + " bundled roots)");
        return client;
    }

    // A plain chain check for when Android 4.2's validator fails on ECDSA certificates: every certificate is
    // within its validity dates and signed by the next one, every issuer is a CA, and the chain ends at (or
    // is signed by) one of the trusted roots. The host name is checked separately by OkHttp.
    static final class ChainCheck implements X509TrustManager {
        private final java.util.List<X509Certificate> roots = new java.util.ArrayList<>();

        ChainCheck(KeyStore store) throws Exception {
            java.util.Enumeration<String> aliases = store.aliases();
            while (aliases.hasMoreElements()) {
                java.security.cert.Certificate c = store.getCertificate(aliases.nextElement());
                if (c instanceof X509Certificate) roots.add((X509Certificate) c);
            }
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws java.security.cert.CertificateException {
            throw new java.security.cert.CertificateException("Client certificates are not used");
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws java.security.cert.CertificateException {
            if (chain == null || chain.length == 0) throw new java.security.cert.CertificateException("Empty chain");
            try {
                for (int i = 0; i < chain.length; i++) {
                    X509Certificate c = chain[i];
                    c.checkValidity();
                    for (X509Certificate r : roots) {
                        if (r.equals(c)) return; // reached a trusted root
                        if (r.getSubjectX500Principal().equals(c.getIssuerX500Principal())) {
                            try {
                                c.verify(r.getPublicKey());
                                r.checkValidity();
                                return; // signed by a trusted root
                            } catch (java.security.GeneralSecurityException ignored) {
                                // another root with the same name
                            }
                        }
                    }
                    if (i + 1 >= chain.length) break;
                    X509Certificate issuer = chain[i + 1];
                    if (!issuer.getSubjectX500Principal().equals(c.getIssuerX500Principal()))
                        throw new java.security.cert.CertificateException("Chain out of order at " + i);
                    if (issuer.getBasicConstraints() < 0)
                        throw new java.security.cert.CertificateException("Issuer is not a CA: " + issuer.getSubjectX500Principal());
                    c.verify(issuer.getPublicKey());
                }
            } catch (java.security.cert.CertificateException e) {
                throw e;
            } catch (java.security.GeneralSecurityException e) {
                throw new java.security.cert.CertificateException(e);
            }
            StringBuilder why = new StringBuilder("Chain does not end at a trusted root (" + roots.size() + " roots); chain:");
            for (X509Certificate c : chain)
                why.append(" [").append(c.getSubjectX500Principal().getName()).append(" <- ").append(c.getIssuerX500Principal().getName()).append(']');
            X509Certificate lastCert = chain[chain.length - 1];
            for (X509Certificate r : roots)
                if (r.getSubjectX500Principal().getName().equals(lastCert.getIssuerX500Principal().getName()))
                    why.append(" root with that name found: ").append(r.getSubjectX500Principal().equals(lastCert.getIssuerX500Principal()) ? "equal" : "NOT equal");
            throw new java.security.cert.CertificateException(why.toString());
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return roots.toArray(new X509Certificate[0]);
        }
    }

    // The certificate in an Android cacerts file (PEM, followed by a text dump).
    private static X509Certificate pemFile(CertificateFactory cf, File f) throws Exception {
        byte[] data = new byte[(int) f.length()];
        java.io.DataInputStream in = new java.io.DataInputStream(new java.io.FileInputStream(f));
        try {
            in.readFully(data);
        } finally {
            in.close();
        }
        String text = new String(data, "ISO-8859-1");
        int a = text.indexOf("-----BEGIN CERTIFICATE-----"), b = text.indexOf("-----END CERTIFICATE-----");
        if (a < 0 || b < a) return null;
        byte[] der = android.util.Base64.decode(text.substring(a + 27, b), android.util.Base64.DEFAULT);
        return (X509Certificate) cf.generateCertificate(new java.io.ByteArrayInputStream(der));
    }

    private static X509TrustManager defaultTrustManager(KeyStore ks) throws Exception {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        for (TrustManager tm : tmf.getTrustManagers())
            if (tm instanceof X509TrustManager) return (X509TrustManager) tm;
        throw new IllegalStateException("No X509TrustManager");
    }

    public static File lrcFileFor(File song) {
        String name = song.getName();
        int dot = name.lastIndexOf('.');
        return new File(song.getParentFile(), (dot > 0 ? name.substring(0, dot) : name) + ".lrc");
    }

    private static File missingList(Context c) {
        return new File(c.getFilesDir(), "lyrics_missing.txt");
    }

    // True if LRCLIB had nothing for this song less than 30 days ago.
    public static synchronized boolean recentlyMissing(Context c, File song) {
        File f = missingList(c);
        if (!f.exists()) return false;
        String key = song.getAbsolutePath() + "\t";
        try {
            java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8"));
            try {
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.startsWith(key)) {
                        long when = Long.parseLong(line.substring(key.length()).trim());
                        return System.currentTimeMillis() - when < MISSING_RETRY_MS;
                    }
                }
            } finally {
                br.close();
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private static synchronized void rememberMissing(Context c, File song) {
        try {
            FileOutputStream out = new FileOutputStream(missingList(c), true);
            out.write((song.getAbsolutePath() + "\t" + System.currentTimeMillis() + "\n").getBytes("UTF-8"));
            out.close();
        } catch (Exception ignored) {
        }
    }

    private static String enc(String s) throws Exception {
        return URLEncoder.encode(s == null ? "" : s, "UTF-8");
    }

    private static JSONObject getJson(Context c, String url) throws Exception {
        okhttp3.Request req = new okhttp3.Request.Builder().url(url).header("User-Agent", UA).build();
        okhttp3.Response res = client(c).newCall(req).execute();
        try {
            int code = res.code();
            if (code == 404) return null;
            if (code == 429 || code >= 500) throw new BusyException("HTTP " + code);
            if (code != 200) throw new java.io.IOException("HTTP " + code);
            String body = res.body().string();
            return body.trim().startsWith("[") ? new JSONObject().put("results", new JSONArray(body)) : new JSONObject(body);
        } finally {
            res.close();
        }
    }

    // Looks the song up and writes its .lrc. FOUND or NOT_FOUND; BusyException when LRCLIB asks to wait;
    // other exceptions for network problems.
    public static int fetch(Context c, File song, String title, String artist, String album, long durationMs)
            throws Exception {
        long sec = Math.round(durationMs / 1000.0);
        JSONObject hit = null;
        if (sec > 0 && artist != null && !artist.isEmpty()) {
            hit = getJson(c, "https://lrclib.net/api/get?track_name=" + enc(title) + "&artist_name=" + enc(artist)
                    + "&album_name=" + enc(album) + "&duration=" + sec);
        }
        if (hit == null) {
            // Not an exact match (album or length differ): search, and take the closest length, synced first.
            JSONObject res = getJson(c, "https://lrclib.net/api/search?track_name=" + enc(title)
                    + (artist != null && !artist.isEmpty() ? "&artist_name=" + enc(artist) : ""));
            JSONArray arr = res != null ? res.optJSONArray("results") : null;
            double best = Double.MAX_VALUE;
            for (int i = 0; arr != null && i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                double diff = sec > 0 ? Math.abs(o.optDouble("duration", 0) - sec) : 0;
                if (sec > 0 && diff > 3) continue;
                double score = diff + (o.isNull("syncedLyrics") ? 100 : 0);
                if (score < best) {
                    best = score;
                    hit = o;
                }
            }
        }
        if (hit == null) {
            rememberMissing(c, song);
            Log.i(TAG, "Not found: " + artist + " - " + title);
            return NOT_FOUND;
        }
        String text;
        if (hit.optBoolean("instrumental", false)) text = "[00:00.00]♪ Instrumental ♪\n";
        else if (!hit.isNull("syncedLyrics") && !hit.optString("syncedLyrics").trim().isEmpty()) text = hit.getString("syncedLyrics");
        else if (!hit.isNull("plainLyrics") && !hit.optString("plainLyrics").trim().isEmpty()) text = hit.getString("plainLyrics");
        else {
            rememberMissing(c, song);
            return NOT_FOUND;
        }
        File lrc = lrcFileFor(song);
        File tmp = new File(lrc.getParentFile(), "." + lrc.getName() + ".tmp");
        FileOutputStream out = new FileOutputStream(tmp);
        out.write(text.getBytes("UTF-8"));
        out.close();
        lrc.delete();
        if (!tmp.renameTo(lrc)) {
            tmp.delete();
            throw new java.io.IOException("Cannot save " + lrc.getName());
        }
        Log.i(TAG, "Saved " + lrc.getName() + (text.startsWith("[") ? " (synced)" : " (plain)"));
        return FOUND;
    }
}
