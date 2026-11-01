package com.themoon.y1.games;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

// 🚀 [Games] Birdy: Center (or Play/Pause) flaps; fly through the gaps between the sandstone columns of
// the oasis. One point per column. It has its own sunset colours rather than the theme's.
public class FlappyGame extends GameView {
    private float birdX, birdY, vy, radius, gravity, flap, speed, pipeW, gap, spacing, ground;
    private final List<float[]> pipes = new ArrayList<>(); // {x, gap centre, passed}
    private final Random random = new Random();
    private Shader sky;
    private float wing;

    public FlappyGame(Context context, Host host) {
        super(context, host);
    }

    @Override
    public String id() {
        return "flappy";
    }

    @Override
    protected String[] instructions() {
        return new String[] { t("Center: flap"), t("Fly through the gaps between the columns.") };
    }

    @Override
    protected void reset() {
        radius = 11 * dp;
        gravity = 1250 * dp;
        flap = -360 * dp;
        speed = 150 * dp;
        pipeW = 54 * dp;
        gap = 118 * dp;
        spacing = 210 * dp;
        ground = 26 * dp;
        birdX = getWidth() * 0.3f;
        birdY = (getHeight() - ground) / 2f;
        vy = 0;
        pipes.clear();
        for (int i = 0; i < 4; i++) pipes.add(newPipe(getWidth() + 80 * dp + i * spacing));
        sky = new LinearGradient(0, 0, 0, getHeight(), 0xFFF9A77E, 0xFFFFE9C9, Shader.TileMode.CLAMP);
    }

    private float[] newPipe(float x) {
        float min = 40 * dp + gap / 2, max = getHeight() - ground - 40 * dp - gap / 2;
        return new float[] { x, min + random.nextFloat() * Math.max(1, max - min), 0 };
    }

    @Override
    protected void onStarted() {
        vy = flap;
    }

    @Override
    protected void onGameKey(int key, boolean repeat) {
        if (repeat) return;
        if (key == 23 || key == 66 || key == KEY_PLAY || key == KEY_NEXT || key == KEY_PREV) {
            vy = flap;
            wing = 0.15f;
        }
    }

    @Override
    protected void update(float dt) {
        vy += gravity * dt;
        birdY += vy * dt;
        if (wing > 0) wing -= dt;
        groundShift = (groundShift + speed * dt) % (8 * PX * dp);
        if (birdY < radius) {
            birdY = radius;
            vy = 0;
        }
        float last = 0;
        for (float[] p : pipes) {
            p[0] -= speed * dt;
            last = Math.max(last, p[0]);
            if (p[2] == 0 && p[0] + pipeW < birdX) {
                p[2] = 1;
                score++;
                host.feedback();
            }
        }
        if (!pipes.isEmpty() && pipes.get(0)[0] + pipeW < 0) {
            pipes.remove(0);
            pipes.add(newPipe(last + spacing));
        }
        if (birdY + radius >= getHeight() - ground) {
            birdY = getHeight() - ground - radius;
            gameOver();
            return;
        }
        float hit = radius * 0.8f; // a little forgiving
        for (float[] p : pipes) {
            if (birdX + hit < p[0] || birdX - hit > p[0] + pipeW) continue;
            if (birdY - hit < p[1] - gap / 2 || birdY + hit > p[1] + gap / 2) {
                gameOver();
                return;
            }
        }
    }

    // Birdy is pixel art: a 13x10 sprite (two frames: wing down / up) drawn in square "pixels" of PX dp, with
    // its own palette - K outline, T turquoise, L light wing, W eye, B pupil, O coral beak, C crest, F belly.
    private static final String[] SPRITE_DOWN = { "....CC.......", "...CKKKKK....", "..KTTTTTWWK..", ".KTTTTTWWBWK.", "KTTTTTTWWBWK.", "KLLLLTTTWWKOO", "KLLLLLTTTKOOO", ".KLLLFFFFKOO.", "..KKFFFFFK...", "....KKKKK...." };
    private static final String[] SPRITE_UP = { "....CC.......", "...CKKKKK....", "..KLLTTTWWK..", ".KLLLLTWWBWK.", "KLLLLTTWWBWK.", "KTTTTTTTWWKOO", "KTTTTTTTTKOOO", ".KTTTFFFFKOO.", "..KKFFFFFK...", "....KKKKK...." };
    private static final String PALETTE_KEYS = "KTLWBOCF";
    private static final int[] PALETTE = { 0xFF0F5E58, 0xFF2EC4B6, 0xFF8FE3D9, 0xFFFFFFFF, 0xFF1B2B34, 0xFFFF7A5C, 0xFF1E9E93, 0xFFE6FBF7 };
    private static final float PX = 2.5f;
    private float groundShift;

