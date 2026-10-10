package com.themoon.y1.games;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;

import java.util.Random;

// 🚀 [Games] Blocks (falling-block puzzle): the wheel moves the piece, Center turns it, Previous turns
// it the other way, Next drops it and Play/Pause moves it down one row. Full rows clear; every 10 rows
// the level - and the speed - goes up.
public class BlocksGame extends GameView {
    private static final int W = 10, H = 18;
    // the seven pieces, as cells in their box: I, O, T, S, Z, J, L
    private static final int[][][] SHAPES = {
            { { 0, 1 }, { 1, 1 }, { 2, 1 }, { 3, 1 } },
            { { 0, 0 }, { 1, 0 }, { 0, 1 }, { 1, 1 } },
            { { 1, 0 }, { 0, 1 }, { 1, 1 }, { 2, 1 } },
            { { 1, 0 }, { 2, 0 }, { 0, 1 }, { 1, 1 } },
            { { 0, 0 }, { 1, 0 }, { 1, 1 }, { 2, 1 } },
            { { 0, 0 }, { 0, 1 }, { 1, 1 }, { 2, 1 } },
            { { 2, 0 }, { 0, 1 }, { 1, 1 }, { 2, 1 } } };
    private static final int[] BOX = { 4, 2, 3, 3, 3, 3, 3 };
    private static final int[] COLORS = { 0xFF64D2FF, 0xFFFFD60A, 0xFFBF5AF2, 0xFF30D158, 0xFFFF453A, 0xFF0A84FF, 0xFFFF9F0A };
    private final int[][] board = new int[H][W]; // 0 empty, else colour index + 1
    private final Random random = new Random();
    private int piece, next, rot, px, py, lines, level;
    private float fall;
    private int cell, boardX, boardY;

    public BlocksGame(Context context, Host host) {
        super(context, host);
    }

    @Override
    public String id() {
        return "blocks";
    }

    @Override
    protected String[] instructions() {
        return new String[] { t("Wheel: move  ·  Center: turn"), t("Next: drop  ·  Play/Pause: down") };
    }

    @Override
    protected void reset() {
        cell = Math.max(8, (int) ((getHeight() - 10 * dp) / H));
        boardY = (getHeight() - cell * H) / 2;
        boardX = (int) (24 * dp);
        for (int[] row : board) java.util.Arrays.fill(row, 0);
        lines = 0;
        level = 1;
        next = random.nextInt(7);
        spawn();
    }

    private void spawn() {
        piece = next;
        next = random.nextInt(7);
        rot = 0;
        px = (W - BOX[piece]) / 2;
        py = 0;
        fall = 0;
        if (collides(px, py, rot) && state == PLAYING) gameOver();
    }

    // cell i of the current piece in a rotation: {x, y} inside its box
    private int[] cellOf(int p, int r, int i) {
        int x = SHAPES[p][i][0], y = SHAPES[p][i][1], n = BOX[p];
        for (int k = 0; k < r; k++) {
            int nx = n - 1 - y;
            y = x;
            x = nx;
        }
        return new int[] { x, y };
    }

    private boolean collides(int x0, int y0, int r) {
        for (int i = 0; i < 4; i++) {
            int[] c = cellOf(piece, r, i);
            int x = x0 + c[0], y = y0 + c[1];
            if (x < 0 || x >= W || y >= H) return true;
            if (y >= 0 && board[y][x] != 0) return true;
        }
        return false;
    }

    private void turn(int d) {
        int r = (rot + d + 4) % 4;
        for (int kick : new int[] { 0, -1, 1, -2, 2 }) { // nudge it off a wall if needed
            if (!collides(px + kick, py, r)) {
                px += kick;
                rot = r;
                return;
            }
        }
    }

    @Override
    protected void onGameKey(int key, boolean repeat) {
        if (key == KEY_LEFT && !collides(px - 1, py, rot)) px--;
        else if (key == KEY_RIGHT && !collides(px + 1, py, rot)) px++;
        else if ((key == 23 || key == 66) && !repeat) turn(1);
        else if (key == KEY_PREV && !repeat) turn(-1);
        else if (key == KEY_PLAY) down();
        else if (key == KEY_NEXT && !repeat) {
            while (!collides(px, py + 1, rot)) {
                py++;
                score += 2;
            }
            lock();
        }
        invalidate();
    }

