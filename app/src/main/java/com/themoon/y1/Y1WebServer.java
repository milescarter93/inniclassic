package com.themoon.y1;

import android.content.Context;
import com.themoon.y1.io.SafeFiles;
import android.net.wifi.WifiManager;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class Y1WebServer extends Thread {
    private volatile ServerSocket serverSocket;
    private final java.util.Set<Socket> clients = java.util.Collections.synchronizedSet(new java.util.HashSet<Socket>());
    private final java.util.concurrent.ThreadPoolExecutor workers = new java.util.concurrent.ThreadPoolExecutor(
            3, 6, 30, TimeUnit.SECONDS, new java.util.concurrent.ArrayBlockingQueue<Runnable>(48));
    private volatile boolean running = true;
    private final File rootFolder;
    private final int port;
    private Context context;

    public Y1WebServer(Context context, File rootFolder) {
        this(context, rootFolder, 8080);
    }

    Y1WebServer(Context context, File rootFolder, int port) {
        this.context = context;
        this.rootFolder = rootFolder.getAbsoluteFile();
        this.port = port;
    }

    int getListeningPort() {
        ServerSocket socket = serverSocket;
        return socket == null ? -1 : socket.getLocalPort();
    }

    // 🚀 [Web server] The Y1's MediaTek Wi-Fi dozes whenever the Y1 itself sends nothing - even with a
    // high-performance Wi-Fi lock held - and then drops most connections from the computer (measured: 6 of
    // 20 requests answered; 20 of 20 while the Y1 was sending). So while the server runs it sends a 1-byte
    // UDP packet to the router (discard port) every 200 ms, which keeps the radio awake.
    private void keepWifiAwake() {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                java.net.DatagramSocket ds = null;
                try {
                    WifiManager wm = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
                    int gw = wm.getDhcpInfo().gateway;
                    if (gw == 0) return;
                    java.net.InetAddress router = java.net.InetAddress.getByAddress(new byte[] { (byte) (gw & 0xff),
                            (byte) (gw >> 8 & 0xff), (byte) (gw >> 16 & 0xff), (byte) (gw >> 24 & 0xff) });
                    ds = new java.net.DatagramSocket();
                    java.net.DatagramPacket packet = new java.net.DatagramPacket(new byte[1], 1, router, 9);
                    while (running) {
                        try {
                            ds.send(packet);
                        } catch (Exception ignored) {
                        }
                        Thread.sleep(200);
                    }
                } catch (Exception ignored) {
                } finally {
                    if (ds != null) ds.close();
                }
            }
        }, "Y1WebKeepAwake");
        t.setDaemon(true);
        t.start();
    }

    public void run() {
        try {
            serverSocket = new ServerSocket(port);
            keepWifiAwake();
            while (running) {
                Socket socket = serverSocket.accept();
                clients.add(socket);
                try { workers.execute(new RequestHandler(socket)); }
                catch (java.util.concurrent.RejectedExecutionException e) {
                    clients.remove(socket);
                    socket.close();
                }
            }
        } catch (Exception e) {}
        finally { stopServer(); }
    }

    public void stopServer() {
        running = false;
        workers.shutdownNow();
        synchronized (clients) {
            for (Socket client : clients) { try { client.close(); } catch (Exception ignored) {} }
            clients.clear();
        }
        try { if (serverSocket != null) serverSocket.close(); } catch(Exception e){}
    }

    // 📚 A change under Music or Audiobooks: the app rescans its library a few seconds after the last one
    // (it used to wait until the server was stopped).
    private static void libraryFileChanged(File f) {
        String path = f.getAbsolutePath();
        MainActivity main = MainActivity.instance;
        if (main != null && (path.startsWith("/storage/sdcard0/Music") || path.startsWith("/storage/sdcard0/Audiobooks")))
            main.requestLibraryRescanSoon();
    }

    private static String tr(String text) {
        MainActivity main = MainActivity.instance;
        return main != null ? main.t(text) : text;
    }

    public String getLocalIpAddress() {
        try {
            WifiManager wm = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            int ipAddress = wm.getConnectionInfo().getIpAddress();
            return String.format(Locale.US, "%d.%d.%d.%d", (ipAddress & 0xff), (ipAddress >> 8 & 0xff), (ipAddress >> 16 & 0xff), (ipAddress >> 24 & 0xff));
        } catch (Exception ex) { return "Unknown IP"; }
    }

    private void deleteFileOrFolder(File file) throws java.io.IOException {
        File base = rootFolder.getCanonicalFile();
        File canonical = file.getCanonicalFile();
        if (canonical.equals(base) || !canonical.getPath().startsWith(base.getPath() + File.separator))
            throw new java.io.IOException("Cannot delete outside shared folder");
        // Never recurse into symbolic links, including links back to an ancestor.
        if (!file.getAbsoluteFile().equals(canonical)) throw new java.io.IOException("Cannot delete symbolic link");
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) throw new java.io.IOException("Cannot read folder");
            for (File child : children) deleteFileOrFolder(child);
        }
        if (!file.delete()) throw new java.io.IOException("Cannot delete file");
    }

    // 🌐 [Web server] Translated pages kept in memory (per language) and small image thumbnails.
    private static final java.util.Map<String, byte[]> pageCache = new java.util.HashMap<>();
    private static final android.util.LruCache<String, byte[]> thumbCache = new android.util.LruCache<String, byte[]>(3 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, byte[] value) {
            return value.length;
        }
    };

    private class RequestHandler implements Runnable {
        private Socket socket;
        private boolean gzipOk;   // the browser accepts gzip
        private String range;     // "bytes=..." of the request, or null
        public RequestHandler(Socket socket) { this.socket = socket; }

        // A complete response; text over 1 KB goes gzip-compressed when the browser accepts it (the Y1's
        // Wi-Fi is the bottleneck: the Music folder's listing shrinks about 8 times).
        private void send(OutputStream os, String status, String type, byte[] body, String extraHeaders) throws java.io.IOException {
            boolean zip = gzipOk && body.length > 1024;
            if (zip) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream(body.length / 4);
                java.util.zip.GZIPOutputStream gz = new java.util.zip.GZIPOutputStream(bos);
                gz.write(body);
                gz.close();
                body = bos.toByteArray();
            }
            os.write(("HTTP/1.1 " + status + "\r\nContent-Type: " + type + "\r\nContent-Length: " + body.length
                    + (zip ? "\r\nContent-Encoding: gzip" : "") + (extraHeaders != null ? "\r\n" + extraHeaders : "")
                    + "\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
            os.write(body);
        }

        // An HTML page from assets/web, translated once per language and then served from memory.
        private void sendPage(OutputStream os, String asset) throws java.io.IOException {
            MainActivity main = MainActivity.instance;
            String key = asset + "|" + (main != null ? com.themoon.y1.managers.LanguageManager.getInstance(context).currentLangFileName : "");
            byte[] page;
            synchronized (pageCache) {
                page = pageCache.get(key);
                if (page == null) {
                    page = LibraryWeb.page(context, asset).getBytes("UTF-8");
                    pageCache.put(key, page);
                }
            }
            send(os, "200 OK", "text/html; charset=UTF-8", page, "Cache-Control: no-cache");
        }

        // The file, or the part the browser asked for with Range (lets the browser's player seek).
        private void sendFile(OutputStream os, File file, String type, String extraHeaders) throws java.io.IOException {
            long len = file.length(), start = 0, end = len - 1;
            boolean partial = false;
            if (range != null && range.startsWith("bytes=") && !range.contains(",")) {
                try {
                    String r = range.substring(6).trim();
                    int dash = r.indexOf('-');
                    String a = r.substring(0, dash).trim(), b = r.substring(dash + 1).trim();
                    if (a.isEmpty()) {
                        start = Math.max(0, len - Long.parseLong(b));
                    } else {
                        start = Long.parseLong(a);
                        if (!b.isEmpty()) end = Math.min(len - 1, Long.parseLong(b));
                    }
                    partial = true;
                } catch (Exception ignored) {
                    partial = false;
                }
            }
            if (partial && (start >= len || start > end)) {
                os.write(("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */" + len + "\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
                return;
            }
            long count = end - start + 1;
            os.write(("HTTP/1.1 " + (partial ? "206 Partial Content" : "200 OK") + "\r\nContent-Type: " + type
                    + "\r\nAccept-Ranges: bytes\r\nContent-Length: " + count
                    + (partial ? "\r\nContent-Range: bytes " + start + "-" + end + "/" + len : "")
                    + (extraHeaders != null ? "\r\n" + extraHeaders : "") + "\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
            java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file, "r");
            try {
                raf.seek(start);
                byte[] buffer = new byte[16384];
                while (count > 0) {
                    int n = raf.read(buffer, 0, (int) Math.min(buffer.length, count));
                    if (n < 0) break;
                    os.write(buffer, 0, n);
                    count -= n;
                }
            } finally {
                raf.close();
            }
        }

        private String mimeType(String name) {
            String n = name.toLowerCase(Locale.US);
            String ext = n.contains(".") ? n.substring(n.lastIndexOf('.') + 1) : "";
            switch (ext) {
                case "mp3": return "audio/mpeg";
                case "flac": return "audio/flac";
                case "wav": return "audio/wav";
                case "ogg": case "opus": return "audio/ogg";
                case "m4a": case "m4b": case "aac": return "audio/mp4";
                case "jpg": case "jpeg": return "image/jpeg";
                case "png": return "image/png";
                case "gif": return "image/gif";
                case "webp": return "image/webp";
                case "bmp": return "image/bmp";
                case "mp4": case "m4v": return "video/mp4";
                case "webm": return "video/webm";
                case "mkv": return "video/x-matroska";
                case "json": return "application/json; charset=UTF-8";
                case "txt": case "m3u": case "m3u8": case "eq": case "lrc": case "md": case "ini": case "cfg":
                case "xml": case "opml": case "log": return "text/plain; charset=UTF-8";
                default: return "application/octet-stream";
            }
        }

        private String readHeaderLine(InputStream is) throws java.io.IOException {
            StringBuilder sb = new StringBuilder();
            int c;
            while ((c = is.read()) != -1) {
                if (c == '\r') continue;
                if (c == '\n') break;
                if (sb.length() >= 8192) throw new java.io.IOException("Header too long");
                sb.append((char) c);
            }
            return sb.toString();
        }

        private String readBody(InputStream is, int contentLength) throws java.io.IOException {
            return readBody(is, contentLength, 65536);
        }

        private String readBody(InputStream is, int contentLength, int max) throws java.io.IOException {
            if (contentLength < 0 || contentLength > max) throw new java.io.IOException("Body too large");
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int totalRead = 0, bytesRead;
            while (totalRead < contentLength && (bytesRead = is.read(buffer, 0, Math.min(buffer.length, contentLength - totalRead))) != -1) {
                bos.write(buffer, 0, bytesRead);
                totalRead += bytesRead;
            }
            if (totalRead != contentLength) throw new java.io.EOFException("Incomplete body");
            return new String(bos.toByteArray(), "UTF-8");
        }

        private String formParam(String body, String key) throws java.io.IOException {
            for (String pair : body.split("&")) {
                int eq = pair.indexOf('=');
                if (eq < 0) continue;
                if (pair.substring(0, eq).equals(key)) {
                    return URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
                }
            }
            return "";
        }

        // A chart / search country the page may ask for (anything else: Spain).
        private String mediaCountry(String c) {
            return c.matches("[a-z]{2}") ? c : "es";
        }

        // One query-string parameter of the request path, decoded ("" if missing).
        private String query(String path, String key) throws java.io.IOException {
            int q = path.indexOf('?');
            if (q < 0) return "";
            return formParam(path.substring(q + 1), key);
        }

        private void writeJson(OutputStream os, String json) throws java.io.IOException {
            send(os, "200 OK", "application/json; charset=UTF-8", json.getBytes("UTF-8"), "Cache-Control: no-store");
        }

        public void run() {
            try {
                socket.setSoTimeout(15000);
                InputStream is = socket.getInputStream();
                OutputStream os = socket.getOutputStream();

                String requestLine = readHeaderLine(is);
                if (requestLine == null || requestLine.isEmpty()) return;

                String[] parts = requestLine.split(" ");
                if (parts.length != 3) throw new java.io.IOException("Invalid request");
                String method = parts[0];
                String path = parts[1];

                int contentLength = 0;
                String line;
                int headerCount = 0;
                String origin = null, host = null;
                while (!(line = readHeaderLine(is)).isEmpty()) {
                    if (++headerCount > 100) throw new java.io.IOException("Too many headers");
                    if (line.toLowerCase(Locale.US).startsWith("host:")) host = line.substring(5).trim();
                    if (line.toLowerCase(Locale.US).startsWith("origin:")) origin = line.substring(7).trim();
                    if (line.toLowerCase(Locale.US).startsWith("accept-encoding:") && line.toLowerCase(Locale.US).contains("gzip")) gzipOk = true;
                    if (line.toLowerCase(Locale.US).startsWith("range:")) range = line.substring(6).trim();
                    if (line.toLowerCase(Locale.US).startsWith("content-length:")) {
                        contentLength = Integer.parseInt(line.split(":")[1].trim());
                    }
                }

                if ("POST".equals(method) && origin != null && !origin.equals("http://" + host))
                    throw new java.io.IOException("Cross-origin write rejected");
                if (contentLength < 0) throw new java.io.IOException("Invalid length");

                // 1️⃣ 화면 UI 전송 (프론트엔드 - 인라인 플레이어 + 🚀 텍스트 에디터 탑재 + 🚀 드래그 앤 드롭 지원)
                // 📁 [Web redesign] The file manager page: assets/web/index.html (desktop and phone layouts),
                // translated like the Library and Podcasts pages.
                if (method.equals("GET") && (path.equals("/") || path.startsWith("/?"))) {
                    sendPage(os, "web/index.html");
                }
                // the card's space, for the pages' header
                else if (method.equals("GET") && path.equals("/api/storage")) {
                    android.os.StatFs fs = new android.os.StatFs(rootFolder.getAbsolutePath());
                    long block = fs.getBlockSize();
                    writeJson(os, "{\"total\":" + ((long) fs.getBlockCount() * block) + ",\"free\":" + ((long) fs.getAvailableBlocks() * block) + "}");
                }
                // 📚 [Library web] Library & playlists page and its API (see LibraryWeb).
                else if (method.equals("GET") && (path.equals("/library") || path.startsWith("/library?"))) {
                    sendPage(os, "web/library.html");
                }
                // 🎙️ [Podcasts & audiobooks web] explore, subscribe and queue downloads from the computer (see MediaWeb)
                else if (method.equals("GET") && (path.equals("/media") || path.startsWith("/media?"))) {
                    sendPage(os, "web/media.html");
                }
                else if (method.equals("GET") && path.startsWith("/api/media/chart?")) {
                    int genre = 0;
                    try {
                        genre = Integer.parseInt(query(path, "genre"));
                    } catch (NumberFormatException ignored) {
                    }
                    writeJson(os, MediaWeb.podChart(context, mediaCountry(query(path, "country")), genre));
                }
                else if (method.equals("GET") && path.startsWith("/api/media/search?")) {
                    writeJson(os, MediaWeb.podSearch(context, query(path, "q"), mediaCountry(query(path, "country"))));
                }
                else if (method.equals("GET") && path.equals("/api/media/subs")) {
                    writeJson(os, MediaWeb.podSubscriptions(context));
                }
                else if (method.equals("GET") && path.startsWith("/api/media/feed?")) {
                    writeJson(os, MediaWeb.podFeed(context, query(path, "feed"), query(path, "name"), query(path, "art"), query(path, "author")));
                }
                else if (method.equals("POST") && (path.equals("/api/media/subscribe") || path.equals("/api/media/unsubscribe"))) {
                    String body = readBody(is, contentLength);
                    String feed = formParam(body, "feed");
                    if (!feed.startsWith("http")) throw new java.io.IOException("Bad feed");
                    writeJson(os, path.endsWith("/subscribe")
                            ? MediaWeb.podSubscribe(context, feed, formParam(body, "name"), formParam(body, "art"), formParam(body, "author"))
                            : MediaWeb.podUnsubscribe(context, feed));
                }
                else if (method.equals("POST") && path.equals("/api/media/episode")) {
                    String body = readBody(is, contentLength);
                    String url = formParam(body, "url");
                    if (!url.startsWith("http")) throw new java.io.IOException("Bad url");
                    long size = 0;
                    try {
                        size = Long.parseLong(formParam(body, "size"));
                    } catch (NumberFormatException ignored) {
                    }
                    writeJson(os, MediaWeb.podDownload(context, formParam(body, "show"), formParam(body, "title"), url, size,
                            formParam(body, "art")));
                }
                else if (method.equals("GET") && path.startsWith("/api/media/books?")) {
                    int page = 1;
                    try {
                        page = Math.max(1, Integer.parseInt(query(path, "page")));
                    } catch (NumberFormatException ignored) {
                    }
                    String lang = "en".equals(query(path, "lang")) ? com.themoon.y1.managers.AudiobookStore.LANG_EN
                            : com.themoon.y1.managers.AudiobookStore.LANG_ES;
                    writeJson(os, MediaWeb.bookSearch(context, lang, query(path, "q"), page));
                }
                else if (method.equals("GET") && path.startsWith("/api/media/book?")) {
                    writeJson(os, MediaWeb.bookDetails(context, query(path, "id")));
                }
                else if (method.equals("POST") && path.equals("/api/media/book/download")) {
                    writeJson(os, MediaWeb.bookDownload(context, formParam(readBody(is, contentLength), "id")));
                }
                else if (method.equals("GET") && path.equals("/api/media/downloads")) {
                    writeJson(os, MediaWeb.downloads());
                }
                else if (method.equals("POST") && path.equals("/api/media/cancel")) {
                    String body = readBody(is, contentLength);
                    writeJson(os, MediaWeb.cancel(context, formParam(body, "type"), formParam(body, "key")));
                }
                else if (method.equals("GET") && path.equals("/api/library")) {
                    writeJson(os, LibraryWeb.songsJson(rootFolder));
                }
                else if (method.equals("GET") && path.startsWith("/api/art?")) {
                    byte[] art = LibraryWeb.art(SafeFiles.resolve(rootFolder, query(path, "path")));
                    if (art == null) {
                        os.write("HTTP/1.1 404 Not Found\r\n\r\n".getBytes("UTF-8"));
                    } else {
                        os.write(("HTTP/1.1 200 OK\r\nContent-Type: image/jpeg\r\nCache-Control: no-cache\r\nContent-Length: " + art.length + "\r\n\r\n").getBytes("UTF-8"));
                        os.write(art);
                    }
                }
                else if (method.equals("POST") && path.startsWith("/api/tags?")) {
                    File song = SafeFiles.resolve(rootFolder, query(path, "path"));
                    String body = readBody(is, contentLength);
                    java.util.Map<String, String> values = new java.util.HashMap<>();
                    for (String key : new String[] { "title", "artist", "album", "albumArtist", "year", "genre", "track" }) {
                        if (body.contains(key + "=")) values.put(key, formParam(body, key));
                    }
                    writeJson(os, LibraryWeb.saveTags(song, values));
                }
                else if (method.equals("POST") && path.startsWith("/api/cover?")) {
                    File song = SafeFiles.resolve(rootFolder, query(path, "path"));
                    if (contentLength <= 0 || contentLength > 12 * 1024 * 1024) throw new java.io.IOException("Image too large");
                    byte[] img = new byte[contentLength];
                    int read = 0, n;
                    while (read < contentLength && (n = is.read(img, read, contentLength - read)) > 0) read += n;
                    if (read != contentLength) throw new java.io.EOFException("Incomplete image");
                    writeJson(os, LibraryWeb.saveAlbumCover(song, img));
                }
                else if (method.equals("POST") && path.startsWith("/api/deletesong?")) {
                    writeJson(os, LibraryWeb.deleteSong(SafeFiles.resolve(rootFolder, query(path, "path"))));
                }
                else if (method.equals("GET") && path.equals("/api/playlists")) {
                    writeJson(os, LibraryWeb.playlistsJson());
                }
                else if (method.equals("GET") && path.startsWith("/api/playlist?")) {
                    writeJson(os, LibraryWeb.playlistJson(rootFolder, query(path, "name")));
                }
                else if (method.equals("POST") && path.startsWith("/api/playlist/save?")) {
                    String body = readBody(is, contentLength, 1024 * 1024);
                    writeJson(os, LibraryWeb.savePlaylist(rootFolder, query(path, "name"), body));
                }
                else if (method.equals("POST") && path.startsWith("/api/playlist/delete?")) {
                    writeJson(os, LibraryWeb.deletePlaylist(query(path, "name")));
                }

                // 2️⃣ [API] 파일 및 폴더 리스트 응답 (JSON 형식)
                else if (method.equals("GET") && path.startsWith("/api/list")) {
                    String q = path.contains("?") ? path.split("\\?")[1] : "";
                    String dirStr = "";
                    if (q.startsWith("dir=")) dirStr = URLDecoder.decode(q.substring(4), "UTF-8");

                    File targetDir = dirStr.isEmpty() ? rootFolder : SafeFiles.resolve(rootFolder, dirStr);
                    StringBuilder json = new StringBuilder("[");

                    if (targetDir.exists() && targetDir.isDirectory()) {
                        File[] files = targetDir.listFiles();
                        if (files != null) {
                            java.util.Arrays.sort(files, new java.util.Comparator<File>() {
                                @Override
                                public int compare(File a, File b) {
                                    return a.getName().compareToIgnoreCase(b.getName());
                                }
                            });
                            for (int i=0; i<2; i++) {
                                for (File f : files) {
                                    boolean isDir = f.isDirectory();
                                    if ((i == 0 && isDir) || (i == 1 && !isDir)) {
                                        if (json.length() > 1) json.append(",");
                                        json.append("{\"name\":").append(org.json.JSONObject.quote(f.getName())).append(",\"isDir\":").append(isDir)
                                                .append(",\"modified\":").append(f.lastModified());
                                        if (isDir) {
                                            String[] inside = f.list();
                                            json.append(",\"count\":").append(inside == null ? 0 : inside.length);
                                        } else {
                                            json.append(",\"size\":").append(f.length());
                                        }
                                        json.append("}");
                                    }
                                }
                            }
                        }
                    }
                    json.append("]");
                    writeJson(os, json.toString());
                }

                // 3️⃣ [API] 폴더 생성
                else if (method.equals("POST") && path.startsWith("/api/create")) {
                    String q = path.split("\\?")[1];
                    String[] params = q.split("&");
                    String dirStr = "", name = "";
                    for (String p : params) {
                        if (p.startsWith("dir=")) dirStr = URLDecoder.decode(p.substring(4), "UTF-8");
                        if (p.startsWith("name=")) name = URLDecoder.decode(p.substring(5), "UTF-8");
                    }
                    File targetDir = dirStr.isEmpty() ? rootFolder : SafeFiles.resolve(rootFolder, dirStr);
                    File newDir = SafeFiles.child(targetDir, name);
                    if (!newDir.isDirectory() && !newDir.mkdirs()) throw new java.io.IOException("Cannot create folder");
                    os.write("HTTP/1.1 200 OK\r\n\r\nOK".getBytes("UTF-8"));
                }

                // 4️⃣ [API] 파일 및 폴더 삭제
                else if (method.equals("POST") && path.startsWith("/api/delete")) {
                    String q = path.split("\\?")[1];
                    String targetPath = URLDecoder.decode(q.substring(5), "UTF-8");
                    File targetFile = SafeFiles.resolve(rootFolder, targetPath);
                    if (targetFile.equals(rootFolder.getCanonicalFile())) throw new java.io.IOException("Cannot delete root");
                    if (targetFile.exists()) {
                        deleteFileOrFolder(targetFile);
                        libraryFileChanged(targetFile);
                    }
                    os.write("HTTP/1.1 200 OK\r\n\r\nOK".getBytes("UTF-8"));
                }

                // 🚀 [수정 5] [API] 파일 및 폴더 이름 변경 (Rename) 엔진 장착!
                else if (method.equals("POST") && path.startsWith("/api/rename")) {
                    String q = path.split("\\?")[1];
                    String[] params = q.split("&");
                    String dirStr = "", oldName = "", newName = "";
                    for (String p : params) {
                        if (p.startsWith("dir=")) dirStr = URLDecoder.decode(p.substring(4), "UTF-8");
                        if (p.startsWith("old=")) oldName = URLDecoder.decode(p.substring(4), "UTF-8");
                        if (p.startsWith("new=")) newName = URLDecoder.decode(p.substring(4), "UTF-8");
                    }

                    File targetDir = dirStr.isEmpty() ? rootFolder : SafeFiles.resolve(rootFolder, dirStr);
                    File oldFile = SafeFiles.child(targetDir, oldName);
                    File newFile = SafeFiles.child(targetDir, newName);

                    // 기존 파일이 존재하고 새 이름의 파일이 없을 때만 안전하게 이름 변경 실행
                    if (!oldFile.exists() || newFile.exists()) throw new java.io.IOException("Invalid rename");
                    if (oldFile.exists() && !newFile.exists()) {
                        if (!oldFile.renameTo(newFile)) throw new java.io.IOException("Cannot rename file");
                        libraryFileChanged(newFile);
                    }
                    os.write("HTTP/1.1 200 OK\r\n\r\nOK".getBytes("UTF-8"));
                }

                // 5️⃣ [API] 파일 읽기 (스트리밍, 다운로드, 코드 불러오기)

                // 5️⃣ [API] 파일 읽기 (스트리밍, 다운로드, 코드 불러오기)
                else if (method.equals("GET") && path.startsWith("/api/thumb?")) {
                    File image = SafeFiles.resolve(rootFolder, query(path, "path"));
                    int size = 96;
                    try {
                        size = Math.max(32, Math.min(400, Integer.parseInt(query(path, "size"))));
                    } catch (NumberFormatException ignored) {
                    }
                    String key = image.getAbsolutePath() + "|" + image.lastModified() + "|" + size;
                    byte[] jpeg = thumbCache.get(key);
                    if (jpeg == null && image.isFile()) {
                        android.graphics.Bitmap bmp = com.themoon.y1.managers.ThumbnailCache.decodeSampled(image.getAbsolutePath(), size);
                        if (bmp != null) {
                            ByteArrayOutputStream bos = new ByteArrayOutputStream();
                            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, bos);
                            bmp.recycle();
                            jpeg = bos.toByteArray();
                            thumbCache.put(key, jpeg);
                        }
                    }
                    if (jpeg == null) os.write("HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n".getBytes("UTF-8"));
                    else send(os, "200 OK", "image/jpeg", jpeg, "Cache-Control: max-age=3600");
                }
                else if (method.equals("GET") && path.startsWith("/api/file")) {
                    File targetFile = SafeFiles.resolve(rootFolder, query(path, "path"));
                    if (!targetFile.exists() || targetFile.isDirectory()) {
                        os.write("HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\nNot Found".getBytes("UTF-8"));
                    } else {
                        sendFile(os, targetFile, mimeType(targetFile.getName()), "Cache-Control: no-cache");
                    }
                }

                // 6️⃣ [API] 파일 업로드
                else if (method.equals("POST") && path.startsWith("/api/upload")) {
                    String q = path.split("\\?")[1];
                    String[] params = q.split("&");
                    String dirStr = "", name = "unnamed.file";
                    for (String p : params) {
                        if (p.startsWith("dir=")) dirStr = URLDecoder.decode(p.substring(4), "UTF-8");
                        if (p.startsWith("name=")) name = URLDecoder.decode(p.substring(5), "UTF-8");
                    }

                    File targetDir = dirStr.isEmpty() ? rootFolder : SafeFiles.resolve(rootFolder, dirStr);
                    if (!targetDir.isDirectory() && !targetDir.mkdirs()) throw new java.io.IOException("Cannot create folder");
                    File outFile = SafeFiles.child(targetDir, name);

                    SafeFiles.replace(outFile, is, contentLength);
                    libraryFileChanged(outFile); // 📚 new songs show up without stopping the server

                    os.write("HTTP/1.1 200 OK\r\n\r\nOK".getBytes("UTF-8"));
                }

                // 7️⃣ [API] 🚀 텍스트 파일 저장 (코드 에디터에서 전송된 텍스트를 기기에 덮어쓰기)
                else if (method.equals("POST") && path.startsWith("/api/save")) {
                    String q = path.split("\\?")[1];
                    String targetPath = URLDecoder.decode(q.substring(5), "UTF-8");
                    File targetFile = SafeFiles.resolve(rootFolder, targetPath);

                    SafeFiles.replace(targetFile, is, contentLength);

                    os.write("HTTP/1.1 200 OK\r\n\r\nOK".getBytes("UTF-8"));
                }
                else if (method.equals("GET") && path.startsWith("/api/download")) {
                    String q = path.split("\\?")[1];
                    String targetPath = URLDecoder.decode(q.substring(5), "UTF-8");
                    File targetFile = SafeFiles.resolve(rootFolder, targetPath);

                    if (!targetFile.exists() || targetFile.isDirectory()) {
                        os.write("HTTP/1.1 404 Not Found\r\n\r\nNot Found".getBytes("UTF-8"));
                    } else {
                        // 💡 브라우저가 화면에 재생하지 않고 "무조건 파일로 저장"하게 만드는 Content-Disposition 헤더!
                        // the name in plain ASCII for old browsers, and exact (UTF-8) for the others
                        String ascii = targetFile.getName().replaceAll("[^\\x20-\\x7E]", "_").replaceAll("[\"\\\\]", "_");
                        String utf8 = URLEncoder.encode(targetFile.getName(), "UTF-8").replace("+", "%20");
                        sendFile(os, targetFile, "application/octet-stream",
                                "Content-Disposition: attachment; filename=\"" + ascii + "\"; filename*=UTF-8''" + utf8);
                    }
                }
                // 8️⃣ [Last.fm] 로그인 페이지 (PC 브라우저에서 편하게 아이디/비번 입력용)
                else if (method.equals("GET") && path.equals("/lastfm")) {
                    sendPage(os, "web/lastfm.html");
                }
                // [API] Last.fm 로그인 상태 조회
                else if (method.equals("GET") && path.startsWith("/api/lastfm/status")) {
                    com.themoon.y1.managers.LastFmScrobbler s = com.themoon.y1.managers.LastFmScrobbler.getInstance(context);
                    String json = "{\"loggedIn\":" + s.isLoggedIn() + ",\"username\":\"" + s.getUsername().replace("\"", "\\\"") +
                            "\",\"enabled\":" + s.isEnabled() + "}";
                    writeJson(os, json);
                }
                // [API] Last.fm 로그인 (아이디/비번은 PC 브라우저에서 입력받아 이 엔드포인트로 전송됨)
                else if (method.equals("POST") && path.startsWith("/api/lastfm/login")) {
                    String body = readBody(is, contentLength);
                    String username = formParam(body, "username");
                    String password = formParam(body, "password");

                    final CountDownLatch latch = new CountDownLatch(1);
                    final AtomicBoolean success = new AtomicBoolean(false);
                    final AtomicReference<String> message = new AtomicReference<>("Login failed");

                    com.themoon.y1.managers.LastFmScrobbler.getInstance(context).login(username, password,
                            new com.themoon.y1.managers.LastFmScrobbler.LoginCallback() {
                                @Override
                                public void onResult(boolean ok, String msg) {
                                    success.set(ok);
                                    message.set(msg);
                                    latch.countDown();
                                }
                            });
                    latch.await(15, TimeUnit.SECONDS);

                    String json = "{\"success\":" + success.get() + ",\"message\":\"" +
                            String.valueOf(message.get()).replace("\"", "\\\"") + "\"}";
                    writeJson(os, json);
                }
                // [API] Last.fm 로그아웃
                else if (method.equals("POST") && path.startsWith("/api/lastfm/logout")) {
                    com.themoon.y1.managers.LastFmScrobbler.getInstance(context).logout();
                    writeJson(os, "{\"ok\":true}");
                }
                // [API] 스크로블링 ON/OFF 토글
                else if (method.equals("POST") && path.startsWith("/api/lastfm/enabled")) {
                    String q = path.contains("?") ? path.split("\\?")[1] : "";
                    boolean value = q.contains("value=true");
                    com.themoon.y1.managers.LastFmScrobbler.getInstance(context).setEnabled(value);
                    writeJson(os, "{\"ok\":true}");
                }
                else { os.write("HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\nNot Found".getBytes("UTF-8")); }
                os.flush();
            } catch (Exception e) {
                try { socket.getOutputStream().write("HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\nRequest failed".getBytes("UTF-8")); } catch (Exception ignored) {}
            }
            finally {
                clients.remove(socket);
                try { socket.close(); } catch (Exception e) {}
            }
        }
    }
}