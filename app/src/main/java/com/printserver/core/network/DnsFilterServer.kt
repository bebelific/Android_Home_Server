package com.printserver.core.network

import com.printserver.core.common.PrinterLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class DnsFilterServer(
    private val port: () -> Int,
    private val blocklist: () -> Set<String>,
    private val upstream: String = "8.8.8.8",
) {
    val total = AtomicLong()
    val blocked = AtomicLong()
    val lastBlocked = object : AtomicReference<ArrayDeque<String>>(ArrayDeque()) {}
    @Volatile var running = false
        private set

    private val socket = AtomicBoolean(false)
    private var ds: DatagramSocket? = null
    private var scope: CoroutineScope? = null

    fun start() {
        if (running) return
        val p = port()
        val s = DatagramSocket(null)
        s.reuseAddress = true
        s.bind(InetSocketAddress(p))
        ds = s
        socket.set(true)
        running = true
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        repeat(3) {
            scope!!.launch(Dispatchers.IO) { receiveLoop(s) }
        }
        PrinterLog.i(TAG, "DNS filter listening on 0.0.0.0:$p (blocklist=${blocklist().size})")
    }

    fun stop() {
        running = false
        runCatching { ds?.close() }
        ds = null
        scope = null
        PrinterLog.i(TAG, "Stopped")
    }

    private fun receiveLoop(s: DatagramSocket) {
        val buf = ByteArray(1500)
        while (running && !s.isClosed) {
            val packet = DatagramPacket(buf, buf.size)
            try {
                s.receive(packet)
            } catch (_: Exception) {
                if (running) continue else return
            }
            val data = packet.data.copyOf(packet.length)
            val client = packet.socketAddress
            scope!!.launch(Dispatchers.IO) { handle(data, client, s) }
        }
    }

    private fun handle(query: ByteArray, client: SocketAddress, listenSocket: DatagramSocket) {
        total.incrementAndGet()
        if (query.size < 17) return
        val parsed = parseQname(query) ?: return
        val name = parsed.first
        val qEnd = parsed.second
        if (qEnd + 4 > query.size) return
        val qtype = ((query[qEnd].toInt() and 0xFF) shl 8) or (query[qEnd + 1].toInt() and 0xFF)
        if (blocklist().contains(name)) {
            blocked.incrementAndGet()
            noteBlocked(name)
            val resp = if (qtype == 1) blockedAAnswer(query) else nxdomainAnswer(query)
            send(resp, client, listenSocket)
            return
        }
        try {
            val up = DatagramSocket()
            up.soTimeout = 4000
            up.send(DatagramPacket(query, query.size, InetAddress.getByName(upstream), 53))
            val rb = ByteArray(1500)
            val rp = DatagramPacket(rb, rb.size)
            up.receive(rp)
            up.close()
            send(rb.copyOf(rp.length), client, listenSocket)
        } catch (e: Exception) {
            send(nxdomainAnswer(query), client, listenSocket)
        }
    }

    private fun send(data: ByteArray, to: SocketAddress, s: DatagramSocket) {
        try {
            s.send(DatagramPacket(data, data.size, to))
        } catch (_: Exception) {}
    }

    private fun noteBlocked(name: String) {
        val q = lastBlocked.get()
        synchronized(q) {
            q.addFirst(name)
            while (q.size > 25) q.removeLast()
        }
    }

    fun recentBlocked(): List<String> = synchronized(lastBlocked.get()) { lastBlocked.get().toList() }

    companion object {
        private const val TAG = "DnsFilter"

        fun parseQname(query: ByteArray): Pair<String, Int>? {
            var i = 12
            val sb = StringBuilder()
            while (i < query.size) {
                val len = query[i].toInt() and 0xFF
                if (len == 0) { i += 1; break }
                if (i + len >= query.size) return null
                if (sb.isNotEmpty()) sb.append('.')
                sb.append(String(query, i + 1, len, Charsets.US_ASCII).lowercase())
                i += len + 1
            }
            return if (sb.isEmpty()) null else sb.toString() to i
        }

        private fun header(query: ByteArray, rcode: Int, ancount: Int): ByteArray {
            val r = query.copyOf()
            r[2] = 0x81.toByte()
            r[3] = ((rcode and 0x0F) or 0x80).toByte()
            r[6] = ((ancount shr 8) and 0xFF).toByte()
            r[7] = (ancount and 0xFF).toByte()
            r[8] = 0; r[9] = 0; r[10] = 0; r[11] = 0
            return r
        }

        fun blockedAAnswer(query: ByteArray): ByteArray {
            val base = header(query, 0, 1)
            val answer = byteArrayOf(
                0xC0.toByte(), 0x0C.toByte(),
                0, 1, 0, 1,
                0, 0, 1, 44,
                0, 4, 0, 0, 0, 0,
            )
            return base + answer
        }

        fun nxdomainAnswer(query: ByteArray): ByteArray = header(query, 3, 0)
    }
}