    private void down() {
        if (!collides(px, py + 1, rot)) {
            py++;
            fall = 0;
        } else {
            lock();
        }
    }

    private void lock() {
        for (int i = 0; i < 4; i++) {
            int[] c = cellOf(piece, rot, i);
            int y = py + c[1];
            if (y < 0) {
                gameOver();
                return;
            }
            board[y][px + c[0]] = piece + 1;
        }
        int cleared = 0;
        for (int y = H - 1; y >= 0; y--) {
            boolean full = true;
            for (int x = 0; x < W; x++) if (board[y][x] == 0) full = false;
            if (!full) continue;
            cleared++;
            for (int yy = y; yy > 0; yy--) board[yy] = board[yy - 1].clone();
            board[0] = new int[W];
            y++; // look at the same row again
        }
        if (cleared > 0) {
            host.feedback();
            score += new int[] { 0, 100, 300, 500, 800 }[cleared] * level;
            lines += cleared;
            level = 1 + lines / 10;
        }
        spawn();
    }

    @Override
    protected void update(float dt) {
        fall += dt;
        float interval = Math.max(0.08f, 0.8f - (level - 1) * 0.07f);
        if (fall >= interval) {
            fall = 0;
            down();
        }
    }

    private void drawCell(Canvas c, float x, float y, int colour) {
        paint.setColor(colour);
        roundRect(c, x + dp, y + dp, x + cell - dp, y + cell - dp, 2 * dp, paint);
    }

    @Override
    protected void drawGame(Canvas c) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor((colText & 0x00FFFFFF) | 0x1A000000); // a faint well in the text colour
        c.drawRect(boardX, boardY, boardX + W * cell, boardY + H * cell, paint);
        for (int y = 0; y < H; y++)
            for (int x = 0; x < W; x++)
                if (board[y][x] != 0) drawCell(c, boardX + x * cell, boardY + y * cell, COLORS[board[y][x] - 1]);
        if (state != OVER) {
            // where it would land, then the piece
            int gy = py;
            while (!collides(px, gy + 1, rot)) gy++;
            for (int i = 0; i < 4; i++) {
                int[] cc = cellOf(piece, rot, i);
                if (gy + cc[1] >= 0) drawCell(c, boardX + (px + cc[0]) * cell, boardY + (gy + cc[1]) * cell, 0x40FFFFFF);
            }
            for (int i = 0; i < 4; i++) {
                int[] cc = cellOf(piece, rot, i);
                if (py + cc[1] >= 0) drawCell(c, boardX + (px + cc[0]) * cell, boardY + (py + cc[1]) * cell, COLORS[piece]);
            }
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2 * dp);
        paint.setColor(colText2);
        c.drawRect(boardX - dp, boardY - dp, boardX + W * cell + dp, boardY + H * cell + dp, paint);
        paint.setStyle(Paint.Style.FILL);
        // side panel
        float sx = boardX + W * cell + 24 * dp, y = boardY + 18 * dp;
        text.setTypeface(fontBold);
        text.setTextSize(15 * dp);
        String[][] info = { { t("Next"), "" }, { "", "" }, { "", "" }, { "", "" }, { t("Score"), String.valueOf(score) },
                { t("Lines"), String.valueOf(lines) }, { t("Level"), String.valueOf(level) },
                { t("Best"), String.valueOf(Math.max(score, host.best(id()))) } };
        for (String[] row : info) {
            if (!row[0].isEmpty()) {
                text.setColor(colText2);
                c.drawText(row[0], sx, y, text);
                text.setColor(colText);
                c.drawText(row[1], sx + 90 * dp, y, text);
            }
            y += 26 * dp;
        }
        for (int i = 0; i < 4; i++) {
            int[] cc = cellOf(next, 0, i);
            drawCell(c, sx + cc[0] * cell, boardY + 30 * dp + cc[1] * cell, COLORS[next]);
        }
    }
}
