package com.printserver.core.backup

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import com.printserver.core.common.PrinterLog
import com.printserver.core.common.PreferencesManager
import com.printserver.core.common.Service
import com.printserver.core.common.ServiceState
import com.printserver.core.files.DriveSaf
import com.printserver.core.files.StorageProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class PhotoBackupService(
    private val prefs: PreferencesManager,
) : Service {
    override val id = "photo_backup"
    override val displayName = "Photo Backup"
    override val defaultPort = 0

    private val _state = MutableStateFlow(ServiceState.DISABLED)
    override val state: StateFlow<ServiceState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopJob: Job? = null
    private val busy = AtomicBoolean(false)

    @Volatile var lastRunMs: Long = 0; private set
    @Volatile var lastCopied: Int = 0; private set
    @Volatile var totalCopied: Int = 0; private set
    @Volatile var lastError: String? = null; private set

    fun sourceDir(): File {
        val primary = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
        val cam = File(primary, "Camera")
        if (cam.isDirectory) return cam
        if (primary.isDirectory) return primary
        return File(Environment.getExternalStorageDirectory(), "DCIM")
    }

    fun backupRoot(context: Context): File =
        StorageProvider.resolveRoot(context, prefs.shareRoot.value)

    override suspend fun start(context: Context): Result<Unit> {
        _state.value = ServiceState.STARTING
        return try {
            backupRoot(context)
            loopJob?.cancel()
            loopJob = scope.launch {
                while (isActive && _state.value == ServiceState.RUNNING) {
                    runCycle(context)
                    delay(prefs.backupIntervalMin.value * 60_000L)
                }
            }
            _state.value = ServiceState.RUNNING
            PrinterLog.i(TAG, "Running (interval ${prefs.backupIntervalMin.value} min)")
            Result.success(Unit)
        } catch (e: Exception) {
            _state.value = ServiceState.ERROR
            Result.failure(e)
        }
    }

    override suspend fun stop(): Result<Unit> {
        _state.value = ServiceState.STOPPING
        loopJob?.cancel(); loopJob = null
        _state.value = ServiceState.DISABLED
        return Result.success(Unit)
    }

    override fun isHealthy(): Boolean = _state.value == ServiceState.RUNNING

    fun runNow(context: Context) {
        if (!busy.compareAndSet(false, true)) return
        scope.launch {
            try { runCycle(context) } finally { busy.set(false) }
        }
    }

    suspend fun runCycle(context: Context) {
        if (!busy.compareAndSet(false, true)) return
        try {
            val src = sourceDir()
            if (!src.isDirectory) { lastError = "source missing: ${src.path}"; return }
            val device = Build.MODEL.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val dest = File(backupRoot(context), "PhotoBackup/$device")
            dest.mkdirs()
            val marker = prefs.backupLastRun.value
            val drive: Uri? = prefs.driveTreeUri.value.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
            var copied = 0
            val candidates = src.listFiles { f -> f.isFile && f.lastModified() > marker && isMedia(f.name) }
                ?.sortedBy { it.lastModified() } ?: emptyList()
            for (f in candidates) {
                val out = File(dest, f.name)
                if (!out.exists() || out.length() != f.length()) {
                    runCatching { f.copyTo(out, overwrite = true) }
                        .onSuccess {
                            copied++
                            if (drive != null) DriveSaf.writeAppend(context, drive, f.name, mime(f.name), out)
                        }
                        .onFailure { lastError = it.message }
                } else if (drive != null) {
                    DriveSaf.writeAppend(context, drive, f.name, mime(f.name), out)
                }
            }
            lastCopied = copied
            totalCopied += copied
            lastRunMs = System.currentTimeMillis() - 1000
            prefs.setBackupLastRun(lastRunMs)
            lastError = null
            PrinterLog.i(TAG, "Cycle done: $copied new (${candidates.size} seen) -> ${dest.path}")
        } catch (e: Exception) {
            lastError = e.message
            PrinterLog.w(TAG, "cycle failed: ${e.message}")
        } finally {
            busy.set(false)
        }
    }

    private fun isMedia(name: String): Boolean {
        val e = name.substringAfterLast('.', "").lowercase()
        return e in setOf("jpg", "jpeg", "png", "heic", "webp", "mp4", "mov")
    }

    private fun mime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"; "heic" -> "image/heic"; "webp" -> "image/webp"
        "mp4" -> "video/mp4"; "mov" -> "video/quicktime"
        else -> "image/jpeg"
    }

    companion object { private const val TAG = "PhotoBackup" }
}
