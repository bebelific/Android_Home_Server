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

    companion object {
        fun sha256(s: String): String = try {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            md.digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        } catch (_: Exception) { s }
    }
}
