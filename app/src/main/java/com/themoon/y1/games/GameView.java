package com.themoon.y1.games;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;

import com.themoon.y1.models.SongItem;

import java.util.List;

// 🚀 [Games] Base of the Extras > Games mini-games: a View redrawn about 30 times a second while a game
// runs, with the states every game shares (ready, playing, paused, over), best scores and the keys:
// Center starts / resumes / plays again, Back pauses and, when paused or over, leaves. Each game only
// draws itself and handles its keys while playing.
public abstract class GameView extends View {
    public interface Host {
        String t(String english);

        int best(String gameId);

        // Saves the score if it's a record; true when it is.
        boolean submitScore(String gameId, int score);

        void feedback();

        void keepAwake();

        void exitGame();

        List<SongItem> library();
    }

    protected static final int READY = 0, PLAYING = 1, PAUSED = 2, OVER = 3;
    protected static final int KEY_LEFT = 21, KEY_RIGHT = 22, KEY_PREV = 88, KEY_NEXT = 87, KEY_PLAY = 85;

    protected final Host host;
    protected int state = READY;
    protected int score;
    protected boolean newRecord;
    protected int colBg = 0xFF1E1E22, colText = 0xFFF2F2F7, colText2 = 0xFF98989D, colAccent = 0xFF2588DC;
    protected Typeface font = Typeface.DEFAULT, fontBold = Typeface.DEFAULT_BOLD;
    protected final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    protected final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    protected final float dp;
    private long lastFrame, lastAwake;

    private final Runnable frame = new Runnable() {
        @Override
        public void run() {
            if (state != PLAYING || getWindowToken() == null) return;
            long now = SystemClock.uptimeMillis();
            float dt = Math.min(0.05f, (now - lastFrame) / 1000f);
            lastFrame = now;
            update(dt);
            invalidate();
            if (now - lastAwake > 4000) { // the backlight timer only counts key presses
                lastAwake = now;
                host.keepAwake();
            }
            if (state == PLAYING) postDelayed(this, 33);
        }
    };

    protected GameView(Context context, Host host) {
        super(context);
        this.host = host;
        dp = context.getResources().getDisplayMetrics().density;
        text.setTextAlign(Paint.Align.LEFT);
    }

    public void setLook(int bg, int textColor, int text2, int accent, Typeface regular, Typeface bold) {
        colBg = bg;
        colText = textColor;
        colText2 = text2;
        colAccent = accent;
        if (regular != null) font = regular;
        if (bold != null) fontBold = bold;
        invalidate();
    }

    public abstract String id();

    // Sets up a new game (also called once the size is known).
    protected abstract void reset();

    protected abstract void update(float dt);

    protected abstract void drawGame(Canvas c);

    // Keys while playing (key down; repeat = held).
    protected abstract void onGameKey(int key, boolean repeat);

    // Lines under the title on the start screen.
    protected abstract String[] instructions();

    // Called when a game starts (from the start screen or "play again").
    protected void onStarted() {
    }

    // A reason the game can't be played (e.g. no songs with a year), or null.
    protected String cannotPlay() {
        return null;
    }

    protected String t(String s) {
        return host.t(s);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w > 0 && h > 0 && state == READY) reset();
    }

    public final void handleKey(KeyEvent e) {
        int key = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        boolean first = e.getRepeatCount() == 0;
        boolean center = key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER;
        if (key == KeyEvent.KEYCODE_BACK) {
            if (!down || !first) return;
            host.feedback();
            if (state == PLAYING) pause();
            else host.exitGame();
            return;
        }
        if (!down) return;
        if (state == PLAYING) {
            onGameKey(key, !first);
            return;
        }
        if (!center || !first || getWidth() == 0) return;
        if (state == PAUSED) {
            host.feedback();
            run();
        } else if (cannotPlay() == null) {
            host.feedback();
            if (state == OVER) reset();
            score = 0;
            newRecord = false;
            onStarted();
            run();
        }
    }

    private void run() {
        state = PLAYING;
        lastFrame = SystemClock.uptimeMillis();
        removeCallbacks(frame);
        post(frame);
        invalidate();
    }

    public void pause() {
        if (state != PLAYING) return;
        state = PAUSED;
        removeCallbacks(frame);
        invalidate();
    }

    protected void gameOver() {
        state = OVER;
        removeCallbacks(frame);
        newRecord = host.submitScore(id(), score);
        invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(frame);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas c) {
        c.drawColor(colBg);
        if (getWidth() == 0) return;
        drawGame(c);
        if (state == PLAYING) return;
        // the start / pause / game over panel
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xB0000000);
        c.drawRect(0, 0, getWidth(), getHeight(), paint);
        String title;
        String[] lines;
        if (state == PAUSED) {
            title = t("Paused");
            lines = new String[] { t("Center: resume"), t("Back: leave") };
        } else if (state == OVER) {
            title = t("Game Over");
            lines = new String[] { String.format(t("Score: %d"), score),
                    newRecord ? t("New record!") : String.format(t("Best: %d"), host.best(id())), "",
                    t("Center: play again"), t("Back: leave") };
        } else {
            title = "";
            String reason = cannotPlay();
            String[] how = instructions();
            int best = host.best(id());
            int extra = reason != null ? 1 : 3;
            lines = new String[how.length + extra];
            System.arraycopy(how, 0, lines, 0, how.length);
            if (reason != null) {
                lines[how.length] = reason;
            } else {
                lines[how.length] = best > 0 ? String.format(t("Best: %d"), best) : "";
                lines[how.length + 1] = "";
                lines[how.length + 2] = t("Center: start");
            }
        }
        float titleSize = 30 * dp, lineSize = 16 * dp, gap = 6 * dp;
        float total = (title.isEmpty() ? 0 : titleSize + 10 * dp) + lines.length * (lineSize + gap);
        float y = (getHeight() - total) / 2f;
        text.setTextAlign(Paint.Align.CENTER);
        if (!title.isEmpty()) {
            text.setTypeface(fontBold);
            text.setTextSize(titleSize);
            text.setColor(0xFFFFFFFF);
            y += titleSize;
            c.drawText(title, getWidth() / 2f, y, text);
            y += 10 * dp;
        }
        text.setTypeface(font);
        text.setTextSize(lineSize);
        for (String line : lines) {
            y += lineSize;
            text.setColor(line.equals(t("New record!")) ? 0xFFFFD60A : 0xFFE6E6E6);
            c.drawText(line, getWidth() / 2f, y, text);
            y += gap;
        }
        text.setTextAlign(Paint.Align.LEFT);
    }

    // "Score 12" on the left and "Best 30" on the right of a strip at the top.
    protected void drawHud(Canvas c, String left, String right) {
        text.setTypeface(fontBold);
        text.setTextSize(15 * dp);
        text.setColor(colText);
        text.setTextAlign(Paint.Align.LEFT);
        c.drawText(left, 8 * dp, 17 * dp, text);
        if (right != null) {
            text.setColor(colText2);
            text.setTextAlign(Paint.Align.RIGHT);
            c.drawText(right, getWidth() - 8 * dp, 17 * dp, text);
            text.setTextAlign(Paint.Align.LEFT);
        }
    }

    protected static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    protected static void roundRect(Canvas c, float l, float t, float r, float b, float radius, Paint p) {
        c.drawRoundRect(new RectF(l, t, r, b), radius, radius, p);
    }
}
