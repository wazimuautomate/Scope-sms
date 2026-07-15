package com.scopesms.autoreply.queue

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Room access for the outbound queue. Phase 5b.
 *
 * No decisions live here — see [OutboundJobStore] for why the rules sit above
 * this in pure Kotlin.
 */
@Dao
interface OutboundJobDao {

    /**
     * `OnConflictStrategy.IGNORE` is the duplicate guard, and it is doing real
     * work: combined with the unique index on `transactionCode`, a redelivered
     * SMS_RECEIVED inserts nothing and returns -1 instead of queueing a second
     * copy. SQLite resolves the race, so two concurrent broadcasts for one
     * payment cannot both win.
     *
     * @return the new row id, or -1 if the transaction was already queued.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfNew(job: OutboundJob): Long

    @Query(
        """
        SELECT * FROM outbound_jobs
        WHERE status = :status
        ORDER BY createdAt ASC
        LIMIT :limit
        """,
    )
    suspend fun jobsWithStatus(
        status: OutboundJobStatus = OutboundJobStatus.PENDING,
        limit: Int = 100,
    ): List<OutboundJob>

    @Query("SELECT * FROM outbound_jobs WHERE transactionCode = :transactionCode LIMIT 1")
    suspend fun jobByTransactionCode(transactionCode: String): OutboundJob?

    @Query("UPDATE outbound_jobs SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: OutboundJobStatus)

    @Query(
        """
        UPDATE outbound_jobs
        SET status = :status, gatewayMessageId = :gatewayMessageId, lastError = NULL
        WHERE id = :id
        """,
    )
    suspend fun markSent(
        id: Long,
        gatewayMessageId: String,
        status: OutboundJobStatus = OutboundJobStatus.SENT,
    )

    /**
     * Increments in SQL rather than read-modify-write in Kotlin, so a concurrent
     * drain can't lose an attempt and let a job retry past its budget forever.
     */
    @Query(
        """
        UPDATE outbound_jobs
        SET status = :status, attemptCount = attemptCount + 1, lastError = :error
        WHERE id = :id
        """,
    )
    suspend fun recordAttempt(id: Long, error: String, status: OutboundJobStatus)

    /** See [OutboundJobStore.releaseStuckJobs]. */
    @Query(
        """
        UPDATE outbound_jobs
        SET status = :to
        WHERE status = :from
        """,
    )
    suspend fun releaseStuckJobs(
        from: OutboundJobStatus = OutboundJobStatus.SENDING,
        to: OutboundJobStatus = OutboundJobStatus.PENDING,
    ): Int
}

/** Thin adapter from [OutboundJobDao] to the port. Deliberately logic-free. */
class RoomOutboundJobStore(private val dao: OutboundJobDao) : OutboundJobStore {

    override suspend fun insertIfNew(job: OutboundJob): Long? =
        dao.insertIfNew(job).takeIf { it != IGNORED }

    override suspend fun pendingJobs(limit: Int): List<OutboundJob> =
        dao.jobsWithStatus(OutboundJobStatus.PENDING, limit)

    override suspend fun markSending(id: Long) =
        dao.updateStatus(id, OutboundJobStatus.SENDING)

    override suspend fun markSent(id: Long, gatewayMessageId: String) =
        dao.markSent(id, gatewayMessageId)

    override suspend fun markRetryable(id: Long, error: String) =
        dao.recordAttempt(id, error, OutboundJobStatus.PENDING)

    override suspend fun markFailed(id: Long, error: String) =
        dao.recordAttempt(id, error, OutboundJobStatus.FAILED)

    override suspend fun releaseStuckJobs(): Int = dao.releaseStuckJobs()

    override suspend fun jobByTransactionCode(transactionCode: String): OutboundJob? =
        dao.jobByTransactionCode(transactionCode)

    private companion object {
        /** Room's documented return for an ignored @Insert conflict. */
        const val IGNORED = -1L
    }
}
