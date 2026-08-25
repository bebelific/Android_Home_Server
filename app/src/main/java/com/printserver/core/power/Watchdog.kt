package com.printserver.core.power

import com.printserver.core.common.PrinterLog
import com.printserver.core.common.ServiceRegistry
import com.printserver.core.common.ServiceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class Watchdog(
    private val registry: ServiceRegistry,
    private val battery: BatteryHealthLogger,
    private val onCritical: () -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var running = false
    private var lastDeepCheck = 0L

    fun start() {
        if (running) return
        running = true
        scope.launch {
            while (isActive && running) {
                delay(INTERVAL_MS)
                checkServices()
                if (System.currentTimeMillis() - lastDeepCheck > DEEP_INTERVAL_MS) {
                    lastDeepCheck = System.currentTimeMillis()
                    deepCheck()
                }
            }
        }
        PrinterLog.i(TAG, "Started")
    }

    fun stop() {
        running = false
        scope.cancel()
    }

    private suspend fun checkServices() {
        for (svc in registry.allServices) {
            if (svc.state.value == ServiceState.RUNNING && !svc.isHealthy()) {
                PrinterLog.w(TAG, "${svc.id} unhealthy -> restarting")
                runCatching { svc.stop() }
                runCatching { svc.start(registry.appContext) }
            }
        }
    }

    private fun deepCheck() {
        val issues = mutableListOf<String>()
        val dir = registry.appContext.filesDir
        val freeMb = dir.usableSpace / (1024 * 1024)
        if (freeMb < 100) issues.add("low disk ${freeMb}MB")

        val rt = Runtime.getRuntime()
        val usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
        if (usedMb > 220) issues.add("heap ${usedMb}MB")

        battery.readNow()?.let { s ->
            if (s.tempC > 45.0) issues.add("temp %.1fC".format(s.tempC))
            if (s.health == "DEAD" || s.health == "OVERHEAT") {
                issues.add("battery ${s.health}")
                onCritical()
            }
        }

        if (issues.isEmpty()) PrinterLog.i(TAG, "Deep check OK")
        else PrinterLog.w(TAG, "Issues: ${issues.joinToString("; ")}")
    }

    companion object {
        private const val TAG = "Watchdog"
        private const val INTERVAL_MS = 60_000L
        private const val DEEP_INTERVAL_MS = 6L * 60 * 60 * 1000
    }
}
