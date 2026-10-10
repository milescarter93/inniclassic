package com.themoon.y1.managers;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import java.util.Calendar;

// 🚀 [Extras] The music alarm: when it goes off the Y1 wakes up and plays music (the last queue or the
// whole library shuffled), optionally fading in. Settings live in Y1_SETTINGS (alarm_*). The system
// alarm is set again after every ring, snooze, change and reboot (Android forgets alarms on reboot).
public final class AlarmScheduler {
    private static final String TAG = "Y1Alarm";
    public static final String ACTION_RING = "com.themoon.y1.ALARM_RING";
    public static final String EXTRA_ALARM = "y1_alarm";

    public static final String DAYS_EVERY = "every", DAYS_WEEKDAYS = "weekdays", DAYS_WEEKENDS = "weekends",
            DAYS_ONCE = "once";
    // What plays: the last queue, the whole library shuffled, one chosen song (then the library shuffled)
    // or a playlist.
    public static final String MUSIC_RESUME = "resume", MUSIC_SHUFFLE = "shuffle", MUSIC_SONG = "song",
            MUSIC_PLAYLIST = "playlist";
    public static final int SNOOZE_MINUTES = 9;

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("Y1_SETTINGS", Context.MODE_PRIVATE);
    }

    public static boolean isEnabled(Context c) {
        return prefs(c).getBoolean("alarm_enabled", false);
    }

    public static int hour(Context c) {
        return prefs(c).getInt("alarm_hour", 7);
    }

    public static int minute(Context c) {
        return prefs(c).getInt("alarm_minute", 30);
    }

    public static String days(Context c) {
        return prefs(c).getString("alarm_days", DAYS_EVERY);
    }

    public static String music(Context c) {
        return prefs(c).getString("alarm_music", MUSIC_RESUME);
    }

    public static String song(Context c) {
        return prefs(c).getString("alarm_song", "");
    }

    public static String playlist(Context c) {
        return prefs(c).getString("alarm_playlist", "");
    }

    public static boolean fadeIn(Context c) {
        return prefs(c).getBoolean("alarm_fade_in", true);
    }

    private static boolean dayAllowed(String days, int dayOfWeek) {
        boolean weekend = dayOfWeek == Calendar.SATURDAY || dayOfWeek == Calendar.SUNDAY;
        if (DAYS_WEEKDAYS.equals(days)) return !weekend;
        if (DAYS_WEEKENDS.equals(days)) return weekend;
        return true; // every day, or once
    }

    // Next time the alarm (or a pending snooze) goes off, or -1 if it's off.
    public static long nextTrigger(Context c) {
        SharedPreferences p = prefs(c);
        long now = System.currentTimeMillis();
        long snooze = p.getLong("alarm_snooze_at", 0);
        long next = -1;
        if (isEnabled(c)) {
            Calendar cal = Calendar.getInstance();
            cal.set(Calendar.HOUR_OF_DAY, hour(c));
            cal.set(Calendar.MINUTE, minute(c));
            cal.set(Calendar.SECOND, 0);
            cal.set(Calendar.MILLISECOND, 0);
            String days = days(c);
            for (int i = 0; i < 8; i++) {
                if (cal.getTimeInMillis() > now + 1000 && dayAllowed(days, cal.get(Calendar.DAY_OF_WEEK))) {
                    next = cal.getTimeInMillis();
                    break;
                }
                cal.add(Calendar.DAY_OF_MONTH, 1);
            }
        }
        if (snooze > now && (next < 0 || snooze < next)) next = snooze;
        return next;
    }

    private static PendingIntent pending(Context c) {
        Intent i = new Intent(c, com.themoon.y1.AlarmReceiver.class);
        i.setAction(ACTION_RING);
        return PendingIntent.getBroadcast(c, 4242, i, PendingIntent.FLAG_UPDATE_CURRENT);
    }

    // Sets (or clears) the system alarm for the next ring.
    public static void schedule(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        PendingIntent pi = pending(c);
        am.cancel(pi);
        long next = nextTrigger(c);
        if (next > 0) {
            am.set(AlarmManager.RTC_WAKEUP, next, pi); // exact on API 17
            Log.i(TAG, "Next alarm at " + new java.util.Date(next));
        } else {
            Log.i(TAG, "No alarm set");
        }
    }

    // Called when it rings: a one-off alarm turns itself off; the snooze is used up.
    public static void onRang(Context c) {
        long snooze = prefs(c).getLong("alarm_snooze_at", 0);
        SharedPreferences.Editor e = prefs(c).edit();
        e.putLong("alarm_snooze_at", 0);
        boolean wasSnooze = snooze > 0 && Math.abs(System.currentTimeMillis() - snooze) < 120000;
        if (!wasSnooze && DAYS_ONCE.equals(days(c))) e.putBoolean("alarm_enabled", false);
        e.commit();
        schedule(c);
    }

    public static void snooze(Context c) {
        prefs(c).edit().putLong("alarm_snooze_at", System.currentTimeMillis() + SNOOZE_MINUTES * 60000L).commit();
        schedule(c);
    }
}
