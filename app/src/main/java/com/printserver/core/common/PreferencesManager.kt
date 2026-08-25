package com.printserver.core.common

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object PrefKeys {
    const val PRINT_SERVER_ENABLED = "print_server_enabled"
    const val FILE_SHARING_ENABLED = "file_sharing_enabled"
    const val WEBCAM_ENABLED = "webcam_enabled"
    const val DISCOVERY_ENABLED = "discovery_enabled"
    const val WEBDAV_PORT = "webdav_port"
    const val FTP_PORT = "ftp_port"
    const val MJPEG_PORT = "mjpeg_port"
    const val PRINT_PORT = "print_port"
    const val CHARGE_LIMIT = "charge_limit"
    const val THERMAL_THROTTLE_ENABLED = "thermal_throttle_enabled"
    const val USERNAME = "username"
    const val PASSWORD_HASH = "password_hash"
    const val SHARE_ROOT = "share_root"
    const val CAMERA_FACING_BACK = "camera_facing_back"
    const val CAMERA_TORCH = "camera_torch"
    const val MJPEG_QUALITY = "mjpeg_quality"
    const val MJPEG_FPS = "mjpeg_fps"
    const val BACKUP_ENABLED = "backup_enabled"
    const val BACKUP_INTERVAL_MIN = "backup_interval_min"
    const val BACKUP_LAST_RUN = "backup_last_run"
    const val DRIVE_TREE_URI = "drive_tree_uri"
    const val PRINT_ARCHIVE = "print_archive"
    const val ADBLOCK_ENABLED = "adblock_enabled"
    const val ADBLOCK_PORT = "adblock_port"
    const val BACKUP_USE_USB = "backup_use_usb"
    const val BACKUP_LOCAL = "backup_local"
    const val NET_WATCH_ENABLED = "net_watch_enabled"
    const val DISPLAY_KEEP_ON = "display_keep_on"
    const val SAVER_SHOW_LOGO = "saver_show_logo"
    const val SAVER_SHOW_CLOCK = "saver_show_clock"
    const val SAVER_SHOW_STATUS = "saver_show_status"
    const val SAVER_SPEED = "saver_speed"
    const val DARK_MODE = "dark_mode"
    const val EMERGENCY_STOPPED = "emergency_stopped"
    const val PC_ENABLED = "pc_enabled"
    const val PC_DEVICES = "pc_devices"
    const val PC_BLOCK_ADULT = "pc_block_adult"
    const val PC_BLOCK_SOCIAL = "pc_block_social"
    const val PC_SCHEDULE_ENABLED = "pc_schedule_enabled"
    const val PC_SCHEDULE_START = "pc_schedule_start"
    const val PC_SCHEDULE_END = "pc_schedule_end"
    const val PC_PAUSE_UNTIL = "pc_pause_until"
}

