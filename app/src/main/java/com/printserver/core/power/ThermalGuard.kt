package com.printserver.core.power

import android.content.Context
import android.os.Build
import android.os.PowerManager
import com.printserver.core.common.PrinterLog
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class ThermalGuard(
    context: Context,
    private val enabled: () -> Boolean,
    private val onLevel: (Int) -> Unit,
) {
    private val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private var listener: PowerManager.OnThermalStatusChangedListener? = null

    fun start() {
        if (Build.VERSION.SDK_INT < 29) {
            PrinterLog.i(TAG, "Thermal API unavailable (<29), polling disabled")
            return
        }
        listener = PowerManager.OnThermalStatusChangedListener { status ->
            if (enabled()) onLevel(status)
        }.also { l ->
            runCatching { pm.addThermalStatusListener(executor, l) }
                .onFailure { PrinterLog.w(TAG, "Listener add failed: ${it.message}") }
        }
        report(pm.currentThermalStatus)
    }

    fun stop() {
        if (Build.VERSION.SDK_INT >= 29) {
            listener?.let { runCatching { pm.removeThermalStatusListener(it) } }
        }
        listener = null
    }

    private fun report(status: Int) {
        val level = when (status) {
            PowerManager.THERMAL_STATUS_NONE -> 0
            PowerManager.THERMAL_STATUS_LIGHT -> 1
            PowerManager.THERMAL_STATUS_MODERATE -> 2
            PowerManager.THERMAL_STATUS_SEVERE -> 3
            else -> 4
        }
        if (level > 0) PrinterLog.w(TAG, "Thermal level $level (status=$status)")
        onLevel(level)
    }

    companion object { private const val TAG = "Thermal" }
}
