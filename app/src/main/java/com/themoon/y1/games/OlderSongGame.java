package com.themoon.y1.games;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.media.MediaMetadataRetriever;
import android.text.TextPaint;
import android.text.TextUtils;

import com.themoon.y1.managers.ThumbnailCache;
import com.themoon.y1.models.SongItem;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

// 🚀 [Games] Which Came First? Two songs from the library, with their covers: pick the older one (the
// year tag). The wheel chooses left or right, Center answers. Three mistakes end the game.
public class OlderSongGame extends GameView {
    private final List<SongItem> songs = new ArrayList<>(); // songs with a usable year
    private final Random random = new Random();
    private final SongItem[] pair = new SongItem[2];
    private final int[] years = new int[2];
    private final Bitmap[] covers = new Bitmap[2];
    private int choice, lives, round;
    private boolean revealed, right;
    private float revealTimer;
    private final TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint coverPaint = new Paint(Paint.FILTER_BITMAP_FLAG); // opaque, whatever the card colour

    public OlderSongGame(Context context, Host host) {
        super(context, host);
        for (SongItem s : host.library()) if (yearOf(s) > 0) songs.add(s);
    }

    static int yearOf(SongItem s) {
        if (s.year == null) return 0;
        String y = s.year.trim();
        if (y.length() < 4) return 0;
        try {
            int v = Integer.parseInt(y.substring(0, 4));
            return v >= 1900 && v <= 2100 ? v : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    @Override
    public String id() {
        return "older";
    }

    @Override
    protected String[] instructions() {
        return new String[] { t("Which song came out first?"), t("Wheel: choose  ·  Center: answer") };
    }

    @Override
    protected String cannotPlay() {
        return songs.size() < 2 ? t("Your songs need a year in their tags to play this.") : null;
    }

    @Override
    protected void reset() {
        lives = 3;
        round = 0;
    }

    @Override
    protected void onStarted() {
        lives = 3;
        round = 0;
        nextPair();
    }

    private void nextPair() {
        round++;
        revealed = false;
        choice = 0;
        // two different years (and preferably different artists)
        for (int tries = 0; tries < 200; tries++) {
            SongItem a = songs.get(random.nextInt(songs.size())), b = songs.get(random.nextInt(songs.size()));
            if (yearOf(a) == yearOf(b)) continue;
            if (tries < 150 && a.artist != null && a.artist.equals(b.artist)) continue;
            pair[0] = a;
            pair[1] = b;
            break;
        }
        if (pair[0] == null || pair[1] == null || yearOf(pair[0]) == yearOf(pair[1])) {
            gameOver(); // the library has only one year
            return;
        }
        years[0] = yearOf(pair[0]);
        years[1] = yearOf(pair[1]);
        covers[0] = covers[1] = null;
        final SongItem[] wanted = { pair[0], pair[1] };
        final int size = (int) (130 * dp);
        new Thread(new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < 2; i++) {
                    Bitmap bmp = null;
                    MediaMetadataRetriever r = new MediaMetadataRetriever();
                    try {
                        r.setDataSource(wanted[i].file.getAbsolutePath());
                        bmp = ThumbnailCache.decodeSampled(r.getEmbeddedPicture(), size);
                    } catch (Exception ignored) {
                    } finally {
                        try {
                            r.release();
                        } catch (Exception ignored) {
                        }
                    }
                    final int k = i;
                    final Bitmap result = bmp;
                    post(new Runnable() {
                        @Override
                        public void run() {
                            if (pair[k] == wanted[k]) {
                                covers[k] = result;
                                invalidate();
                            }
                        }
                    });
                }
            }
        }, "Y1OlderCovers").start();
    }

    @Override
    protected void onGameKey(int key, boolean repeat) {
        if (revealed) return;
        if (key == KEY_LEFT || key == KEY_PREV) choice = 0;
        else if (key == KEY_RIGHT || key == KEY_NEXT) choice = 1;
        else if (key == 23 || key == 66) {
            host.feedback();
            revealed = true;
            revealTimer = 1.6f;
            right = years[choice] < years[1 - choice];
            if (right) score++;
            else lives--;
        }
        invalidate();
    }

    @Override
    protected void update(float dt) {
        if (!revealed) return;
        revealTimer -= dt;
        if (revealTimer <= 0) {
            if (lives <= 0) gameOver();
            else nextPair();
        }
    }

    @Override
    protected void drawGame(Canvas c) {
        String hearts = "";
        for (int i = 0; i < 3; i++) hearts += i < lives ? "●" : "○";
        drawHud(c, String.format(t("Score: %d"), score), hearts);
        if (pair[0] == null) return;
        text.setTypeface(fontBold);
        text.setTextSize(17 * dp);
        text.setColor(colText);
        text.setTextAlign(Paint.Align.CENTER);
        String q = revealed ? (right ? t("Right!") : t("Wrong!")) : t("Which one came out first?");
        if (revealed) text.setColor(right ? 0xFF30D158 : 0xFFFF453A);
        c.drawText(q, getWidth() / 2f, 44 * dp, text);
        float cardW = (getWidth() - 36 * dp) / 2f, top = 56 * dp, cardH = getHeight() - top - 8 * dp;
        for (int i = 0; i < 2; i++) {
            float l = 12 * dp + i * (cardW + 12 * dp);
            RectF card = new RectF(l, top, l + cardW, top + cardH);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor((colText & 0x00FFFFFF) | 0x14000000);
            c.drawRoundRect(card, 8 * dp, 8 * dp, paint);
            if (i == choice && state == PLAYING) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(3 * dp);
                paint.setColor(colAccent);
                c.drawRoundRect(card, 8 * dp, 8 * dp, paint);
                paint.setStyle(Paint.Style.FILL);
            }
            float size = 130 * dp, cx = l + cardW / 2f, cy = top + 10 * dp;
            RectF art = new RectF(cx - size / 2, cy, cx + size / 2, cy + size);
            if (covers[i] != null) c.drawBitmap(covers[i], null, art, coverPaint);
            else {
                paint.setColor(0x33808080);
                c.drawRect(art, paint);
            }
            tp.setTypeface(fontBold);
            tp.setTextSize(15 * dp);
            tp.setColor(colText);
            float avail = cardW - 16 * dp;
            CharSequence title = TextUtils.ellipsize(pair[i].title == null ? "" : pair[i].title, tp, avail, TextUtils.TruncateAt.END);
            tp.setTextAlign(Paint.Align.CENTER);
            c.drawText(title.toString(), cx, cy + size + 22 * dp, tp);
            tp.setTypeface(font);
            tp.setTextSize(13 * dp);
            tp.setColor(colText2);
            CharSequence artist = TextUtils.ellipsize(pair[i].artist == null ? "" : pair[i].artist, tp, avail, TextUtils.TruncateAt.END);
            c.drawText(artist.toString(), cx, cy + size + 40 * dp, tp);
            if (revealed) {
                boolean older = years[i] < years[1 - i];
                text.setTextSize(24 * dp);
                text.setColor(older ? 0xFF30D158 : 0xFFFF453A);
                c.drawText(String.valueOf(years[i]), cx, cy + size + 70 * dp, text);
            }
        }
        text.setTextAlign(Paint.Align.LEFT);
    }
}
