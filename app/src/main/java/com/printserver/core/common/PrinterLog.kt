package com.printserver.core.common

import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

fun interface LogSink {
    fun append(line: String)
}

private const val RING_CAPACITY = 300

object PrinterLog {
    private val sinks = CopyOnWriteArrayList<LogSink>()
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val ring = RingBufferSink(RING_CAPACITY)

    init { sinks.add(ring) }

    fun addSink(sink: LogSink) { sinks.add(sink) }
    fun clearSinks() { sinks.clear(); sinks.add(ring) }

    fun d(tag: String, message: String) = emit("DEBUG", tag, message)
    fun i(tag: String, message: String) = emit("INFO ", tag, message)
    fun w(tag: String, message: String) = emit("WARN ", tag, message)
    fun e(tag: String, message: String) = emit("ERROR", tag, message)

    fun tail(lines: Int): List<String> = ring.snapshot().takeLast(lines)

    private fun emit(level: String, tag: String, message: String) {
        when (level.trim()) {
            "DEBUG" -> Log.d(tag, message)
            "WARN" -> Log.w(tag, message)
            "ERROR" -> Log.e(tag, message)
            else -> Log.i(tag, message)
        }
        val line = "${fmt.format(Date())} $level $tag: $message"
        for (s in sinks) runCatching { s.append(line) }
    }
}

class FileLogSink(private val dir: File) : LogSink {
    private val file = File(dir, "print_server.log")
    private val lock = Any()

    override fun append(line: String) = synchronized(lock) {
        try {
            if (file.exists() && file.length() > MAX_BYTES) {
                File(dir, "print_server.log.old").let { old ->
                    if (old.exists()) old.delete()
                    file.renameTo(old)
                }
            }
            file.appendText(line + "\n")
        } catch (_: Exception) {}
    }

    companion object { private const val MAX_BYTES = 2L * 1024 * 1024 }
}

class RingBufferSink(private val capacity: Int) : LogSink {
    private val deque = ArrayDeque<String>(capacity)
    fun snapshot(): List<String> = synchronized(deque) { deque.toList() }
    override fun append(line: String) = synchronized(deque) {
        if (deque.size >= capacity) deque.removeFirst()
        deque.addLast(line)
    }
}
