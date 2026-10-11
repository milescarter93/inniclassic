package com.themoon.y1;

import android.content.Context;
import android.content.SharedPreferences;

import com.themoon.y1.managers.AudiobookStore;
import com.themoon.y1.managers.PodcastStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

// 🎙️ [Podcasts & audiobooks web] The "Podcasts & Audiobooks" page of the Wi-Fi web server (Y1WebServer):
// explore and search podcasts and LibriVox audiobooks from the computer's browser, subscribe, and queue
// downloads that the Y1 then makes by itself - the same PodcastStore / AudiobookStore the Y1's own pages use.
final class MediaWeb {
    private MediaWeb() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("Y1_SETTINGS", Context.MODE_PRIVATE);
    }

    static String error(Exception e) {
        try {
            return new JSONObject().put("ok", false).put("error", e.getClass().getSimpleName()
                    + (e.getMessage() != null ? ": " + e.getMessage() : "")).toString();
        } catch (Exception x) {
            return "{\"ok\":false}";
        }
    }

    private static String ok() {
        return "{\"ok\":true}";
    }

    // ---------------------------------------------------------------- podcasts

    private static JSONArray shows(Context c, List<PodcastStore.Show> shows) throws Exception {
        JSONArray a = new JSONArray();
        for (PodcastStore.Show s : shows)
            a.put(new JSONObject().put("name", s.name).put("author", s.author).put("feed", s.feedUrl)
                    .put("art", s.artUrl).put("episodes", s.episodes)
                    .put("subscribed", PodcastStore.isSubscribed(c, s.feedUrl)));
        return a;
    }

    static String podChart(Context c, String country, int genre) {
        try {
            return new JSONObject().put("ok", true).put("shows", shows(c, PodcastStore.chart(c, country, genre))).toString();
        } catch (Exception e) {
            return error(e);
        }
    }

    static String podSearch(Context c, String q, String country) {
        try {
            return new JSONObject().put("ok", true).put("shows", shows(c, PodcastStore.search(c, q, country))).toString();
        } catch (Exception e) {
            return error(e);
        }
    }

    static String podSubscriptions(Context c) {
        try {
            JSONArray a = new JSONArray();
            long since = System.currentTimeMillis() - 30L * 86400000L;
            for (PodcastStore.Show s : PodcastStore.subscriptions(c)) {
                int fresh = 0;
                PodcastStore.Feed f = PodcastStore.cached(s);
                if (f != null) {
                    if (s.artUrl.isEmpty()) s.artUrl = f.show.artUrl;
                    for (PodcastStore.Episode e : f.episodes)
                        if (e.pubTime >= since && !PodcastStore.isPlayed(prefs(c), e)) fresh++;
                }
                a.put(new JSONObject().put("name", s.name).put("feed", s.feedUrl).put("art", s.artUrl)
                        .put("subscribed", true).put("fresh", fresh));
            }
            return new JSONObject().put("ok", true).put("shows", a).toString();
        } catch (Exception e) {
            return error(e);
        }
    }

    static String podFeed(Context c, String feedUrl, String name, String art, String author) {
        try {
            PodcastStore.Show show = new PodcastStore.Show();
            show.feedUrl = feedUrl;
            show.name = name;
            show.artUrl = art;
            show.author = author;
            PodcastStore.Feed f;
            boolean offline = false;
            try {
                f = PodcastStore.fetch(c, show);
            } catch (Exception e) {
                f = PodcastStore.cached(show);
                if (f == null) throw e;
                offline = true;
            }
            SharedPreferences p = prefs(c);
            JSONArray eps = new JSONArray();
            for (PodcastStore.Episode e : f.episodes) {
                PodcastStore.Job job = PodcastStore.jobFor(e.audioUrl);
                String state = job != null ? "downloading" : PodcastStore.isDownloaded(e) ? "downloaded"
                        : PodcastStore.isPlayed(p, e) ? "played" : "";
                eps.put(new JSONObject().put("title", e.title).put("url", e.audioUrl).put("date", e.pubTime)
                        .put("seconds", e.seconds).put("size", e.size).put("desc", e.description).put("state", state)
                        .put("played", PodcastStore.isPlayed(p, e)).put("percent", job != null ? job.percent() : 0));
            }
            return new JSONObject().put("ok", true).put("name", f.show.name.isEmpty() ? name : f.show.name)
                    .put("author", f.show.author).put("art", f.show.artUrl.isEmpty() ? art : f.show.artUrl)
                    .put("feed", feedUrl).put("offline", offline)
                    .put("subscribed", PodcastStore.isSubscribed(c, feedUrl)).put("episodes", eps).toString();
        } catch (Exception e) {
            return error(e);
        }
    }

    static String podSubscribe(Context c, String feedUrl, String name, String art, String author) {
        PodcastStore.Show s = new PodcastStore.Show();
        s.feedUrl = feedUrl;
        s.name = name;
        s.artUrl = art;
        s.author = author;
        PodcastStore.subscribe(c, s);
        new Thread(() -> {
            try {
                PodcastStore.fetch(c, s); // the offline copy, for New Episodes on the Y1
            } catch (Exception ignored) {
            }
        }).start();
        return ok();
    }

    static String podUnsubscribe(Context c, String feedUrl) {
        PodcastStore.unsubscribe(c, feedUrl);
        return ok();
    }

    static String podDownload(Context c, String show, String title, String url, long size, String art) {
        PodcastStore.Episode e = new PodcastStore.Episode();
        e.show = show;
        e.title = title;
        e.audioUrl = url;
        e.size = size;
        e.artUrl = art;
        if (PodcastStore.isDownloaded(e)) return ok();
        PodcastStore.enqueue(c, e);
        return ok();
    }

    // ---------------------------------------------------------------- audiobooks

    static String bookSearch(Context c, String lang, String q, int page) {
        try {
            JSONArray a = new JSONArray();
            for (AudiobookStore.Book b : AudiobookStore.search(c, lang, q.isEmpty() ? null : q, page))
                a.put(new JSONObject().put("id", b.id).put("title", b.title).put("author", b.author)
                        .put("downloaded", AudiobookStore.isDownloaded(b))
                        .put("downloading", AudiobookStore.jobFor(b.id) != null));
            return new JSONObject().put("ok", true).put("books", a).put("more", a.length() >= AudiobookStore.pageSize()).toString();
        } catch (Exception e) {
            return error(e);
        }
    }

    static String bookDetails(Context c, String id) {
        try {
            AudiobookStore.Job job = AudiobookStore.jobFor(id);
            AudiobookStore.Details d = job != null ? job.details : AudiobookStore.details(c, id);
            return new JSONObject().put("ok", true).put("id", id).put("title", d.book.title).put("author", d.book.author)
                    .put("chapters", d.chapters.size()).put("seconds", d.totalSeconds).put("size", d.totalBytes)
                    .put("desc", d.description).put("downloaded", AudiobookStore.isDownloaded(d.book))
                    .put("downloading", job != null).put("percent", job != null ? job.percent() : 0).toString();
        } catch (Exception e) {
            return error(e);
        }
    }

    static String bookDownload(Context c, String id) {
        try {
            AudiobookStore.Details d = AudiobookStore.details(c, id);
            if (d.chapters.isEmpty()) throw new java.io.IOException("no audio files");
            long free = AudiobookStore.freeBytes();
            if (free < d.totalBytes + 20L * 1048576)
                return new JSONObject().put("ok", false).put("error", "space").put("free", free).toString();
            if (!AudiobookStore.isDownloaded(d.book)) AudiobookStore.enqueue(c, d);
            return ok();
        } catch (Exception e) {
            return error(e);
        }
    }

    // ---------------------------------------------------------------- both

    static String downloads() {
        try {
            JSONArray a = new JSONArray();
            for (AudiobookStore.Job j : AudiobookStore.jobs())
                a.put(new JSONObject().put("type", "book").put("key", j.details.book.id).put("title", j.details.book.title)
                        .put("sub", j.details.book.author).put("running", j.running).put("percent", j.percent())
                        .put("detail", j.doneFiles + "/" + j.details.chapters.size()));
            for (PodcastStore.Job j : PodcastStore.jobs())
                a.put(new JSONObject().put("type", "episode").put("key", j.episode.audioUrl).put("title", j.episode.title)
                        .put("sub", j.episode.show).put("running", j.running).put("percent", j.percent()).put("detail", ""));
            return new JSONObject().put("ok", true).put("jobs", a).put("free", AudiobookStore.freeBytes()).toString();
        } catch (Exception e) {
            return error(e);
        }
    }

    static String cancel(Context c, String type, String key) {
        if ("book".equals(type)) AudiobookStore.cancel(c, key);
        else PodcastStore.cancel(c, key);
        return ok();
    }
}
