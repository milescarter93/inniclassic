package com.themoon.y1.managers;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

// 🚀 [Sleep timer] Pauses the music after a set time, or when the playing song ends, fading it out over the
// last FADE_MS so it doesn't stop abruptly. The fade uses the player's own gain, which is set back to full
// once paused, so the next play starts at the normal volume.
public class SleepTimer {

    public interface Listener {
        void onSleepTimerFinished();
    }

    private static final long FADE_MS = 10000;
    // Pause this close to the end of the song, before the player moves on to the next one by itself.
    private static final long END_OF_SONG_MARGIN_MS = 400;

    private static SleepTimer instance;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean active = false;
    private boolean endOfSong = false;
    private long endsAtElapsed = 0;
    private boolean fading = false;
    private Listener listener;

    public static synchronized SleepTimer getInstance() {
        if (instance == null) instance = new SleepTimer();
        return instance;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void startMinutes(int minutes) {
        cancel();
        active = true;
        endOfSong = false;
        endsAtElapsed = SystemClock.elapsedRealtime() + minutes * 60_000L;
        handler.post(tick);
    }

    public void startEndOfSong() {
        cancel();
        active = true;
        endOfSong = true;
        handler.post(tick);
    }

    public void cancel() {
        handler.removeCallbacks(tick);
        if (fading) AudioPlayerManager.getInstance().setFadeFactor(1f);
        active = false;
        fading = false;
        endOfSong = false;
    }

    public boolean isActive() {
        return active;
    }

    public boolean isEndOfSong() {
        return active && endOfSong;
    }

    // Whole minutes left (rounded up) for a timed sleep, or -1.
    public int minutesLeft() {
        if (!active || endOfSong) return -1;
        long ms = Math.max(0, endsAtElapsed - SystemClock.elapsedRealtime());
        return (int) ((ms + 59_999) / 60_000);
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!active) return;
            AudioPlayerManager am = AudioPlayerManager.getInstance();

            long remaining;
            if (endOfSong) {
                int duration = am.getDuration();
                if (!am.isPlaying() || duration <= 0) {
                    restoreVolumeIfFading(am);
                    handler.postDelayed(this, 1000);
                    return;
                }
                remaining = duration - am.getCurrentPosition() - END_OF_SONG_MARGIN_MS;
            } else {
                remaining = endsAtElapsed - SystemClock.elapsedRealtime();
            }

            if (remaining <= 0) {
                finish(am);
                return;
            }
            if (remaining <= FADE_MS && am.isPlaying()) {
                fading = true;
                am.setFadeFactor(remaining / (float) FADE_MS);
                handler.postDelayed(this, 250);
            } else {
                // Paused by hand during the fade: back to full volume for whenever it's resumed.
                restoreVolumeIfFading(am);
                handler.postDelayed(this, Math.min(1000, Math.max(250, remaining - FADE_MS)));
            }
        }
    };

    private void restoreVolumeIfFading(AudioPlayerManager am) {
        if (fading) {
            am.setFadeFactor(1f);
            fading = false;
        }
    }

    private void finish(AudioPlayerManager am) {
        active = false;
        boolean wasPlaying = am.isPlaying();
        android.util.Log.i("Y1Sleep", "Sleep timer finished (" + (endOfSong ? "end of song" : "timed") + "): position "
                + am.getCurrentPosition() + " of " + am.getDuration() + " ms, was playing " + wasPlaying);
        if (wasPlaying) am.playOrPauseMusic();
        am.setFadeFactor(1f);
        fading = false;
        endOfSong = false;
        if (wasPlaying && listener != null) listener.onSleepTimerFinished();
    }
}
