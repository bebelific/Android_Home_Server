package com.printserver.core.print

import android.content.Context
import com.printserver.core.common.ConnectionMeta
import com.printserver.core.common.PrinterLog
import com.printserver.core.common.PrefKeys
import com.printserver.core.common.PreferencesManager
import com.printserver.core.common.Service
import com.printserver.core.common.ServiceState
import com.printserver.core.network.IngressListener
import com.printserver.core.network.TcpPrintServer
import com.printserver.core.usb.UsbPrinterManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class PrintService(
    private val context: Context,
    private val prefs: PreferencesManager,
    private val usb: UsbPrinterManager,
) : Service {
    override val id = "print_server"
    override val displayName = "Print Server"
    override val defaultPort = TcpPrintServer.DEFAULT_PORT

    private val _state = MutableStateFlow(ServiceState.DISABLED)
    override val state: StateFlow<ServiceState> = _state.asStateFlow()

    private var server: TcpPrintServer? = null
    private var pipeline: JobPipeline? = null
    private var queue: InMemoryJobQueue? = null

    override suspend fun start(context: Context): Result<Unit> {
        _state.value = ServiceState.STARTING
        return try {
            val port = prefs.printPort.value
            queue = InMemoryJobQueue()
            val archive: java.io.File? = if (prefs.printArchive.value) {
                java.io.File(
                    com.printserver.core.files.StorageProvider.resolveRoot(context, prefs.shareRoot.value),
                    "PrintJobs"
                ).apply { mkdirs() }
            } else null
            pipeline = JobPipeline(
                queue!!,
                { usb.findPrinter()?.let { usb.openPrinter(it) } },
                sinkFactory = { id ->
                    if (archive == null) null else runCatching {
                        archive.listFiles()?.sortedBy { it.name }?.let { files ->
                            files.take((files.size - 19).coerceAtLeast(0)).forEach { it.delete() }
                        }
                        val f = java.io.File(archive, "job-%d-%d.prn".format(id, System.currentTimeMillis()))
                        f to java.io.FileOutputStream(f)
                    }.getOrNull()
                },
                onSinkDone = { f ->
                    val drive = prefs.driveTreeUri.value
                    if (f != null && drive.isNotBlank()) {
                        runCatching {
                            com.printserver.core.files.DriveSaf.writeAppend(
                                context, android.net.Uri.parse(drive), f.name,
                                "application/octet-stream", f
                            )
                        }
                    }
                },
            )
            server = TcpPrintServer(port).also { srv ->
                srv.start(object : IngressListener {
                    override suspend fun onStream(stream: java.io.InputStream, meta: ConnectionMeta) {
                        pipeline?.run(stream, meta)
                    }
                })
            }
            PrinterLog.i("Print", "Running on port $port")
            _state.value = ServiceState.RUNNING
            Result.success(Unit)
        } catch (e: Exception) {
            _state.value = ServiceState.ERROR
            PrinterLog.e("Print", "Start failed: ${e.message}")
            Result.failure(e)
        }
    }

    override suspend fun stop(): Result<Unit> {
        _state.value = ServiceState.STOPPING
        return try {
            server?.stop()
            server = null; pipeline = null; queue = null
            _state.value = ServiceState.DISABLED
            Result.success(Unit)
        } catch (e: Exception) {
            _state.value = ServiceState.ERROR
            Result.failure(e)
        }
    }

    override fun isHealthy(): Boolean =
        _state.value == ServiceState.RUNNING && server?.isRunning == true

    fun needsUsbPermission(): Boolean = usb.findPrinter()?.let { !usb.hasPermission(it) } ?: false

    fun requestUsbPermission(callback: (Boolean) -> Unit) {
        val d = usb.findPrinter()
        if (d == null) callback(false) else usb.requestPermission(d, callback)
    }

    fun printerDescription(): String? = usb.findPrinter()?.let { usb.describe(it) }

    fun jobSnapshot(): Pair<com.printserver.core.print.PrintJob?, List<com.printserver.core.print.PrintJob>> =
        (queue?.active()) to (queue?.recent().orEmpty().take(6))

    suspend fun reprintLast(): Result<String> {
        val pipeline = pipeline ?: return Result.failure(IllegalStateException("service not running"))
        if (!pipeline.acceptsWork) return Result.failure(IllegalStateException("printer busy"))
        val dir = java.io.File(
            com.printserver.core.files.StorageProvider.resolveRoot(context, prefs.shareRoot.value),
            "PrintJobs"
        )
        val last = dir.listFiles()?.maxByOrNull { it.name }
            ?: return Result.failure(IllegalStateException("no archived jobs — enable 'Archive print jobs' and print once"))
        usb.findPrinter()?.let { usb.openPrinter(it) }?.close()
            ?: return Result.failure(IllegalStateException("printer not attached/permitted"))
        return runCatching {
            java.io.FileInputStream(last).use { ins ->
                pipeline.run(ins, ConnectionMeta("reprint:${last.name}", System.currentTimeMillis()))
            }
            "Reprinted ${last.name}"
        }
    }
}