class PreferencesManager(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("home_server_prefs", Context.MODE_PRIVATE)

    private val _printServerEnabled = MutableStateFlow(prefs.getBoolean(PrefKeys.PRINT_SERVER_ENABLED, true))
    private val _fileSharingEnabled = MutableStateFlow(prefs.getBoolean(PrefKeys.FILE_SHARING_ENABLED, false))
    private val _webcamEnabled = MutableStateFlow(prefs.getBoolean(PrefKeys.WEBCAM_ENABLED, false))
    private val _discoveryEnabled = MutableStateFlow(prefs.getBoolean(PrefKeys.DISCOVERY_ENABLED, true))

    private val _printPort = MutableStateFlow(prefs.getInt(PrefKeys.PRINT_PORT, 9100))
    private val _webdavPort = MutableStateFlow(prefs.getInt(PrefKeys.WEBDAV_PORT, 8080))
    private val _ftpPort = MutableStateFlow(prefs.getInt(PrefKeys.FTP_PORT, 2121))
    private val _mjpegPort = MutableStateFlow(prefs.getInt(PrefKeys.MJPEG_PORT, 8081))

    private val _chargeLimit = MutableStateFlow(prefs.getInt(PrefKeys.CHARGE_LIMIT, 80))
    private val _thermalThrottleEnabled = MutableStateFlow(prefs.getBoolean(PrefKeys.THERMAL_THROTTLE_ENABLED, true))

    private val _username = MutableStateFlow(prefs.getString(PrefKeys.USERNAME, "homeserver") ?: "homeserver")
    private val _passwordHash = MutableStateFlow(prefs.getString(PrefKeys.PASSWORD_HASH, "") ?: "")

    private val _shareRoot = MutableStateFlow(prefs.getString(PrefKeys.SHARE_ROOT, "") ?: "")
    private val _cameraFacingBack = MutableStateFlow(prefs.getBoolean(PrefKeys.CAMERA_FACING_BACK, true))
    private val _cameraTorch = MutableStateFlow(prefs.getBoolean(PrefKeys.CAMERA_TORCH, false))
    private val _mjpegQuality = MutableStateFlow(prefs.getInt(PrefKeys.MJPEG_QUALITY, 70))
    private val _mjpegFps = MutableStateFlow(prefs.getInt(PrefKeys.MJPEG_FPS, 15))

    private val _backupEnabled = MutableStateFlow(prefs.getBoolean(PrefKeys.BACKUP_ENABLED, false))
    private val _backupIntervalMin = MutableStateFlow(prefs.getInt(PrefKeys.BACKUP_INTERVAL_MIN, 15))
    private val _backupLastRun = MutableStateFlow(prefs.getLong(PrefKeys.BACKUP_LAST_RUN, 0L))
    private val _driveTreeUri = MutableStateFlow(prefs.getString(PrefKeys.DRIVE_TREE_URI, "") ?: "")
    private val _printArchive = MutableStateFlow(prefs.getBoolean(PrefKeys.PRINT_ARCHIVE, false))
    private val _adblockEnabled = MutableStateFlow(prefs.getBoolean(PrefKeys.ADBLOCK_ENABLED, false))
    private val _adblockPort = MutableStateFlow(prefs.getInt(PrefKeys.ADBLOCK_PORT, 53))
    private val _backupUseUsb = MutableStateFlow(prefs.getBoolean(PrefKeys.BACKUP_USE_USB, false))
    private val _backupLocal = MutableStateFlow(prefs.getBoolean(PrefKeys.BACKUP_LOCAL, true))
    private val _netWatchEnabled = MutableStateFlow(prefs.getBoolean(PrefKeys.NET_WATCH_ENABLED, false))
    private val _displayKeepOn = MutableStateFlow(prefs.getBoolean(PrefKeys.DISPLAY_KEEP_ON, false))
    private val _saverShowLogo = MutableStateFlow(prefs.getBoolean(PrefKeys.SAVER_SHOW_LOGO, true))
    private val _saverShowClock = MutableStateFlow(prefs.getBoolean(PrefKeys.SAVER_SHOW_CLOCK, true))
    private val _saverShowStatus = MutableStateFlow(prefs.getBoolean(PrefKeys.SAVER_SHOW_STATUS, true))
    private val _saverSpeed = MutableStateFlow(prefs.getInt(PrefKeys.SAVER_SPEED, 1))
    private val _darkMode = MutableStateFlow(prefs.getBoolean(PrefKeys.DARK_MODE, true))
    private val _emergencyStopped = MutableStateFlow(prefs.getBoolean(PrefKeys.EMERGENCY_STOPPED, false))
    private val _pcEnabled = MutableStateFlow(prefs.getBoolean(PrefKeys.PC_ENABLED, false))
    private val _pcDevices = MutableStateFlow(prefs.getString(PrefKeys.PC_DEVICES, "") ?: "")
    private val _pcBlockAdult = MutableStateFlow(prefs.getBoolean(PrefKeys.PC_BLOCK_ADULT, true))
    private val _pcBlockSocial = MutableStateFlow(prefs.getBoolean(PrefKeys.PC_BLOCK_SOCIAL, false))
    private val _pcScheduleEnabled = MutableStateFlow(prefs.getBoolean(PrefKeys.PC_SCHEDULE_ENABLED, false))
    private val _pcScheduleStart = MutableStateFlow(prefs.getString(PrefKeys.PC_SCHEDULE_START, "21:00") ?: "21:00")
    private val _pcScheduleEnd = MutableStateFlow(prefs.getString(PrefKeys.PC_SCHEDULE_END, "07:00") ?: "07:00")
    private val _pcPauseUntil = MutableStateFlow(prefs.getLong(PrefKeys.PC_PAUSE_UNTIL, 0L))

    val printServerEnabled: StateFlow<Boolean> = _printServerEnabled.asStateFlow()
    val fileSharingEnabled: StateFlow<Boolean> = _fileSharingEnabled.asStateFlow()
    val webcamEnabled: StateFlow<Boolean> = _webcamEnabled.asStateFlow()
    val discoveryEnabled: StateFlow<Boolean> = _discoveryEnabled.asStateFlow()

    val printPort: StateFlow<Int> = _printPort.asStateFlow()
    val webdavPort: StateFlow<Int> = _webdavPort.asStateFlow()
    val ftpPort: StateFlow<Int> = _ftpPort.asStateFlow()
    val mjpegPort: StateFlow<Int> = _mjpegPort.asStateFlow()

    val chargeLimit: StateFlow<Int> = _chargeLimit.asStateFlow()
    val thermalThrottleEnabled: StateFlow<Boolean> = _thermalThrottleEnabled.asStateFlow()

    val username: StateFlow<String> = _username.asStateFlow()
    val passwordHash: StateFlow<String> = _passwordHash.asStateFlow()

    val shareRoot: StateFlow<String> = _shareRoot.asStateFlow()
    val cameraFacingBack: StateFlow<Boolean> = _cameraFacingBack.asStateFlow()
    val cameraTorch: StateFlow<Boolean> = _cameraTorch.asStateFlow()
    val mjpegQuality: StateFlow<Int> = _mjpegQuality.asStateFlow()
    val mjpegFps: StateFlow<Int> = _mjpegFps.asStateFlow()

    val backupEnabled: StateFlow<Boolean> = _backupEnabled.asStateFlow()
    val backupIntervalMin: StateFlow<Int> = _backupIntervalMin.asStateFlow()
    val backupLastRun: StateFlow<Long> = _backupLastRun.asStateFlow()
    val driveTreeUri: StateFlow<String> = _driveTreeUri.asStateFlow()
    val printArchive: StateFlow<Boolean> = _printArchive.asStateFlow()
    val adblockEnabled: StateFlow<Boolean> = _adblockEnabled.asStateFlow()
    val adblockPort: StateFlow<Int> = _adblockPort.asStateFlow()
    val backupUseUsb: StateFlow<Boolean> = _backupUseUsb.asStateFlow()
    val backupLocal: StateFlow<Boolean> = _backupLocal.asStateFlow()
    val netWatchEnabled: StateFlow<Boolean> = _netWatchEnabled.asStateFlow()
    val displayKeepOn: StateFlow<Boolean> = _displayKeepOn.asStateFlow()
    val saverShowLogo: StateFlow<Boolean> = _saverShowLogo.asStateFlow()
    val saverShowClock: StateFlow<Boolean> = _saverShowClock.asStateFlow()
    val saverShowStatus: StateFlow<Boolean> = _saverShowStatus.asStateFlow()
    val saverSpeed: StateFlow<Int> = _saverSpeed.asStateFlow()
    val darkMode: StateFlow<Boolean> = _darkMode.asStateFlow()
    val emergencyStopped: StateFlow<Boolean> = _emergencyStopped.asStateFlow()
    val pcEnabled: StateFlow<Boolean> = _pcEnabled.asStateFlow()
    val pcDevices: StateFlow<String> = _pcDevices.asStateFlow()
    val pcBlockAdult: StateFlow<Boolean> = _pcBlockAdult.asStateFlow()
    val pcBlockSocial: StateFlow<Boolean> = _pcBlockSocial.asStateFlow()
    val pcScheduleEnabled: StateFlow<Boolean> = _pcScheduleEnabled.asStateFlow()
    val pcScheduleStart: StateFlow<String> = _pcScheduleStart.asStateFlow()
    val pcScheduleEnd: StateFlow<String> = _pcScheduleEnd.asStateFlow()
    val pcPauseUntil: StateFlow<Long> = _pcPauseUntil.asStateFlow()

    fun setPrintServerEnabled(v: Boolean) { prefs.edit().putBoolean(PrefKeys.PRINT_SERVER_ENABLED, v).apply(); _printServerEnabled.value = v }
    fun setFileSharingEnabled(v: Boolean) { prefs.edit().putBoolean(PrefKeys.FILE_SHARING_ENABLED, v).apply(); _fileSharingEnabled.value = v }
    fun setWebcamEnabled(v: Boolean) { prefs.edit().putBoolean(PrefKeys.WEBCAM_ENABLED, v).apply(); _webcamEnabled.value = v }
    fun setDiscoveryEnabled(v: Boolean) { prefs.edit().putBoolean(PrefKeys.DISCOVERY_ENABLED, v).apply(); _discoveryEnabled.value = v }

    fun setPrintPort(v: Int) { val c = v.coerceIn(1024, 65535); prefs.edit().putInt(PrefKeys.PRINT_PORT, c).apply(); _printPort.value = c }
    fun setWebdavPort(v: Int) { val c = v.coerceIn(1024, 65535); prefs.edit().putInt(PrefKeys.WEBDAV_PORT, c).apply(); _webdavPort.value = c }
    fun setFtpPort(v: Int) { val c = v.coerceIn(1024, 65535); prefs.edit().putInt(PrefKeys.FTP_PORT, c).apply(); _ftpPort.value = c }
    fun setMjpegPort(v: Int) { val c = v.coerceIn(1024, 65535); prefs.edit().putInt(PrefKeys.MJPEG_PORT, c).apply(); _mjpegPort.value = c }

    fun setChargeLimit(v: Int) { val c = v.coerceIn(50, 100); prefs.edit().putInt(PrefKeys.CHARGE_LIMIT, c).apply(); _chargeLimit.value = c }
    fun setThermalThrottleEnabled(v: Boolean) { prefs.edit().putBoolean(PrefKeys.THERMAL_THROTTLE_ENABLED, v).apply(); _thermalThrottleEnabled.value = v }

    fun setUsername(v: String) { val s = v.ifBlank { "homeserver" }; prefs.edit().putString(PrefKeys.USERNAME, s).apply(); _username.value = s }
    fun setPasswordHash(v: String) { prefs.edit().putString(PrefKeys.PASSWORD_HASH, v).apply(); _passwordHash.value = v }
    fun setCredentials(u: String, passPlain: String) {
        setUsername(u)
        setPasswordHash(if (passPlain.isBlank()) "" else sha256(passPlain))
    }

    fun setShareRoot(v: String) { prefs.edit().putString(PrefKeys.SHARE_ROOT, v).apply(); _shareRoot.value = v }
    fun setCameraFacingBack(v: Boolean) { prefs.edit().putBoolean(PrefKeys.CAMERA_FACING_BACK, v).apply(); _cameraFacingBack.value = v }
    fun setCameraTorch(v: Boolean) { prefs.edit().putBoolean(PrefKeys.CAMERA_TORCH, v).apply(); _cameraTorch.value = v }
    fun setMjpegQuality(v: Int) { val c = v.coerceIn(30, 95); prefs.edit().putInt(PrefKeys.MJPEG_QUALITY, c).apply(); _mjpegQuality.value = c }
    fun setMjpegFps(v: Int) { val c = v.coerceIn(5, 30); prefs.edit().putInt(PrefKeys.MJPEG_FPS, c).apply(); _mjpegFps.value = c }

    fun setBackupEnabled(v: Boolean) { prefs.edit().putBoolean(PrefKeys.BACKUP_ENABLED, v).apply(); _backupEnabled.value = v }
    fun setBackupIntervalMin(v: Int) { val c = v.coerceIn(5, 720); prefs.edit().putInt(PrefKeys.BACKUP_INTERVAL_MIN, c).apply(); _backupIntervalMin.value = c }
    fun setBackupLastRun(v: Long) { prefs.edit().putLong(PrefKeys.BACKUP_LAST_RUN, v).apply(); _backupLastRun.value = v }
    fun setDriveTreeUri(v: String) { prefs.edit().putString(PrefKeys.DRIVE_TREE_URI, v).apply(); _driveTreeUri.value = v }
    fun setPrintArchive(v: Boolean) { prefs.edit().putBoolean(PrefKeys.PRINT_ARCHIVE, v).apply(); _printArchive.value = v }
    fun setAdblockEnabled(v: Boolean) { prefs.edit().putBoolean(PrefKeys.ADBLOCK_ENABLED, v).apply(); _adblockEnabled.value = v }
    fun setAdblockPort(v: Int) { val c = v.coerceIn(53, 65535); prefs.edit().putInt(PrefKeys.ADBLOCK_PORT, c).apply(); _adblockPort.value = c }
    fun setBackupUseUsb(v: Boolean) { prefs.edit().putBoolean(PrefKeys.BACKUP_USE_USB, v).apply(); _backupUseUsb.value = v }
    fun setBackupLocal(v: Boolean) { prefs.edit().putBoolean(PrefKeys.BACKUP_LOCAL, v).apply(); _backupLocal.value = v }
    fun setNetWatchEnabled(v: Boolean) { prefs.edit().putBoolean(PrefKeys.NET_WATCH_ENABLED, v).apply(); _netWatchEnabled.value = v }
    fun setDisplayKeepOn(v: Boolean) { prefs.edit().putBoolean(PrefKeys.DISPLAY_KEEP_ON, v).apply(); _displayKeepOn.value = v }
    fun setSaverShowLogo(v: Boolean) { prefs.edit().putBoolean(PrefKeys.SAVER_SHOW_LOGO, v).apply(); _saverShowLogo.value = v }
    fun setSaverShowClock(v: Boolean) { prefs.edit().putBoolean(PrefKeys.SAVER_SHOW_CLOCK, v).apply(); _saverShowClock.value = v }
    fun setSaverShowStatus(v: Boolean) { prefs.edit().putBoolean(PrefKeys.SAVER_SHOW_STATUS, v).apply(); _saverShowStatus.value = v }
    fun setSaverSpeed(v: Int) { val c = v.coerceIn(0, 2); prefs.edit().putInt(PrefKeys.SAVER_SPEED, c).apply(); _saverSpeed.value = c }
    fun setDarkMode(v: Boolean) { prefs.edit().putBoolean(PrefKeys.DARK_MODE, v).apply(); _darkMode.value = v }
    fun setEmergencyStopped(v: Boolean) { prefs.edit().putBoolean(PrefKeys.EMERGENCY_STOPPED, v).apply(); _emergencyStopped.value = v }
    fun setPcEnabled(v: Boolean) { prefs.edit().putBoolean(PrefKeys.PC_ENABLED, v).apply(); _pcEnabled.value = v }
    fun setPcDevices(v: String) { prefs.edit().putString(PrefKeys.PC_DEVICES, v).apply(); _pcDevices.value = v }
    fun setPcBlockAdult(v: Boolean) { prefs.edit().putBoolean(PrefKeys.PC_BLOCK_ADULT, v).apply(); _pcBlockAdult.value = v }
    fun setPcBlockSocial(v: Boolean) { prefs.edit().putBoolean(PrefKeys.PC_BLOCK_SOCIAL, v).apply(); _pcBlockSocial.value = v }
    fun setPcScheduleEnabled(v: Boolean) { prefs.edit().putBoolean(PrefKeys.PC_SCHEDULE_ENABLED, v).apply(); _pcScheduleEnabled.value = v }
    fun setPcScheduleStart(v: String) { prefs.edit().putString(PrefKeys.PC_SCHEDULE_START, v).apply(); _pcScheduleStart.value = v }
    fun setPcScheduleEnd(v: String) { prefs.edit().putString(PrefKeys.PC_SCHEDULE_END, v).apply(); _pcScheduleEnd.value = v }
    fun setPcPauseUntil(v: Long) { prefs.edit().putLong(PrefKeys.PC_PAUSE_UNTIL, v).apply(); _pcPauseUntil.value = v }

    companion object {
        fun sha256(s: String): String = try {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            md.digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        } catch (_: Exception) { s }
    }
}
