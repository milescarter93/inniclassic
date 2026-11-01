package com.themoon.y1;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;

// 🚀 [Crash recovery] While music plays, MainActivity keeps pushing this alarm WATCHDOG_MS into the future
// (every periodic position save). If the process dies without the Java crash handler running - a native
// crash in the VM, or the system killing it - nothing restarts the launcher until a key is pressed, so the
// music just stops. The alarm then fires, this receiver starts a fresh process, and MainActivity resumes the
// queue (PlaybackStateStore.diedWhilePlaying). In a live process it does nothing.
public class WatchdogReceiver extends BroadcastReceiver {
    private static final String ACTION = "com.themoon.y1.WATCHDOG";
    private static final long WATCHDOG_MS = 30000;

    public static void arm(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + WATCHDOG_MS, pending(context));
    }

    public static void disarm(Context context) {
        AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        am.cancel(pending(context));
    }

    private static PendingIntent pending(Context context) {
        Intent i = new Intent(context, WatchdogReceiver.class).setAction(ACTION);
        return PendingIntent.getBroadcast(context, 0, i, PendingIntent.FLAG_UPDATE_CURRENT);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ACTION.equals(intent.getAction()) || MainActivity.instance != null) return;
        Log.i("Y1Resume", "Watchdog: the launcher died while playing, starting it again");
        Intent i = new Intent(context, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(i);
    }
}
