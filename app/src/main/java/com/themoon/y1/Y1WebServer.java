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
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class Y1WebServer extends Thread {
    private volatile ServerSocket serverSocket;
    private final java.util.Set<Socket> clients = java.util.Collections.synchronizedSet(new java.util.HashSet<Socket>());
    private final java.util.concurrent.ThreadPoolExecutor workers = new java.util.concurrent.ThreadPoolExecutor(
            2, 4, 30, TimeUnit.SECONDS, new java.util.concurrent.ArrayBlockingQueue<Runnable>(8));
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

    private class RequestHandler implements Runnable {
        private Socket socket;
        public RequestHandler(Socket socket) { this.socket = socket; }

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
            os.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n" + json).getBytes("UTF-8"));
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
                    byte[] page = LibraryWeb.page(context, "web/index.html").getBytes("UTF-8");
                    os.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=UTF-8\r\nContent-Length: " + page.length + "\r\n\r\n").getBytes("UTF-8"));
                    os.write(page);
                }
                // the card's space, for the pages' header
                else if (method.equals("GET") && path.equals("/api/storage")) {
                    android.os.StatFs fs = new android.os.StatFs(rootFolder.getAbsolutePath());
                    long block = fs.getBlockSize();
                    writeJson(os, "{\"total\":" + ((long) fs.getBlockCount() * block) + ",\"free\":" + ((long) fs.getAvailableBlocks() * block) + "}");
                }
                // 📚 [Library web] Library & playlists page and its API (see LibraryWeb).
                else if (method.equals("GET") && (path.equals("/library") || path.startsWith("/library?"))) {
                    byte[] page = LibraryWeb.page(context).getBytes("UTF-8");
                    os.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=UTF-8\r\nContent-Length: " + page.length + "\r\n\r\n").getBytes("UTF-8"));
                    os.write(page);
                }
                // 🎙️ [Podcasts & audiobooks web] explore, subscribe and queue downloads from the computer (see MediaWeb)
                else if (method.equals("GET") && (path.equals("/media") || path.startsWith("/media?"))) {
                    byte[] page = LibraryWeb.page(context, "web/media.html").getBytes("UTF-8");
                    os.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=UTF-8\r\nContent-Length: " + page.length + "\r\n\r\n").getBytes("UTF-8"));
                    os.write(page);
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
                    os.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n" + json.toString()).getBytes("UTF-8"));
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
                else if (method.equals("GET") && path.startsWith("/api/file")) {
                    String q = path.split("\\?")[1];
                    String targetPath = URLDecoder.decode(q.substring(5), "UTF-8");
                    File targetFile = SafeFiles.resolve(rootFolder, targetPath);

                    if (!targetFile.exists() || targetFile.isDirectory()) {
                        os.write("HTTP/1.1 404 Not Found\r\n\r\nNot Found".getBytes("UTF-8"));
                    } else {
                        String mimeType = "application/octet-stream";
                        String lowerName = targetFile.getName().toLowerCase();
                        if (lowerName.endsWith(".mp3")) mimeType = "audio/mpeg";
                        else if (lowerName.endsWith(".flac")) mimeType = "audio/flac";
                        else if (lowerName.endsWith(".wav")) mimeType = "audio/wav";
                        else if (lowerName.endsWith(".ogg")) mimeType = "audio/ogg";
                        else if (lowerName.endsWith(".m4a") || lowerName.endsWith(".aac")) mimeType = "audio/mp4";
                        else if (lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg")) mimeType = "image/jpeg";
                        else if (lowerName.endsWith(".png")) mimeType = "image/png";
                        else if (lowerName.endsWith(".json")) mimeType = "application/json";
                            // 🚀 [수정 2] 방금 추가한 파일들도 브라우저가 순수한 '글자'로 인식해서 에디터 창에 띄우도록 명시!
                        else if (lowerName.endsWith(".txt") || lowerName.endsWith(".m3u") || lowerName.endsWith(".m3u8") || lowerName.endsWith(".eq")) mimeType = "text/plain";

                        String header = "HTTP/1.1 200 OK\r\n" +
                                "Content-Type: " + mimeType + "\r\n" +
                                "Content-Length: " + targetFile.length() + "\r\n" +
                                "Connection: close\r\n\r\n";
                        os.write(header.getBytes("UTF-8"));

                        try (FileInputStream fis = new FileInputStream(targetFile)) {
                        byte[] buffer = new byte[8192];
                        int bytesRead;
                        while ((bytesRead = fis.read(buffer)) != -1) {
                            os.write(buffer, 0, bytesRead);
                        }
                        }
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
                        String header = "HTTP/1.1 200 OK\r\n" +
                                "Content-Type: application/octet-stream\r\n" +
                                "Content-Disposition: attachment; filename=\"" + targetFile.getName().replaceAll("[\\r\\n\"\\\\]", "_") + "\"\r\n" +
                                "Content-Length: " + targetFile.length() + "\r\n" +
                                "Connection: close\r\n\r\n";
                        os.write(header.getBytes("UTF-8"));

                        try (FileInputStream fis = new FileInputStream(targetFile)) {
                        byte[] buffer = new byte[8192];
                        int bytesRead;
                        while ((bytesRead = fis.read(buffer)) != -1) {
                            os.write(buffer, 0, bytesRead);
                        }
                        }
                    }
                }
                // 8️⃣ [Last.fm] 로그인 페이지 (PC 브라우저에서 편하게 아이디/비번 입력용)
                else if (method.equals("GET") && path.equals("/lastfm")) {
                    String html = "<!DOCTYPE html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width, initial-scale=1'>" +
                            "<title>Y1 Last.fm Settings</title><style>" +
                            "body{font-family:'Roboto','Segoe UI',sans-serif; background:#1E1E24; color:#E0E0E0; padding:20px; max-width:480px; margin:0 auto;} " +
                            "a{color:#B39DDB;} " +
                            "input{width:100%; box-sizing:border-box; font-size:15px; padding:12px; margin:6px 0; border-radius:12px; border:none; background:#2A2A35; color:#E0E0E0; outline:none;} " +
                            "button{width:100%; font-size:15px; padding:12px; margin:6px 0; border-radius:20px; border:none; background:#B39DDB; color:#121212; font-weight:600; cursor:pointer;} " +
                            "button:hover{background:#9575CD;} " +
                            "button.danger{background:#424250; color:#E57373;} " +
                            ".box{background:#252530; padding:20px; border-radius:16px; margin:15px 0;} " +
                            "#msg{margin-top:10px; font-weight:600;}" +
                            "</style></head><body>" +
                            "<h2>🎧 Last.fm Scrobbling</h2>" +
                            "<p><a href='/'>&larr; Back to File Manager</a></p>" +
                            "<div class='box' id='statusBox'>Loading...</div>" +
                            "<div class='box' id='loginBox' style='display:none;'>" +
                            "<input id='u' type='text' placeholder='Last.fm username' autocapitalize='off' autocorrect='off'>" +
                            "<input id='p' type='password' placeholder='Last.fm password'>" +
                            "<button onclick='doLogin()'>Log In</button>" +
                            "<div id='msg'></div>" +
                            "</div>" +
                            "<script>" +
                            "function refreshStatus() {" +
                            "  fetch('/api/lastfm/status').then(r=>r.json()).then(s => {" +
                            "    let box = document.getElementById('statusBox');" +
                            "    if (s.loggedIn) {" +
                            "      box.innerHTML = `<p>Logged in as <b id='lastfmUsername'></b></p>` +" +
                            "        `<button onclick='setEnabled(${!s.enabled})'>Turn Scrobbling ${s.enabled ? 'OFF' : 'ON'}</button>` +" +
                            "        `<p>Scrobbling is currently <b>${s.enabled ? 'ON' : 'OFF'}</b></p>` +" +
                            "        `<button class='danger' onclick='doLogout()'>Log Out</button>`;" +
                            "      document.getElementById('lastfmUsername').textContent = '@' + s.username;" +
                            "      document.getElementById('loginBox').style.display = 'none';" +
                            "    } else {" +
                            "      box.innerHTML = '<p>Not logged in to Last.fm.</p>';" +
                            "      document.getElementById('loginBox').style.display = 'block';" +
                            "    }" +
                            "  });" +
                            "}" +
                            "function doLogin() {" +
                            "  let u = document.getElementById('u').value.trim();" +
                            "  let p = document.getElementById('p').value;" +
                            "  let msg = document.getElementById('msg');" +
                            "  if (!u || !p) { msg.style.color='#E57373'; msg.innerText='Please fill in both fields.'; return; }" +
                            "  msg.style.color='#B39DDB'; msg.innerText='Logging in...';" +
                            "  fetch('/api/lastfm/login', {method:'POST', body:'username='+encodeURIComponent(u)+'&password='+encodeURIComponent(p)})" +
                            "    .then(r=>r.json()).then(res => {" +
                            "      if (res.success) { msg.style.color='#81C784'; msg.innerText='✅ Logged in as @' + res.message; refreshStatus(); }" +
                            "      else { msg.style.color='#E57373'; msg.innerText='❌ ' + res.message; }" +
                            "    });" +
                            "}" +
                            "function doLogout() { fetch('/api/lastfm/logout', {method:'POST'}).then(refreshStatus); }" +
                            "function setEnabled(v) { fetch('/api/lastfm/enabled?value=' + v, {method:'POST'}).then(refreshStatus); }" +
                            "refreshStatus();" +
                            "</script></body></html>";
                    os.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=UTF-8\r\n\r\n" + html).getBytes("UTF-8"));
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