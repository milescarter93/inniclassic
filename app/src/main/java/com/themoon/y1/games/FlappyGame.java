package com.themoon.y1.games;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

// 🚀 [Games] Flappy: Center (or Play/Pause) flaps; fly through the gaps between the pipes. One point per
// pipe. It has its own sky colours rather than the theme's.
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
        return new String[] { t("Center: flap"), t("Fly through the gaps between the pipes.") };
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
        sky = new LinearGradient(0, 0, 0, getHeight(), 0xFF4EC0CA, 0xFFB8EDF0, Shader.TileMode.CLAMP);
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

    @Override
    protected void drawGame(Canvas c) {
        int w = getWidth(), h = getHeight();
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(sky);
        c.drawRect(0, 0, w, h, paint);
        paint.setShader(null);
        for (float[] p : pipes) {
            float top = p[1] - gap / 2, bottom = p[1] + gap / 2;
            paint.setColor(0xFF5EBE3B);
            c.drawRect(p[0], 0, p[0] + pipeW, top, paint);
            c.drawRect(p[0], bottom, p[0] + pipeW, h - ground, paint);
            paint.setColor(0xFF3F8F25); // lips
            c.drawRect(p[0] - 3 * dp, top - 14 * dp, p[0] + pipeW + 3 * dp, top, paint);
            c.drawRect(p[0] - 3 * dp, bottom, p[0] + pipeW + 3 * dp, bottom + 14 * dp, paint);
        }
        paint.setColor(0xFFDED895);
        c.drawRect(0, h - ground, w, h, paint);
        paint.setColor(0xFF7CC34B);
        c.drawRect(0, h - ground, w, h - ground + 5 * dp, paint);
        // the bird
        c.save();
        c.rotate(clamp(vy / (8 * dp), -25, 70), birdX, birdY);
        paint.setColor(0xFFFFCC33);
        c.drawCircle(birdX, birdY, radius, paint);
        paint.setColor(0xFFFFFFFF);
        c.drawCircle(birdX + radius * 0.45f, birdY - radius * 0.3f, radius * 0.35f, paint);
        paint.setColor(0xFF000000);
        c.drawCircle(birdX + radius * 0.55f, birdY - radius * 0.3f, radius * 0.15f, paint);
        paint.setColor(0xFFFF7A33);
        c.drawRect(birdX + radius * 0.6f, birdY + radius * 0.05f, birdX + radius * 1.35f, birdY + radius * 0.4f, paint);
        paint.setColor(0xFFF2E6B3);
        float wy = wing > 0 ? -radius * 0.45f : radius * 0.1f;
        c.drawOval(new android.graphics.RectF(birdX - radius * 0.9f, birdY + wy, birdX - radius * 0.1f, birdY + wy + radius * 0.5f), paint);
        c.restore();
        // score, big at the top
        text.setTypeface(fontBold);
        text.setTextSize(36 * dp);
        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(0xFF000000);
        c.drawText(String.valueOf(score), w / 2f + 2 * dp, 48 * dp, text);
        text.setColor(0xFFFFFFFF);
        c.drawText(String.valueOf(score), w / 2f, 46 * dp, text);
        text.setTextAlign(Paint.Align.LEFT);
    }
}
