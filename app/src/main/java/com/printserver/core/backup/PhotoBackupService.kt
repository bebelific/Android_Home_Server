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
    @Volatile var lastUsbCopied: Int = 0; private set
    @Volatile var totalUsbCopied: Int = 0; private set
    @Volatile var usbTarget: String? = null; private set
    @Volatile var lastError: String? = null; private set

    fun sourceDir(): File {
        val primary = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
        val cam = File(primary, "Camera")
        if (cam.isDirectory) return cam
        if (primary.isDirectory) return primary
        return File(Environment.getExternalStorageDirectory(), "DCIM")
    }

    data class SourceDoc(val uri: android.net.Uri, val name: String, val mime: String, val lastMod: Long, val size: Long)

    fun safSourceDocs(context: Context, treeUri: android.net.Uri): List<SourceDoc> {
        val out = mutableListOf<SourceDoc>()
        runCatching {
            val childrenUri = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(
                treeUri, android.provider.DocumentsContract.getTreeDocumentId(treeUri)
            )
            context.contentResolver.query(
                childrenUri,
                arrayOf(
                    android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE,
                    android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                    android.provider.DocumentsContract.Document.COLUMN_SIZE,
                ),
                null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    val docId = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2) ?: "application/octet-stream"
                    val mod = c.getLong(3)
                    val size = c.getLong(4)
                    val ext = name.substringAfterLast('.', "").lowercase()
                    if (ext in setOf("jpg", "jpeg", "png", "heic", "webp", "mp4", "mov")) {
                        val docUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                        out.add(SourceDoc(docUri, name, mime, mod, size))
                    }
                }
            }
        }
        return out
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
            val localEnabled = prefs.backupLocal.value
            val device = Build.MODEL.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val dest = File(backupRoot(context), "PhotoBackup/$device")
            if (localEnabled) dest.mkdirs()
            val marker = prefs.backupLastRun.value
            val drive: Uri? = prefs.driveTreeUri.value.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }

            data class Item(val name: String, val lastMod: Long, val open: () -> java.io.InputStream, val file: File?)

            val srcUri = prefs.backupSourceUri.value.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
            val items: List<Item> = if (srcUri != null) {
                safSourceDocs(context, srcUri).filter { it.lastMod > marker }.sortedBy { it.lastMod }
                    .map { d -> Item(d.name, d.lastMod, { context.contentResolver.openInputStream(d.uri)!! }, null) }
            } else {
                val src = sourceDir()
                if (!src.isDirectory) { lastError = "source missing: ${src.path}"; return }
                src.listFiles { f -> f.isFile && f.lastModified() > marker && isMedia(f.name) }
                    ?.sortedBy { it.lastModified() }
                    ?.map { f -> Item(f.name, f.lastModified(), { f.inputStream() }, f) }
                    ?: emptyList()
            }
            if (items.isEmpty()) {
                lastRunMs = System.currentTimeMillis()
                lastError = null
                return
            }

            var copied = 0
            var usbCopied = 0
            var okMarker = marker

            val usbVol = if (prefs.backupUseUsb.value) com.printserver.core.files.UsbStorage.firstWritable(context) else null
            usbTarget = usbVol?.path
            if (prefs.backupUseUsb.value && usbVol == null) {
                lastError = "USB target on but no writable drive (plug in OTG; Android 10+ may need All-files access)"
            }
            val usbDest = usbVol?.let { File(File(it.path, "PhotoBackup"), device).apply { mkdirs() } }

            for (item in items) {
                var fileOk = true
                var localRef: File? = null
                if (localEnabled) {
                    val out = File(dest, item.name)
                    if (!out.exists() || out.length() != item.open().use { it.available().toLong() }) {
                        runCatching {
                            item.open().use { ins -> out.outputStream().use { outs -> ins.copyTo(outs) } }
                            copied++
                            localRef = out
                        }.onFailure { lastError = it.message; fileOk = false }
                    } else localRef = out
                }
                if (drive != null) {
                    val payload = localRef ?: run {
                        val tmp = File(context.cacheDir, item.name)
                        runCatching { item.open().use { ins -> tmp.outputStream().use { tmpOut -> ins.copyTo(tmpOut) } } }
                            .onFailure { fileOk = false }
                        tmp
                    }
                    if (fileOk && !DriveSaf.writeAppend(context, drive, item.name, mime(item.name), payload)) {
                        lastError = "drive upload failed: ${item.name}"
                    }
                }
                if (usbDest != null) {
                    val out = File(usbDest, item.name)
                    if (!out.exists() || out.length() != (localRef?.length() ?: -1)) {
                        val payload = localRef
                        if (payload != null) {
                            runCatching { payload.copyTo(out, overwrite = true) }
                                .onSuccess { usbCopied++ }
                                .onFailure { lastError = "usb: ${it.message}"; fileOk = false }
                        } else {
                            runCatching { item.open().use { ins -> out.outputStream().use { outs -> ins.copyTo(outs) } } }
                                .onSuccess { usbCopied++ }
                                .onFailure { lastError = "usb: ${it.message}"; fileOk = false }
                        }
                    }
                }
                if (fileOk) okMarker = item.lastMod
            }

            lastUsbCopied = usbCopied
            totalUsbCopied += usbCopied
            lastCopied = copied
            totalCopied += copied
            prefs.setBackupLastRun(okMarker)
            lastRunMs = System.currentTimeMillis()
            PrinterLog.i(TAG, "Cycle done: $copied new (${items.size} seen) marker->${okMarker}")
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
