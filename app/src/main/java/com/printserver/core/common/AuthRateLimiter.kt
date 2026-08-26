package com.printserver.core.common

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

object AuthRateLimiter {
    private const val MAX_FAILURES = 5
    private const val WINDOW_MS = 5 * 60 * 1000L
    private const val BLOCK_MS = 10 * 60 * 1000L

    private data class Entry(val failures: MutableSet<Long> = CopyOnWriteArraySet())

    private val attempts = ConcurrentHashMap<String, Entry>()
    private val blockedUntil = ConcurrentHashMap<String, Long>()

    fun isBlocked(clientIp: String): Boolean {
        val until = blockedUntil[clientIp] ?: return false
        if (System.currentTimeMillis() > until) {
            blockedUntil.remove(clientIp)
            attempts.remove(clientIp)
            return false
        }
        return true
    }

    fun recordFailure(clientIp: String) {
        val now = System.currentTimeMillis()
        val entry = attempts.getOrPut(clientIp) { Entry() }
        entry.failures.add(now)
        entry.failures.removeAll { it < now - WINDOW_MS }
        if (entry.failures.size >= MAX_FAILURES) {
            blockedUntil[clientIp] = now + BLOCK_MS
            PrinterLog.w("AuthLimiter", "IP $clientIp blocked for 10 min after ${entry.failures.size} failures")
        }
    }

    fun recordSuccess(clientIp: String) {
        attempts.remove(clientIp)
        blockedUntil.remove(clientIp)
    }
}
