package com.themoon.y1.games;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

// 🚀 [Games] Simon on the click wheel itself: the wheel is drawn on screen and its buttons are the
// colours - Previous (left), Next (right), Play/Pause (bottom) and Center. Watch the sequence, then
// repeat it; every round adds one more. The top of the wheel (Back) pauses.
public class SimonGame extends GameView {
    private static final int CENTER = 0, RIGHT = 1, BOTTOM = 2, LEFT = 3;
    private static final int[] COLORS = { 0xFF30D158, 0xFF0A84FF, 0xFFFFD60A, 0xFFFF453A };
    private final List<Integer> sequence = new ArrayList<>();
    private final Random random = new Random();
    private boolean showing;
    private int showIndex, inputIndex, lit = -1;
    private float timer, litTimer, waitBeforeShow;

    public SimonGame(Context context, Host host) {
        super(context, host);
    }

    @Override
    public String id() {
        return "simon";
    }

    @Override
    protected String[] instructions() {
        return new String[] { t("Watch the sequence, then repeat it"), t("with Previous, Next, Play/Pause and Center.") };
    }

    @Override
    protected void reset() {
        sequence.clear();
        showing = false;
        lit = -1;
    }

    @Override
    protected void onStarted() {
        sequence.clear();
        nextRound();
    }

    private void nextRound() {
        sequence.add(random.nextInt(4));
        showing = true;
        showIndex = 0;
        timer = 0;
        waitBeforeShow = 0.7f;
        if (litTimer <= 0) lit = -1; // the last press of the round stays lit for its quarter second
    }

    private float onTime() {
        return Math.max(0.22f, 0.5f - sequence.size() * 0.02f);
    }

    @Override
    protected void update(float dt) {
        if (litTimer > 0) {
            litTimer -= dt;
            if (litTimer <= 0 && (!showing || waitBeforeShow > 0)) lit = -1;
        }
        if (!showing) return;
        if (waitBeforeShow > 0) {
            waitBeforeShow -= dt;
            return;
        }
        timer += dt;
        float on = onTime(), off = on * 0.45f;
        int step = (int) (timer / (on + off));
        if (step >= sequence.size()) {
            showing = false;
            lit = -1;
            inputIndex = 0;
            return;
        }
        boolean isOn = timer - step * (on + off) < on;
        lit = isOn ? sequence.get(step) : -1;
    }

    @Override
    protected void onGameKey(int key, boolean repeat) {
        if (showing || repeat) return;
        int pad = key == 23 || key == 66 ? CENTER : key == KEY_NEXT ? RIGHT : key == KEY_PLAY ? BOTTOM : key == KEY_PREV ? LEFT : -1;
        if (pad < 0) return;
        host.feedback();
        lit = pad;
        litTimer = 0.25f;
        if (pad != sequence.get(inputIndex)) {
            gameOver();
            return;
        }
        inputIndex++;
        if (inputIndex == sequence.size()) {
            score = sequence.size();
            nextRound();
        }
    }

    private int shade(int color, boolean on) {
        if (on) return color;
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[2] *= 0.38f;
        return Color.HSVToColor(hsv);
    }

    @Override
    protected void drawGame(Canvas c) {
        String status = showing ? t("Watch...") : state == PLAYING ? t("Your turn") : "";
        drawHud(c, String.format(t("Round %d"), Math.max(1, sequence.size())), status);
        float cx = getWidth() / 2f, cy = (getHeight() + 22 * dp) / 2f;
        float big = Math.min(getWidth(), getHeight() - 26 * dp) / 2f - 10 * dp, inner = big * 0.4f;
        RectF oval = new RectF(cx - big, cy - big, cx + big, cy + big);
        paint.setStyle(Paint.Style.FILL);
        // wedges: right, bottom, left (colours) and top (Back, grey); angles start at 3 o'clock, clockwise
        int[] pads = { RIGHT, BOTTOM, LEFT };
        for (int i = 0; i < 3; i++) {
            paint.setColor(shade(COLORS[pads[i]], lit == pads[i]));
            c.drawArc(oval, -45 + i * 90 + 1.5f, 87, true, paint);
        }
        paint.setColor(0xFF3A3A3C);
        c.drawArc(oval, 225 + 1.5f, 87, true, paint);
        paint.setColor(colBg);
        c.drawCircle(cx, cy, inner + 6 * dp, paint);
        paint.setColor(shade(COLORS[CENTER], lit == CENTER));
        c.drawCircle(cx, cy, inner, paint);
        // the button symbols, as on the wheel
        paint.setColor(0xE6FFFFFF);
        float s = 9 * dp, mid = (big + inner) / 2f;
        drawSkip(c, cx + mid, cy, s, true);
        drawSkip(c, cx - mid, cy, s, false);
        drawPlayPause(c, cx, cy + mid, s);
        text.setTypeface(fontBold);
        text.setTextSize(13 * dp);
        text.setColor(0xFFBBBBBB);
        text.setTextAlign(Paint.Align.CENTER);
        c.drawText(t("MENU"), cx, cy - mid + 5 * dp, text);
        text.setTextAlign(Paint.Align.LEFT);
    }

    // ⏭ / ⏮: two triangles and a bar.
    private void drawSkip(Canvas c, float x, float y, float s, boolean forward) {
        float d = forward ? 1 : -1;
        Path p = new Path();
        for (int i = 0; i < 2; i++) {
            float x0 = x - d * s + d * i * s;
            p.moveTo(x0, y - s * 0.7f);
            p.lineTo(x0 + d * s, y);
            p.lineTo(x0, y + s * 0.7f);
            p.close();
        }
        c.drawPath(p, paint);
        float bx = x + d * s;
        c.drawRect(Math.min(bx, bx + d * 2.5f * dp), y - s * 0.7f, Math.max(bx, bx + d * 2.5f * dp), y + s * 0.7f, paint);
    }

    // ⏯: a triangle and two bars.
    private void drawPlayPause(Canvas c, float x, float y, float s) {
        Path p = new Path();
        p.moveTo(x - s, y - s * 0.7f);
        p.lineTo(x - s * 0.1f, y);
        p.lineTo(x - s, y + s * 0.7f);
        p.close();
        c.drawPath(p, paint);
        c.drawRect(x + s * 0.2f, y - s * 0.7f, x + s * 0.45f, y + s * 0.7f, paint);
        c.drawRect(x + s * 0.7f, y - s * 0.7f, x + s * 0.95f, y + s * 0.7f, paint);
    }
}
