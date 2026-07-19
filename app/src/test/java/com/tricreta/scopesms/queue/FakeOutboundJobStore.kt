package com.tricreta.scopesms.queue

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-memory [OutboundJobStore] for the JVM tests.
 *
 * **The dedupe guarantee is the whole point of this fake.** Room gets atomicity
 * from a unique index plus `OnConflictStrategy.IGNORE`; if this fake used a
 * plain "contains? then put" it would be racy in a way SQLite is not, and the
 * burst test would either flake or — worse — pass while the real guarantee it
 * claims to prove was never tested. Every mutation therefore goes through one
 * mutex, giving the same all-or-nothing insert the unique index provides.
 */
class FakeOutboundJobStore : OutboundJobStore {

    private val mutex = Mutex()
    private val nextId = AtomicLong(1)
    private val jobs = linkedMapOf<Long, OutboundJob>()

    /** Counts every insert *attempt*, including ignored duplicates. */
    var insertAttempts = 0
        private set

    override suspend fun insertIfNew(job: OutboundJob): Long? = mutex.withLock {
        insertAttempts++
        // Stands in for the unique index on transactionCode.
        if (jobs.values.any { it.transactionCode == job.transactionCode }) return@withLock null

        val id = nextId.getAndIncrement()
        jobs[id] = job.copy(id = id)
        id
    }

    override suspend fun pendingJobs(limit: Int): List<OutboundJob> = mutex.withLock {
        jobs.values
            .filter { it.status == OutboundJobStatus.PENDING }
            .sortedBy { it.createdAt }
            .take(limit)
    }

    /**
     * Mirrors [RoomOutboundJobStore]: the attempt is burned **at claim time**, so
     * a send that is cancelled and never returns still counts against the budget.
     * A fake that only set the status would let these tests pass while the real
     * store re-sent the same SMS forever.
     */
    override suspend fun markSending(id: Long) = update(id) {
        it.copy(status = OutboundJobStatus.SENDING, attemptCount = it.attemptCount + 1)
    }

    override suspend fun markSent(id: Long, gatewayMessageId: String) = update(id) {
        it.copy(
            status = OutboundJobStatus.SENT,
            gatewayMessageId = gatewayMessageId,
            lastError = null,
        )
    }

    // Neither of these increments: markSending already did.

    override suspend fun markRetryable(id: Long, error: String) = update(id) {
        it.copy(status = OutboundJobStatus.PENDING, lastError = error)
    }

    override suspend fun markFailed(id: Long, error: String) = update(id) {
        it.copy(status = OutboundJobStatus.FAILED, lastError = error)
    }

    override suspend fun releaseStuckJobs(): Int = mutex.withLock {
        val stuck = jobs.values.filter { it.status == OutboundJobStatus.SENDING }
        stuck.forEach { jobs[it.id] = it.copy(status = OutboundJobStatus.PENDING) }
        stuck.size
    }

    override suspend fun jobByTransactionCode(transactionCode: String): OutboundJob? =
        mutex.withLock { jobs.values.firstOrNull { it.transactionCode == transactionCode } }

    override suspend fun deleteByStatus(status: OutboundJobStatus): Int = mutex.withLock {
        val ids = jobs.values.filter { it.status == status }.map { it.id }
        ids.forEach { jobs.remove(it) }
        ids.size
    }

    suspend fun allJobs(): List<OutboundJob> = mutex.withLock { jobs.values.toList() }

    private suspend fun update(id: Long, transform: (OutboundJob) -> OutboundJob) {
        mutex.withLock { jobs[id]?.let { jobs[id] = transform(it) } }
    }
}
