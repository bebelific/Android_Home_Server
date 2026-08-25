package com.printserver.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.printserver.core.camera.CameraService
import com.printserver.core.common.PrinterLog
import com.printserver.core.common.PreferencesManager
import com.printserver.core.common.ServiceRegistry
import com.printserver.core.common.ServiceState
import com.printserver.core.discovery.DiscoveryService
import com.printserver.core.files.FileService
import com.printserver.core.power.BatteryHealthLogger
import com.printserver.core.power.PowerLocks
import com.printserver.core.power.ThermalGuard
import com.printserver.core.power.Watchdog
import com.printserver.core.print.PrintService
import com.printserver.core.usb.UsbPrinterManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

class HomeServerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var prefs: PreferencesManager
    private lateinit var locks: PowerLocks
    private lateinit var battery: BatteryHealthLogger
    private lateinit var thermal: ThermalGuard
    private lateinit var watchdog: Watchdog
    private lateinit var registry: ServiceRegistry
    private lateinit var usb: UsbPrinterManager
    private lateinit var discovery: DiscoveryService

    private val channelId = "home_server"
    private val notifId = 1
    @Volatile private var shuttingDown = false

    override fun onCreate() {
        super.onCreate()
        PrinterLog.i(TAG, "Creating")
        prefs = PreferencesManager(this)
        locks = PowerLocks(this)
        usb = UsbPrinterManager(this)
        registry = ServiceRegistry(applicationContext)
        battery = BatteryHealthLogger(this, File(filesDir, "battery_health.csv"))

        val print = PrintService(this, prefs, usb)
        val files = FileService(prefs)
        val camera = CameraService(prefs)
        val backup = com.printserver.core.backup.PhotoBackupService(prefs)
        val adblock = com.printserver.core.adblock.AdBlockService(prefs)
        registry.register(print)
        registry.register(files)
        registry.register(camera)
        registry.register(backup)
        registry.register(adblock)
        registry.register(DiscoverySvc())

        discovery = DiscoveryService(applicationContext)

        thermal = ThermalGuard(this, { prefs.thermalThrottleEnabled.value }, ::onThermalLevel)
        watchdog = Watchdog(registry, battery) { emergencyStop("watchdog") }

        createChannel()
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                android.app.NotificationChannel(
                    com.printserver.core.power.ChargeGuard.CHANNEL_ID,
                    "Charging guard",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
        com.printserver.core.power.ChargeGuard.start(
            this,
            { prefs.chargeLimit.value },
            { battery.readNow()?.let { it.percent to (it.plugged != "NONE") } }
        )
        if (Build.VERSION.SDK_INT >= 26) {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
                android.app.NotificationChannel(
                    com.printserver.core.power.InternetWatchdog.CHANNEL_ID,
                    "Internet watchdog",
                    NotificationManager.IMPORTANCE_HIGH
                )
            )
        }
        com.printserver.core.power.InternetWatchdog.start(this) { prefs.netWatchEnabled.value }
        startForegroundCompat()
        thermal.start()
        battery.start()
        watchdog.start()
        restoreToggles()
        PrinterLog.i(TAG, "Created")
    }

    private fun restoreToggles() {
        for (svc in registry.allServices) {
            val wanted = when (svc.id) {
                ID_PRINT -> prefs.printServerEnabled.value
                ID_FILES -> prefs.fileSharingEnabled.value
                ID_WEBCAM -> prefs.webcamEnabled.value
                ID_DISCOVERY -> prefs.discoveryEnabled.value
                "photo_backup" -> prefs.backupEnabled.value
                "adblock" -> prefs.adblockEnabled.value
                else -> false
            }
            if (wanted && svc.state.value == ServiceState.DISABLED) {
                PrinterLog.i(TAG, "Restoring ${svc.id}")
                applyToggle(svc.id, true)
            }
        }
    }

    fun onToggle(id: String, enabled: Boolean) {
        persist(id, enabled)
        applyToggle(id, enabled)
    }

    fun services(): ServiceRegistry = registry
    fun printService(): PrintService? = registry.get(ID_PRINT) as? PrintService

    fun portSummary(): String {
        val sb = StringBuilder()
        for (svc in registry.allServices) {
            if (svc.state.value == ServiceState.RUNNING) sb.append("  :").append(activePort(svc.id))
        }
        return sb.toString()
    }

    private fun activePort(id: String): Int = when (id) {
        ID_PRINT -> prefs.printPort.value
        ID_FILES -> prefs.webdavPort.value
        ID_WEBCAM -> prefs.mjpegPort.value
        ID_DISCOVERY -> 5353
        "adblock" -> prefs.adblockPort.value
        else -> 0
    }

    fun logTail(): String = PrinterLog.tail(14).joinToString("\n")

    private fun applyToggle(id: String, enabled: Boolean) {
        val svc = registry.get(id) ?: return
        scope.launch {
            if (enabled && svc.state.value == ServiceState.DISABLED) {
                locks.acquire(id)
                runCatching { svc.start(applicationContext) }
                    .onFailure { PrinterLog.e(TAG, "$id start failed: ${it.message}") }
            } else if (!enabled && svc.state.value != ServiceState.DISABLED) {
                runCatching { svc.stop() }
                    .onFailure { PrinterLog.e(TAG, "$id stop failed: ${it.message}") }
                locks.release(id)
            }
            refreshDiscovery()
            startForegroundCompat()
        }
    }

    fun restartAll() {
        for (svc in registry.allServices.toList()) {
            if (svc.state.value == ServiceState.RUNNING) {
                onToggle(svc.id, false)
            }
        }
        scope.launch {
            kotlinx.coroutines.delay(800)
            for (svc in registry.allServices.toList()) {
                val wanted = persisted(svc.id)
                if (wanted) onToggle(svc.id, true)
            }
        }
    }

    private fun persisted(id: String): Boolean = when (id) {
        ID_PRINT -> prefs.printServerEnabled.value
        ID_FILES -> prefs.fileSharingEnabled.value
        ID_WEBCAM -> prefs.webcamEnabled.value
        ID_DISCOVERY -> prefs.discoveryEnabled.value
        "photo_backup" -> prefs.backupEnabled.value
        "adblock" -> prefs.adblockEnabled.value
        else -> false
    }

    private fun persist(id: String, v: Boolean) = when (id) {
        ID_PRINT -> prefs.setPrintServerEnabled(v)
        ID_FILES -> prefs.setFileSharingEnabled(v)
        ID_WEBCAM -> prefs.setWebcamEnabled(v)
        ID_DISCOVERY -> prefs.setDiscoveryEnabled(v)
        "photo_backup" -> prefs.setBackupEnabled(v)
        "adblock" -> prefs.setAdblockEnabled(v)
        else -> {}
    }

    private fun runningCount(): Int =
        registry.allServices.count { it.state.value == ServiceState.RUNNING }

    private fun refreshDiscovery() {
        if (!prefs.discoveryEnabled.value) return
        scope.launch(Dispatchers.IO) { discovery.refresh(portSource()) }
    }

    private fun portSource(): DiscoveryService.PortSource = object : DiscoveryService.PortSource {
        override fun enabled(id: String): Boolean =
            registry.get(id)?.state?.value == ServiceState.RUNNING || id == "self"
        override fun printPort() = prefs.printPort.value
        override fun webdavPort() = prefs.webdavPort.value
        override fun ftpPort() = prefs.ftpPort.value
        override fun mjpegPort() = prefs.mjpegPort.value
        override fun adblockPort() = prefs.adblockPort.value
    }

    private fun onThermalLevel(level: Int) {
        val filesSvc = registry.get(ID_FILES) as? FileService
        val camSvc = registry.get(ID_WEBCAM) as? CameraService
        when (level) {
            1 -> { camSvc?.throttleCamera(0.5f); filesSvc?.throttleTransfers(0.5f) }
            2 -> { camSvc?.pauseCamera(); filesSvc?.throttleTransfers(0.25f) }
            3 -> { onToggle(ID_WEBCAM, false); onToggle(ID_FILES, false) }
            else -> if (level >= 4) emergencyStop("thermal")
        }
    }

    private fun emergencyStop(reason: String) {
        if (shuttingDown) return
        shuttingDown = true
        PrinterLog.e(TAG, "EMERGENCY STOP ($reason)")
        registry.stopAll()
        thermal.stop()
        battery.stop()
        runCatching { discovery.stop() }
        locks.release(reason)
        stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        return START_STICKY
    }

    override fun onDestroy() {
        com.printserver.core.power.ChargeGuard.stop()
        com.printserver.core.power.InternetWatchdog.stop()
        com.printserver.core.adblock.GatewayMode.clear(this)
        if (!shuttingDown) {
            registry.stopAll()
            thermal.stop()
            battery.stop()
            runCatching { discovery.stop() }
        }
        scope.cancel()
        super.onDestroy()
        PrinterLog.i(TAG, "Destroyed")
    }

    override fun onBind(intent: Intent?): IBinder = LocalBinder()

    inner class LocalBinder : android.os.Binder() {
        fun service(): HomeServerService = this@HomeServerService
    }

    inner class DiscoverySvc : com.printserver.core.common.Service {
        override val id = ID_DISCOVERY
        override val displayName = "Discovery"
        override val defaultPort = 5353
        private val _state = kotlinx.coroutines.flow.MutableStateFlow(ServiceState.DISABLED)
        override val state: kotlinx.coroutines.flow.StateFlow<ServiceState> = _state.asStateFlow()
        override suspend fun start(context: Context): Result<Unit> = try {
            discovery.start(portSource())
            _state.value = ServiceState.RUNNING
            Result.success(Unit)
        } catch (e: Exception) {
            _state.value = ServiceState.ERROR
            Result.failure(e)
        }
        override suspend fun stop(): Result<Unit> = try {
            discovery.stop()
            _state.value = ServiceState.DISABLED
            Result.success(Unit)
        } catch (e: Exception) {
            _state.value = ServiceState.ERROR
            Result.failure(e)
        }
        override fun isHealthy(): Boolean = _state.value == ServiceState.RUNNING
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(channelId, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW).apply {
                enableLights(false); enableVibration(false)
            }
        )
    }

    private fun buildNotification(): android.app.Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            else PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText("${runningCount()} services running")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pi)
            .build()
    }

    private fun startForegroundCompat() {
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, notifId, buildNotification(), type)
    }

    companion object {
        const val TAG = "HomeServer"
        const val ID_PRINT = "print_server"
        const val ID_FILES = "file_sharing"
        const val ID_WEBCAM = "webcam"
        const val ID_DISCOVERY = "discovery"
        const val ID_BACKUP = "photo_backup"
        const val ID_ADBLOCK = "adblock"
    }
}
