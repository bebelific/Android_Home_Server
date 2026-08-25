package com.printserver.core.print

import com.printserver.core.common.ConnectionMeta
import com.printserver.core.common.JobState
import com.printserver.core.common.PrinterLog
import com.printserver.core.usb.UsbPrinterSession
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

class JobPipeline(
    private val queue: JobQueue,
    private val openSession: () -> UsbPrinterSession?,
) {
    private val busy = AtomicBoolean(false)
    val acceptsWork: Boolean get() = !busy.get()

    suspend fun run(source: InputStream, meta: ConnectionMeta) {
        if (!busy.compareAndSet(false, true)) {
            PrinterLog.w(TAG, "Rejected ${meta.remoteAddress}: a job is already active")
            runCatching { source.close() }
            return
        }
        var job = queue.begin(meta)
        var session: UsbPrinterSession? = null
        try {
            session = openSession()
            if (session == null) {
                queue.update(job.copy(state = JobState.FAILED, error = "No usable USB printer (attached + permitted)"))
                PrinterLog.e(TAG, "Job #${job.id} aborted: printer unavailable")
                return
            }
            PrinterLog.i(TAG, "Job #${job.id} -> ${session.description}")
            job = queue.update(job.copy(state = JobState.PRINTING))
            val buf = ByteArray(BUFFER_SIZE)
            var received = 0L
            var sent = 0L
            var lastTick = System.nanoTime()
            while (true) {
                val n = source.read(buf)
                if (n < 0) break
                var off = 0
                while (off < n) {
                    val w = session.write(buf, off, n - off)
                    if (w < 0) throw IOException("USB bulk transfer failed after $sent bytes")
                    off += w
                    sent += w
                }
                received += n
                val now = System.nanoTime()
                if (now - lastTick >= TICK_NS) {
                    lastTick = now
                    job = queue.update(job.copy(bytesReceived = received, bytesSent = sent))
                }
            }
            queue.update(job.copy(state = JobState.COMPLETED, bytesReceived = received, bytesSent = sent))
            PrinterLog.i(TAG, "Job #${job.id} completed ($received in / $sent out)")
        } catch (e: CancellationException) {
            queue.update(job.copy(state = JobState.CANCELLED))
            throw e
        } catch (e: Exception) {
            queue.update(job.copy(state = JobState.FAILED, error = e.message ?: e.javaClass.simpleName))
            PrinterLog.e(TAG, "Job #${job.id} failed: $e")
        } finally {
            session?.close()
            busy.set(false)
        }
    }

    companion object {
        private const val TAG = "JobPipeline"
        private const val BUFFER_SIZE = 32 * 1024
        private const val TICK_NS = 500L * 1_000_000
    }
}
