package com.printserver.core.power

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.printserver.core.common.PrinterLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL

object InternetWatchdog {
    enum class Status { UNKNOWN, ONLINE, OFFLINE }

    @Volatile var status: Status = Status.UNKNOWN
        private set
    @Volatile var failStreak: Int = 0
        private set
    @Volatile var lastChangeMs: Long = 0
        private set

    private var appContext: Context? = null
    private var scope: CoroutineScope? = null
    private var job: Job? = null
    @Volatile private var running = false

    fun start(context: Context, enabled: () -> Boolean) {
        appContext = context.applicationContext
        if (scope == null) scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        running = true
        job?.cancel()
        job = scope!!.launch {
            while (isActive && running) {
                if (enabled()) probe()
                else { setStatus(Status.UNKNOWN, 0) }
                delay(60_000)
            }
        }
        PrinterLog.i(TAG, "Started")
    }

    fun stop() {
        running = false
        job?.cancel(); job = null
        scope?.cancel(); scope = null
    }

    fun statusShort(): String = when (status) {
        Status.ONLINE -> "net: online"
        Status.OFFLINE -> "net: DOWN ($failStreak)"
        Status.UNKNOWN -> if (running) "net: ?" else ""
    }

    fun statusLong(): String = buildString {
        append("Watchdog: ${if (running) "running" else "stopped"}\n")
        append("Internet: ${status.name.lowercase()}")
        if (status == Status.OFFLINE) append(" ($failStreak failed probes)")
        append("\nFailover: on outage you get a high-priority alert that opens the hotspot screen (one tap to share this phone's data). On recovery you get an 'internet restored' alert.\n")
        append("Note: Android does not allow apps to toggle tethering silently; and on some phones enabling the hotspot turns off Wi-Fi, pausing LAN services until it is disabled.")
    }

    private fun setStatus(s: Status, streak: Int) {
        val changed = s != status
        status = s
        failStreak = streak
        if (changed) {
            lastChangeMs = System.currentTimeMillis()
            PrinterLog.i(TAG, "Internet ${s.name.lowercase()} (streak=$streak)")
            when (s) {
                Status.OFFLINE -> notifyOutage()
                Status.ONLINE -> notifyRestored()
                else -> {}
            }
        }
    }

    private fun probe() {
        val ok = runCatching {
            val c = URL(PROBE_URL).openConnection() as HttpURLConnection
            c.connectTimeout = 6000
            c.readTimeout = 6000
            c.instanceFollowRedirects = false
            val code = c.responseCode
            c.disconnect()
            code in 200..399
        }.getOrDefault(false)
        if (ok) {
            setStatus(Status.ONLINE, 0)
        } else {
            val streak = failStreak + 1
            setStatus(if (streak >= 2) Status.OFFLINE else Status.UNKNOWN, streak)
        }
    }

    private fun notifyOutage() {
        val ctx = appContext ?: return
        val tether = Intent("android.settings.TETHER_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(
            ctx, 11, tether,
            if (android.os.Build.VERSION.SDK_INT >= 23)
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            else PendingIntent.FLAG_UPDATE_CURRENT
        )
        val full = PendingIntent.getActivity(
            ctx, 12, tether,
            if (android.os.Build.VERSION.SDK_INT >= 23)
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            else PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(com.printserver.app.R.drawable.ic_discovery)
            .setContentTitle("Internet is down")
            .setContentText("Tap to enable this phone's hotspot and share its mobile data")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setContentIntent(pi)
            .apply {
                if (android.os.Build.VERSION.SDK_INT >= 29) setFullScreenIntent(full, true)
            }
            .build()
        runCatching { nmm()?.notify(NOTIF_OUTAGE, n) }
    }

    private fun notifyRestored() {
        val ctx = appContext ?: return
        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(com.printserver.app.R.drawable.ic_discovery)
            .setContentTitle("Internet restored")
            .setContentText("You can disable the hotspot now")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        runCatching { nmm()?.notify(NOTIF_RESTORED, n) }
        runCatching { nmm()?.cancel(NOTIF_OUTAGE) }
    }

    private fun nmm(): android.app.NotificationManager? =
        appContext?.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager

    const val CHANNEL_ID = "net_watch"
    const val NOTIF_OUTAGE = 21
    const val NOTIF_RESTORED = 22
    private const val TAG = "NetWatch"
    private const val PROBE_URL = "http://connectivitycheck.gstatic.com/generate_204"
}
