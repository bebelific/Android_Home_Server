package com.printserver.core.power

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.printserver.app.R
import com.printserver.core.common.PrinterLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

object ChargeGuard {

    enum class Mode { UNINIT, NONE, MONITOR, ENFORCE }

    @Volatile var mode: Mode = Mode.UNINIT
        private set
    @Volatile var holding: Boolean = false
        private set
    @Volatile var controlPath: String? = null
        private set
    @Volatile var invert: Boolean = false
        private set

    @Volatile private var running = false
    private var appContext: Context? = null
    private var scope: CoroutineScope? = null
    private var job: Job? = null
    private var limitProvider: (() -> Int)? = null
    private var sampleProvider: (() -> Pair<Int, Boolean>?)? = null

    private const val RESUME_HYSTERESIS = 15

    fun start(
        context: Context,
        limitPct: () -> Int,
        sample: () -> Pair<Int, Boolean>?,
    ) {
        appContext = context.applicationContext
        limitProvider = limitPct
        sampleProvider = sample
        detectControl()
        mode = if (controlPath != null) Mode.ENFORCE else Mode.MONITOR
        if (scope == null) scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        running = true
        job?.cancel()
        job = scope!!.launch { loop() }
        PrinterLog.i(TAG, "Started mode=$mode path=$controlPath invert=$invert root=$hasRoot")
    }

    fun stop() {
        running = false
        job?.cancel(); job = null
        if (holding) setCharging(true)
        holding = false
        notifyHold(false, 0)
        scope?.cancel(); scope = null
        mode = Mode.UNINIT
    }

    fun statusShort(): String = when (mode) {
        Mode.ENFORCE -> if (holding) "[guard: HOLD]" else "[guard: on]"
        Mode.MONITOR -> "[guard: monitor]"
        else -> ""
    }

    fun statusLong(): String = buildString {
        append("Charging guard: ${mode.name.lowercase()}")
        if (mode == Mode.ENFORCE) append(" via $controlPath")
        if (holding) append(" — battery held (charging paused)")
        if (mode == Mode.MONITOR) append(" — no root: can alert only; use OEM 'protect battery' or a PSU board for true bypass")
    }

    private val hasRoot: Boolean by lazy { runCatching { root("id").contains("uid=0") }.getOrDefault(false) }

    private suspend fun loop() {
        while (running) {
            delay(30_000)
            val s = sampleProvider?.invoke() ?: continue
            val (pct, plugged) = s
            val limit = limitProvider?.invoke() ?: continue
            if (!plugged) {
                if (holding) {
                    holding = false
                    setCharging(true)
                    notifyHold(false, pct)
                    PrinterLog.i(TAG, "Unplugged — charging control released")
                }
                continue
            }
            if (!holding && pct >= limit && mode == Mode.ENFORCE) {
                if (setCharging(false)) {
                    holding = true
                    notifyHold(true, pct)
                    PrinterLog.i(TAG, "Limit $limit% reached — charging paused")
                } else {
                    mode = Mode.MONITOR
                    PrinterLog.w(TAG, "Sysfs write failed — demoted to MONITOR")
                }
            } else if (holding && pct <= limit - RESUME_HYSTERESIS) {
                if (setCharging(true)) {
                    holding = false
                    notifyHold(false, pct)
                    PrinterLog.i(TAG, "Battery ${pct}% ≤ resume — charging resumed")
                }
            } else if (mode == Mode.MONITOR && pct >= limit && pct % 5 == 0) {
                PrinterLog.w(TAG, "Battery at $pct% while plugged (limit $limit) — no root to pause charging")
            }
        }
    }

    private fun detectControl() {
        val candidates = listOf(
            "/sys/class/power_supply/battery/charging_enabled" to false,
            "/sys/class/power_supply/battery/battery_charging_enabled" to false,
            "/sys/class/power_supply/battery/chg_disable" to true,
            "/sys/class/power_supply/battery/input_suspend" to true,
        )
        for ((path, inv) in candidates) {
            if (File(path).exists() && hasRoot) {
                controlPath = path
                invert = inv
                return
            }
        }
        controlPath = null
    }

    private fun setCharging(enable: Boolean): Boolean {
        val path = controlPath ?: return false
        val raw = if (invert) !enable else enable
        val value = if (raw) "1" else "0"
        val out = root("echo $value > $path")
        val verify = root("cat $path").trim()
        val ok = verify == value || out.isEmpty()
        PrinterLog.d(TAG, "setCharging($enable) wrote '$value' -> '$path' verify='$verify'")
        return ok
    }

    private fun root(cmd: String): String = runCatching {
        val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true)
        p.start().let { proc ->
            val out = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            out
        }
    }.getOrDefault("")

    private fun notifyHold(active: Boolean, pct: Int) {
        val ctx = appContext ?: return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (active) {
            val pi = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, Class.forName("com.printserver.app.MainActivity")),
                if (android.os.Build.VERSION.SDK_INT >= 23)
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                else PendingIntent.FLAG_UPDATE_CURRENT
            )
            val n: Notification = NotificationCompat.Builder(ctx, "charge_guard")
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("Battery held at $pct%")
                .setContentText("Charging paused to protect the battery")
                .setOngoing(true)
                .setContentIntent(pi)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
            runCatching { nm.notify(NOTIF_ID, n) }
        } else {
            runCatching { nm.cancel(NOTIF_ID) }
        }
    }

    const val CHANNEL_ID = "charge_guard"
    const val NOTIF_ID = 2
    private const val TAG = "ChargeGuard"
}
