package com.themoon.y1.games;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.SystemClock;

// 🚀 [Games] Bricks (the original iPod game): the wheel moves the paddle (faster when spun quickly),
// Center launches the ball. Three lives; each cleared wall brings a faster ball.
public class BricksGame extends GameView {
    private static final int COLS = 8, ROWS = 5;
    private final boolean[][] bricks = new boolean[ROWS][COLS];
    private float paddleX, paddleW, paddleY, paddleH;
    private float ballX, ballY, vx, vy, r, speed;
    private boolean stuck;
    private int lives, level;
    private float wallTop, brickTop, brickW, brickH, margin;
    private long lastMove;

    public BricksGame(Context context, Host host) {
        super(context, host);
    }

    @Override
    public String id() {
        return "bricks";
    }

    @Override
    protected String[] instructions() {
        return new String[] { t("Wheel: move the paddle"), t("Center: launch the ball") };
    }

    @Override
    protected void reset() {
        margin = 8 * dp;
        wallTop = 26 * dp;
        brickTop = wallTop + 20 * dp;
        brickH = 15 * dp;
        brickW = (getWidth() - margin * 2) / COLS;
        paddleW = 76 * dp;
        paddleH = 8 * dp;
        paddleY = getHeight() - 22 * dp;
        paddleX = getWidth() / 2f;
        r = 6 * dp;
        lives = 3;
        level = 1;
        speed = 230 * dp;
        fillWall();
        stuck = true;
    }

    private void fillWall() {
        for (boolean[] row : bricks) java.util.Arrays.fill(row, true);
    }

    @Override
    protected void onGameKey(int key, boolean repeat) {
        if (key == KEY_LEFT || key == KEY_PREV || key == KEY_RIGHT || key == KEY_NEXT) {
            long now = SystemClock.uptimeMillis();
            float step = (now - lastMove < 90 ? 34 : 20) * dp;
            lastMove = now;
            paddleX += key == KEY_LEFT || key == KEY_PREV ? -step : step;
            paddleX = clamp(paddleX, paddleW / 2, getWidth() - paddleW / 2);
        } else if (stuck && (key == 23 || key == 66 || key == KEY_PLAY)) {
            stuck = false;
            double a = Math.toRadians(-20 + Math.random() * 40);
            vx = (float) (speed * Math.sin(a));
            vy = (float) (-speed * Math.cos(a));
        }
    }

    @Override
    protected void update(float dt) {
        if (stuck) {
            ballX = paddleX;
            ballY = paddleY - r - 1;
            return;
        }
        int steps = (int) Math.ceil(speed * dt / (3 * dp));
        for (int i = 0; i < steps && state == PLAYING && !stuck; i++) move(dt / steps);
    }

    private void move(float dt) {
        float px = ballX, py = ballY;
        ballX += vx * dt;
        ballY += vy * dt;
        if (ballX < r) {
            ballX = r;
            vx = Math.abs(vx);
        } else if (ballX > getWidth() - r) {
            ballX = getWidth() - r;
            vx = -Math.abs(vx);
        }
        if (ballY < wallTop + r) {
            ballY = wallTop + r;
            vy = Math.abs(vy);
        }
        // paddle: the further from its middle, the steeper the bounce
        if (vy > 0 && ballY + r >= paddleY && py + r <= paddleY + paddleH && Math.abs(ballX - paddleX) <= paddleW / 2 + r) {
            float rel = clamp((ballX - paddleX) / (paddleW / 2), -1f, 1f);
            double a = Math.toRadians(rel * 60);
            vx = (float) (speed * Math.sin(a));
            vy = (float) (-speed * Math.cos(a));
            ballY = paddleY - r;
        }
        // bricks
        int col = (int) ((ballX - margin) / brickW), row = (int) ((ballY - brickTop) / brickH);
        for (int dr = -1; dr <= 1; dr++)
            for (int dc = -1; dc <= 1; dc++) {
                int rr = row + dr, cc = col + dc;
                if (rr < 0 || rr >= ROWS || cc < 0 || cc >= COLS || !bricks[rr][cc]) continue;
                float l = margin + cc * brickW, t = brickTop + rr * brickH;
                if (ballX + r < l || ballX - r > l + brickW || ballY + r < t || ballY - r > t + brickH) continue;
                bricks[rr][cc] = false;
                score += (ROWS - rr) * 10;
                host.feedback();
                if (px + r <= l || px - r >= l + brickW) vx = -vx;
                else vy = -vy;
                if (wallCleared()) {
                    level++;
                    speed *= 1.12f;
                    fillWall();
                    stuck = true;
                }
                return;
            }
        if (ballY - r > getHeight()) {
            lives--;
            if (lives <= 0) gameOver();
            else stuck = true;
        }
    }

    private boolean wallCleared() {
        for (boolean[] row : bricks) for (boolean b : row) if (b) return false;
        return true;
    }

    @Override
    protected void drawGame(Canvas c) {
        drawHud(c, String.format(t("Score: %d"), score), String.format(t("Level %d"), level));
        paint.setStyle(Paint.Style.FILL);
        float[] hsv = { 0, 0.75f, 0.95f };
        for (int rr = 0; rr < ROWS; rr++) {
            hsv[0] = rr * 38f; // red, orange, yellow, green, teal
            paint.setColor(Color.HSVToColor(hsv));
            for (int cc = 0; cc < COLS; cc++) {
                if (!bricks[rr][cc]) continue;
                float l = margin + cc * brickW, t = brickTop + rr * brickH;
                roundRect(c, l + dp, t + dp, l + brickW - dp, t + brickH - dp, 2 * dp, paint);
            }
        }
        paint.setColor(colAccent);
        roundRect(c, paddleX - paddleW / 2, paddleY, paddleX + paddleW / 2, paddleY + paddleH, paddleH / 2, paint);
        paint.setColor(colText);
        c.drawCircle(ballX, ballY, r, paint);
        // lives, as small balls at the top right
        paint.setColor(colText2);
        for (int i = 0; i < lives; i++) c.drawCircle(getWidth() - 12 * dp - i * 14 * dp, 36 * dp, 4 * dp, paint);
    }
}
