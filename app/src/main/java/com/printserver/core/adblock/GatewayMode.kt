package com.printserver.core.adblock

import android.content.Context
import com.printserver.core.common.PrinterLog
import com.printserver.core.common.PreferencesManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

object GatewayMode {
    @Volatile var active: Boolean = false
        private set
    @Volatile var lastStatus: String = ""
        private set

    private var scope: CoroutineScope? = null
    private var syncJob: Job? = null
    private val natRules = mutableListOf<String>()
    private val fwdRules = mutableListOf<String>()

    private val INTERFACES = listOf("ap0", "ap1", "wlan1", "swlan0", "swlan1")

    fun hasRoot(): Boolean = runCatching {
        ProcessBuilder("su", "-c", "id").start().inputStream.bufferedReader().readText().contains("uid=0")
    }.getOrDefault(false)

    fun apply(context: Context, prefs: PreferencesManager, dnsPort: Int): Result<String> {
        if (!hasRoot()) {
            lastStatus = "no root"
            return Result.failure(IllegalStateException("root required for gateway mode"))
        }
        clear(context)
        val added = mutableListOf<String>()
        for (iface in INTERFACES) {
            for (proto in listOf("udp", "tcp")) {
                val rule = "-t nat -A PREROUTING -i $iface -p $proto --dport 53 -j REDIRECT --to-ports $dnsPort"
                runCatching { su("iptables $rule") }
                added.add(rule)
            }
        }
        natRules.clear()
        natRules.addAll(added)
        active = true
        PrinterLog.i(TAG, "Gateway DNS redirect active (${natRules.size} rules) -> port $dnsPort")

        if (scope == null) scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        syncJob?.cancel()
        syncJob = scope!!.launch {
            while (isActive) {
                syncForwardRules(context, prefs)
                delay(60_000)
            }
        }
        syncForwardRules(context, prefs)
        lastStatus = if (fwdRules.isNotEmpty()) "active · dns redirect + bedtime hard-block" else "active · dns redirect only"
        return Result.success(lastStatus)
    }

    private fun syncForwardRules(context: Context, prefs: PreferencesManager) {
        val kids = com.printserver.core.adblock.ParentalControl.devices(prefs)
        val hardBlock = prefs.pcEnabled.value && (
            com.printserver.core.adblock.ParentalControl.inScheduleWindow(prefs) ||
                prefs.pcPauseUntil.value > System.currentTimeMillis()
            )
        val wanted = if (hardBlock) kids else emptyList()

        fwdRules.removeAll { r ->
            val ip = r.substringAfter("-s ").substringBefore(" ")
            if (ip !in wanted) {
                su("iptables -D FORWARD -s $ip -j REJECT")
                true
            } else false
        }
        for (ip in wanted) {
            if (fwdRules.none { it.contains("-s $ip ") }) {
                val rule = "-I FORWARD -s $ip -j REJECT"
                if (runCatching { su("iptables $rule") }.getOrDefault(false) == true) {
                    fwdRules.add(rule)
                    PrinterLog.i(TAG, "FORWARD REJECT added for $ip (bedtime/pause)")
                }
            }
        }
        lastStatus = if (hardBlock) "active · hard-block ON (${fwdRules.size} ips)" else "active · dns filter only"
    }

    fun clear(context: Context): Boolean {
        syncJob?.cancel(); syncJob = null
        for (ip in fwdRules.map { it.substringAfter("-s ").substringBefore(" ") }) {
            runCatching { su("iptables -D FORWARD -s $ip -j REJECT") }
        }
        for (r in natRules) runCatching { su("iptables -D $r") }
        val had = fwdRules.isNotEmpty() || natRules.isNotEmpty()
        fwdRules.clear()
        natRules.clear()
        if (active || had) PrinterLog.i(TAG, "Gateway mode cleared")
        active = false
        lastStatus = "off"
        return true
    }

    private fun su(cmd: String): String? = runCatching {
        val pb = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true)
        val proc = pb.start()
        val out = proc.inputStream.bufferedReader().readText()
        proc.waitFor()
        out
    }.getOrNull()

    private const val TAG = "GatewayMode"
}
