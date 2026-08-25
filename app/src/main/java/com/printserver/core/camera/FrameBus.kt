package com.printserver.core.camera

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList

class FrameBus(private val maxSubscribers: Int = 8) {

    class Subscription {
        private val queue = ArrayBlockingQueue<ByteArray>(2)
        fun offer(frame: ByteArray) { queue.poll(); queue.offer(frame) }
        fun take(timeoutMs: Long): ByteArray? = queue.poll(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    private val subscribers = CopyOnWriteArrayList<Subscription>()

    @Volatile var latest: ByteArray? = null
        private set

    @Volatile var frameCount: Long = 0
        private set

    val subscriberCount: Int get() = subscribers.size
    val canSubscribe: Boolean get() = subscribers.size < maxSubscribers

    fun publish(frame: ByteArray) {
        latest = frame
        frameCount++
        for (s in subscribers) s.offer(frame)
    }

    fun subscribe(): Subscription? {
        if (!canSubscribe) return null
        val s = Subscription()
        subscribers.add(s)
        return s
    }

    fun unsubscribe(s: Subscription) { subscribers.remove(s) }
}
