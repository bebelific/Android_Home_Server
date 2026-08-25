package com.printserver.core.files

import android.content.Context
import com.printserver.core.common.PrinterLog
import com.printserver.core.common.PreferencesManager
import com.printserver.core.common.Service
import com.printserver.core.common.ServiceState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

class FileService(
    private val prefs: PreferencesManager,
) : Service {
    override val id = "file_sharing"
    override val displayName = "File Sharing"
    override val defaultPort = 8080

    private val _state = MutableStateFlow(ServiceState.DISABLED)
    override val state: StateFlow<ServiceState> = _state.asStateFlow()

    @Volatile private var transferRateLimit = 1.0f

    private lateinit var root: File
    private var webdav: WebDavServer? = null
    private var ftp: FtpShareServer? = null

    fun currentRoot(): File = root

    override suspend fun start(context: Context): Result<Unit> {
        _state.value = ServiceState.STARTING
        return try {
            root = StorageProvider.resolveRoot(context, prefs.shareRoot.value)
            if (prefs.shareRoot.value != root.absolutePath) prefs.setShareRoot(root.absolutePath)
            webdav = WebDavServer({ prefs.webdavPort.value }, { root }, { prefs.username.value }, { prefs.passwordHash.value })
            ftp = FtpShareServer(
                { prefs.ftpPort.value }, { root },
                { prefs.username.value }, { prefs.passwordHash.value }
            )
            webdav?.start()
            runCatching { ftp?.start() }.onFailure { PrinterLog.w(TAG, "FTP start failed: ${it.message}") }
            _state.value = ServiceState.RUNNING
            Result.success(Unit)
        } catch (e: Exception) {
            _state.value = ServiceState.ERROR
            PrinterLog.e(TAG, "Start failed: ${e.message}")
            Result.failure(e)
        }
    }

    override suspend fun stop(): Result<Unit> {
        _state.value = ServiceState.STOPPING
        return try {
            ftp?.stop(); ftp = null
            webdav?.stop(); webdav = null
            _state.value = ServiceState.DISABLED
            Result.success(Unit)
        } catch (e: Exception) {
            _state.value = ServiceState.ERROR
            Result.failure(e)
        }
    }

    override fun isHealthy(): Boolean =
        _state.value == ServiceState.RUNNING && webdav?.isRunning == true

    fun throttleTransfers(limit: Float) {
        transferRateLimit = limit.coerceIn(0f, 1f)
        PrinterLog.i(TAG, "Transfers throttled to ${(transferRateLimit * 100).toInt()}%")
    }

    companion object { private const val TAG = "FileShare" }
}
