package com.themoon.y1.games;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;

import java.util.ArrayDeque;
import java.util.LinkedList;
import java.util.Random;

// 🚀 [Games] Snake: the wheel turns the snake (one step = a quarter turn left or right; Previous / Next
// do the same). Each piece of food makes it longer and a little faster; the walls and its own body end
// the game.
public class SnakeGame extends GameView {
    private static final int UP = 0, RIGHT = 1, DOWN = 2, LEFT = 3;
    private int cell, cols, rows, top, left;
    private final LinkedList<int[]> body = new LinkedList<>(); // head first
    private final ArrayDeque<Integer> turns = new ArrayDeque<>();
    private int dir, foodX, foodY;
    private float acc, stepTime;
    private final Random random = new Random();

    public SnakeGame(Context context, Host host) {
        super(context, host);
    }

    @Override
    public String id() {
        return "snake";
    }

    @Override
    protected String[] instructions() {
        return new String[] { t("Wheel: turn left / right"), t("Eat to grow; avoid the walls and yourself.") };
    }

    @Override
    protected void reset() {
        cell = (int) (16 * dp);
        top = (int) (26 * dp);
        cols = Math.max(10, getWidth() / cell);
        rows = Math.max(8, (getHeight() - top) / cell);
        left = (getWidth() - cols * cell) / 2;
        body.clear();
        int y = rows / 2;
        for (int i = 0; i < 4; i++) body.add(new int[] { cols / 3 - i, y });
        dir = RIGHT;
        turns.clear();
        acc = 0;
        stepTime = 0.14f;
        placeFood();
    }

    private void placeFood() {
        while (true) {
            int x = random.nextInt(cols), y = random.nextInt(rows);
            boolean free = true;
            for (int[] p : body) if (p[0] == x && p[1] == y) free = false;
            if (free) {
                foodX = x;
                foodY = y;
                return;
            }
        }
    }

    @Override
    protected void onGameKey(int key, boolean repeat) {
        int turn = key == KEY_LEFT || key == KEY_PREV ? -1 : key == KEY_RIGHT || key == KEY_NEXT ? 1 : 0;
        if (turn == 0 || turns.size() >= 2) return;
        int base = turns.isEmpty() ? dir : turns.peekLast();
        turns.add((base + turn + 4) % 4);
    }

    @Override
    protected void update(float dt) {
        acc += dt;
        while (acc >= stepTime && state == PLAYING) {
            acc -= stepTime;
            step();
        }
    }

    private void step() {
        if (!turns.isEmpty()) dir = turns.poll();
        int[] head = body.getFirst();
        int nx = head[0] + (dir == RIGHT ? 1 : dir == LEFT ? -1 : 0);
        int ny = head[1] + (dir == DOWN ? 1 : dir == UP ? -1 : 0);
        boolean eats = nx == foodX && ny == foodY;
        if (nx < 0 || ny < 0 || nx >= cols || ny >= rows) {
            gameOver();
            return;
        }
        for (int i = 0; i < body.size() - (eats ? 0 : 1); i++) {
            int[] p = body.get(i);
            if (p[0] == nx && p[1] == ny) {
                gameOver();
                return;
            }
        }
        body.addFirst(new int[] { nx, ny });
        if (eats) {
            score++;
            host.feedback();
            stepTime = Math.max(0.065f, 0.14f - score * 0.0025f);
            placeFood();
        } else {
            body.removeLast();
        }
    }

    @Override
    protected void drawGame(Canvas c) {
        drawHud(c, String.format(t("Score: %d"), score), String.format(t("Best: %d"), Math.max(score, host.best(id()))));
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2 * dp);
        paint.setColor(colText2);
        c.drawRect(left - dp, top - dp, left + cols * cell + dp, top + rows * cell + dp, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFFF453A);
        c.drawCircle(left + foodX * cell + cell / 2f, top + foodY * cell + cell / 2f, cell * 0.42f, paint);
        float r = cell * 0.25f;
        for (int i = body.size() - 1; i >= 0; i--) {
            int[] p = body.get(i);
            paint.setColor(i == 0 ? colAccent : colText);
            float x = left + p[0] * cell, y = top + p[1] * cell;
            roundRect(c, x + dp, y + dp, x + cell - dp, y + cell - dp, r, paint);
        }
    }
}
