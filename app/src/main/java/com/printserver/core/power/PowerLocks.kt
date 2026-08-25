package com.printserver.core.power

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager as AndroidPowerManager
import com.printserver.core.common.PrinterLog

class PowerLocks(private val context: Context) {
    private val pm = context.getSystemService(Context.POWER_SERVICE) as AndroidPowerManager
    private val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private var wakeLock: AndroidPowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var holders = 0
    private val lock = Any()

    fun acquire(reason: String) = synchronized(lock) {
        holders++
        if (holders == 1) {
            wakeLock = pm.newWakeLock(AndroidPowerManager.PARTIAL_WAKE_LOCK, "AndroidHomeServer:wake").apply {
                setReferenceCounted(false); acquire()
            }
            @Suppress("DEPRECATION")
            val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = wm.createWifiLock(mode, "AndroidHomeServer:wifi").apply {
                setReferenceCounted(false); acquire()
            }
            PrinterLog.i(TAG, "Locks acquired ($reason)")
        }
    }

    fun release(reason: String) = synchronized(lock) {
        holders = maxOf(0, holders - 1)
        if (holders == 0) {
            runCatching { wakeLock?.release() }; wakeLock = null
            runCatching { wifiLock?.release() }; wifiLock = null
            PrinterLog.i(TAG, "Locks released ($reason)")
        }
    }

    companion object { private const val TAG = "PowerLocks" }
}
