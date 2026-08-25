package com.printserver.core.discovery

import android.content.Context
import com.printserver.core.common.PrinterLog
import java.net.InetAddress
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo

class DiscoveryService(
    context: Context,
) {
    interface PortSource {
        fun enabled(id: String): Boolean
        fun printPort(): Int
        fun webdavPort(): Int
        fun ftpPort(): Int
        fun mjpegPort(): Int
        fun adblockPort(): Int = 53
    }

    private var jmdns: JmDNS? = null
    private val wifiLockHelper = context

    fun start(ports: PortSource) {
        stop()
        val ip = localWifiIp(wifiLockHelper) ?: run {
            PrinterLog.w(TAG, "No Wi-Fi IPv4 yet; retry on refresh()")
            return
        }
        jmdns = runCatching { JmDNS.create(InetAddress.getByName(ip), "androidhomeserver") }
            .getOrNull() ?: run {
            PrinterLog.e(TAG, "JmDNS create failed")
            return
        }
        PrinterLog.i(TAG, "Started on $ip")
        refresh(ports)
    }

    fun refresh(ports: PortSource) {
        val jm = jmdns ?: return
        runCatching { jm.unregisterAllServices() }
        register(jm, "_http._tcp.local.", "AndroidHomeServer Files", ports.webdavPort(), ports.enabled("file_sharing"), "path=/")
        register(jm, "_ftp._tcp.local.", "AndroidHomeServer FTP", ports.ftpPort(), ports.enabled("file_sharing"))
        register(jm, "_pdl-datastream._tcp.local.", "AndroidHomeServer Print", ports.printPort(), ports.enabled("print_server"))
        register(jm, "_androidhomeserver._tcp.local.", "AndroidHomeServer", ports.mjpegPort(), true, "path=/status")
        register(jm, "_domain._udp.local.", "AndroidHomeServer DNS", ports.adblockPort(), ports.enabled("adblock"))
    }

    private fun register(jm: JmDNS, type: String, name: String, port: Int, enabled: Boolean, txt: String = "") {
        if (!enabled) return
        runCatching {
            jm.registerService(ServiceInfo.create(type, name, port, txt))
            PrinterLog.i(TAG, "Advertised $name :$port ($type)")
        }.onFailure { PrinterLog.w(TAG, "Advertise failed $type: ${it.message}") }
    }

    fun stop() {
        jmdns?.let { jm ->
            runCatching { jm.unregisterAllServices() }
            runCatching { jm.close() }
        }
        jmdns = null
    }

    companion object {
        private const val TAG = "Discovery"

        data class NetIface(val name: String, val ip: String, val isEthernet: Boolean)

        fun localInterfaces(context: Context? = null): List<NetIface> = try {
            java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces()).asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { ni ->
                    java.util.Collections.list(ni.inetAddresses).asSequence()
                        .filterIsInstance<java.net.Inet4Address>()
                        .filter { !it.isLoopbackAddress && it.isSiteLocalAddress }
                        .map { NetIface(ni.name, it.hostAddress ?: "", isEthernetIface(ni.name)) }
                }
                .filter { it.ip.isNotEmpty() }
                .toList()
        } catch (_: Exception) { emptyList() }

        fun isEthernetIface(name: String): Boolean {
            val n = name.lowercase()
            return n.startsWith("eth") || n.startsWith("usb") || n.startsWith("rndis") ||
                n.contains("ax88179") || n.contains("rtl815")
        }

        fun localWifiIp(context: Context? = null): String? =
            localInterfaces(context).firstOrNull { !it.isEthernet }?.ip
            ?: localInterfaces(context).firstOrNull()?.ip

        fun localIp(context: Context? = null): String? = localInterfaces(context).firstOrNull()?.ip
    }
}