    @Override
    protected void drawGame(Canvas c) {
        int w = getWidth(), h = getHeight();
        float px = PX * dp;
        paint.setAntiAlias(false); // crisp pixels
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(sky);
        c.drawRect(0, 0, w, h, paint);
        paint.setShader(null);
        // a low pixel sun and the far dunes, in steps
        pixelDisc(c, w * 0.78f, h * 0.28f, 30 * dp, px * 2, 0xFFFFF1D6);
        pixelDisc(c, w * 0.15f, h - ground + 10 * dp, 90 * dp, px * 2, 0x33B9733F);
        pixelDisc(c, w * 0.75f, h - ground + 20 * dp, 110 * dp, px * 2, 0x33B9733F);
        for (float[] p : pipes) {
            drawColumn(c, p[0], 0, p[1] - gap / 2, true, px);
            drawColumn(c, p[0], p[1] + gap / 2, h - ground, false, px);
        }
        // sand with a pixel pattern that scrolls with the columns, and the water's edge
        paint.setColor(0xFFEBC98F);
        c.drawRect(0, h - ground, w, h, paint);
        paint.setColor(0xFFDDB377);
        float period = 8 * px;
        for (float x = -groundShift; x < w; x += period) {
            c.drawRect(x, h - ground + 4 * px, x + px, h - ground + 5 * px, paint);
            c.drawRect(x + 4 * px, h - ground + 7 * px, x + 5 * px, h - ground + 8 * px, paint);
        }
        paint.setColor(0xFF4FB8B0);
        c.drawRect(0, h - ground, w, h - ground + 2 * px, paint);
        // Birdy
        String[] sprite = wing > 0 ? SPRITE_UP : SPRITE_DOWN;
        c.save();
        c.rotate(clamp(vy / (8 * dp), -20, 45), birdX, birdY);
        float left = birdX - 6.5f * px, top = birdY - 5 * px;
        for (int row = 0; row < sprite.length; row++) {
            for (int col = 0; col < sprite[row].length(); col++) {
                int k = PALETTE_KEYS.indexOf(sprite[row].charAt(col));
                if (k < 0) continue;
                paint.setColor(PALETTE[k]);
                c.drawRect(left + col * px, top + row * px, left + (col + 1) * px, top + (row + 1) * px, paint);
            }
        }
        c.restore();
        paint.setAntiAlias(true);
        // score, big at the top
        text.setTypeface(fontBold);
        text.setTextSize(36 * dp);
        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(0x66000000);
        c.drawText(String.valueOf(score), w / 2f + 2 * dp, 48 * dp, text);
        text.setColor(0xFFFFFFFF);
        c.drawText(String.valueOf(score), w / 2f, 46 * dp, text);
        text.setTextAlign(Paint.Align.LEFT);
    }

    // A disc drawn as horizontal steps of one "pixel" each.
    private void pixelDisc(Canvas c, float cx, float cy, float r, float step, int colour) {
        paint.setColor(colour);
        for (float y = -r; y < r; y += step) {
            float mid = y + step / 2, half = (float) Math.sqrt(Math.max(0, r * r - mid * mid));
            half = Math.round(half / step) * step;
            c.drawRect(cx - half, cy + y, cx + half, cy + y + step, paint);
        }
    }

    // A sandstone column of the old oasis ruins, in pixels: a light edge, a shaded side, bands of darker
    // stone and a wider capital at the end that faces the gap.
    private void drawColumn(Canvas c, float x, float top, float bottom, boolean fromTop, float px) {
        if (bottom <= top) return;
        paint.setColor(0xFFD9A066);
        c.drawRect(x, top, x + pipeW, bottom, paint);
        paint.setColor(0xFFE8B985);
        c.drawRect(x + px, top, x + 3 * px, bottom, paint);
        paint.setColor(0xFFB97A3F);
        c.drawRect(x + pipeW - 3 * px, top, x + pipeW, bottom, paint);
        paint.setColor(0xFFC48A50);
        for (float y = top + 4 * px; y < bottom - px; y += 7 * px) c.drawRect(x, y, x + pipeW, y + px, paint);
        float capH = 5 * px, capTop = fromTop ? bottom - capH : top;
        paint.setColor(0xFFC48A50);
        c.drawRect(x - 2 * px, capTop, x + pipeW + 2 * px, capTop + capH, paint);
        paint.setColor(0xFFE8B985);
        c.drawRect(x - 2 * px, fromTop ? capTop + capH - px : capTop, x + pipeW + 2 * px, (fromTop ? capTop + capH - px : capTop) + px, paint);
    }
}
