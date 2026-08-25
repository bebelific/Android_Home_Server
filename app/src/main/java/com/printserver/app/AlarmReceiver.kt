package com.printserver.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.printserver.core.common.PreferencesManager

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = PreferencesManager(context)
        if (prefs.emergencyStopped.value) return
        if (!HomeServerService.isRunning) {
            Log.i("AlarmReceiver", "heartbeat: service not running, restarting")
            val i = Intent(context, HomeServerService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }
        scheduleNext(context)
    }

    companion object {
        fun scheduleNext(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            val pi = PendingIntent.getBroadcast(
                context, 77, Intent(context, AlarmReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
            )
            val at = android.os.SystemClock.elapsedRealtime() + 15 * 60 * 1000L
            if (Build.VERSION.SDK_INT >= 23) {
                try {
                    am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
                } catch (_: Exception) {
                    am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
                }
            } else {
                @Suppress("DEPRECATION")
                am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
            }
        }
    }
}
