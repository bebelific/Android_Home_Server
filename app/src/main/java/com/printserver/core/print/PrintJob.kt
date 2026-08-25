package com.printserver.core.print

import com.printserver.core.common.ConnectionMeta
import com.printserver.core.common.JobState
import com.printserver.core.common.isTerminal

data class PrintJob(
    val id: Long,
    val clientAddress: String,
    val createdAtMillis: Long,
    val state: JobState = JobState.WAITING,
    val bytesReceived: Long = 0,
    val bytesSent: Long = 0,
    val error: String? = null,
)

interface JobQueue {
    fun begin(meta: ConnectionMeta): PrintJob
    fun update(job: PrintJob): PrintJob
    fun active(): PrintJob?
    fun recent(): List<PrintJob>
}

class InMemoryJobQueue(private val historyLimit: Int = 20) : JobQueue {
    private val lock = Any()
    private var idCounter = 0L
    private var activeJob: PrintJob? = null
    private val history = ArrayDeque<PrintJob>()

    override fun begin(meta: ConnectionMeta): PrintJob = synchronized(lock) {
        idCounter += 1
        PrintJob(id = idCounter, clientAddress = meta.remoteAddress, createdAtMillis = meta.receivedAtMillis)
            .copy(state = JobState.RECEIVING)
            .also { activeJob = it }
    }

    override fun update(job: PrintJob): PrintJob = synchronized(lock) {
        if (activeJob?.id == job.id) {
            if (job.state.isTerminal) {
                activeJob = null
                history.addFirst(job)
                while (history.size > historyLimit) history.removeLast()
            } else {
                activeJob = job
            }
        }
        job
    }

    override fun active(): PrintJob? = synchronized(lock) { activeJob }

    override fun recent(): List<PrintJob> = synchronized(lock) { history.toList() }
}
