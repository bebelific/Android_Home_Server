package com.printserver.core.network

import com.printserver.core.common.ConnectionMeta
import com.printserver.core.common.PrinterLog
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

interface IngressListener {
    suspend fun onStream(stream: InputStream, meta: ConnectionMeta)
}

class TcpPrintServer(private val port: Int) {

    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var scope: CoroutineScope? = null
    private var listener: IngressListener? = null

    val isRunning: Boolean get() = running.get()

    @Synchronized
    fun start(listener: IngressListener) {
        check(!running.get()) { "Already running" }
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(port))
        serverSocket = ss
        this.listener = listener
        running.set(true)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("tcp-print"))
        this.scope = scope
        PrinterLog.i(TAG, "Listening on 0.0.0.0:$port")
        scope.launch(Dispatchers.IO) {
            while (running.get() && isActive) {
                val client: Socket? = try {
                    ss.accept()
                } catch (e: IOException) {
                    if (running.get()) PrinterLog.w(TAG, "Accept failed: ${e.message}")
                    null
                }
                if (client != null) handle(scope, client)
            }
        }
    }

    private fun handle(scope: CoroutineScope, socket: Socket) {
        val remote = socket.remoteSocketAddress?.toString() ?: "unknown"
        val l = listener
        PrinterLog.i(TAG, "Client connected: $remote")
        scope.launch {
            try {
                socket.soTimeout = IDLE_TIMEOUT_MS
                if (l != null) l.onStream(socket.getInputStream(), ConnectionMeta(remote, System.currentTimeMillis()))
            } catch (e: Exception) {
                PrinterLog.e(TAG, "$remote: ${e.message}")
            } finally {
                runCatching { socket.close() }
                PrinterLog.i(TAG, "Client disconnected: $remote")
            }
        }
    }

    @Synchronized
    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { serverSocket?.close() }
        scope?.cancel()
        scope = null
        serverSocket = null
        listener = null
        PrinterLog.i(TAG, "Stopped")
    }

    companion object {
        const val DEFAULT_PORT = 9100
        private const val TAG = "TcpPrint"
        private const val IDLE_TIMEOUT_MS = 60_000
    }
}
