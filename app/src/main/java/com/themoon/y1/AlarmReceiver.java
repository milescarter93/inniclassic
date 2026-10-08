package com.themoon.y1;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.util.Log;

import com.themoon.y1.managers.AlarmScheduler;

// 🚀 [Extras] The music alarm went off: keep the CPU awake for a moment and bring InniClassic up with the
// "alarm" extra (MainActivity starts the music and shows the alarm screen), then set the next one.
public class AlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!AlarmScheduler.ACTION_RING.equals(intent.getAction())) return;
        Log.i("Y1Alarm", "Alarm ringing");
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock wake = pm.newWakeLock(PowerManager.FULL_WAKE_LOCK
                | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE, "InniClassic:alarm");
        wake.acquire(10000);
        Intent i = new Intent(context, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        i.putExtra(AlarmScheduler.EXTRA_ALARM, true);
        context.startActivity(i);
        AlarmScheduler.onRang(context);
    }
}
