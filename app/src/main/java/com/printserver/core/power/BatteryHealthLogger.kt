package com.printserver.core.power

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.printserver.core.common.PrinterLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BatteryHealthLogger(private val context: Context, private val logFile: File) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    @Volatile private var running = false

    fun start() {
        if (running) return
        running = true
        scope.launch {
            while (isActive && running) {
                sample()
                delay(INTERVAL_MS)
            }
        }
        PrinterLog.i(TAG, "Started -> ${logFile.name}")
    }

    fun stop() {
        running = false
        scope.cancel()
    }

    data class Sample(
        val percent: Int, val tempC: Double, val voltageV: Double,
        val health: String, val status: String, val plugged: String,
    )

    fun readNow(): Sample? {
        val i = sticky() ?: return null
        return Sample(
            percent(i), temp(i), voltage(i), healthStr(i), statusStr(i), plugStr(i)
        )
    }

    private fun sticky(): Intent? =
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

    private fun sample() {
        val i = sticky() ?: return
        val line = listOf(
            fmt.format(Date()),
            percent(i),
            "%.1f".format(temp(i)),
            "%.3f".format(voltage(i)),
            healthStr(i),
            statusStr(i),
            plugStr(i),
        ).joinToString(",") { it.toString() }
        try {
            if (logFile.exists() && logFile.length() > MAX_BYTES) {
                File(logFile.parentFile, logFile.name + ".old").let { old ->
                    if (old.exists()) old.delete()
                    logFile.renameTo(old)
                }
            }
            logFile.appendText(line + "\n")
        } catch (_: Exception) {}
    }

    private fun percent(i: Intent): Int {
        val l = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val s = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        return if (l >= 0 && s > 0) l * 100 / s else -1
    }
    private fun temp(i: Intent) = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0
    private fun voltage(i: Intent) = i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) / 1000.0

    private fun healthStr(i: Intent) = when (i.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN)) {
        BatteryManager.BATTERY_HEALTH_GOOD -> "GOOD"
        BatteryManager.BATTERY_HEALTH_OVERHEAT -> "OVERHEAT"
        BatteryManager.BATTERY_HEALTH_DEAD -> "DEAD"
        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "OVER_VOLTAGE"
        BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "FAILURE"
        BatteryManager.BATTERY_HEALTH_COLD -> "COLD"
        else -> "UNKNOWN"
    }

    private fun statusStr(i: Intent) = when (i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
        BatteryManager.BATTERY_STATUS_CHARGING -> "CHARGING"
        BatteryManager.BATTERY_STATUS_DISCHARGING -> "DISCHARGING"
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "NOT_CHARGING"
        BatteryManager.BATTERY_STATUS_FULL -> "FULL"
        else -> "UNKNOWN"
    }

    private fun plugStr(i: Intent) = when (i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)) {
        BatteryManager.BATTERY_PLUGGED_AC -> "AC"
        BatteryManager.BATTERY_PLUGGED_USB -> "USB"
        BatteryManager.BATTERY_PLUGGED_WIRELESS -> "WIRELESS"
        else -> "NONE"
    }

    companion object {
        private const val TAG = "BatteryLog"
        private const val MAX_BYTES = 512L * 1024
        private const val INTERVAL_MS = 15L * 60 * 1000
    }
}
