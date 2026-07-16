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

    /**
     * Claims a job **and burns an attempt in the same statement.**
     *
     * The attempt is counted here, at claim time, rather than when the gateway
     * answers — and that is the whole point. If the send is cancelled mid-flight
     * (WorkManager stopping the worker when connectivity drops, the execution
     * window expiring, process death), the gateway never returns, so nothing
     * downstream counts anything — but this claim already spent one, so the job
     * stays SENDING until `releaseStuckJobs` flips it back to PENDING and it is
     * retried with **one fewer** attempt left, which is what bounds the loop.
     *
     * On a flaky 2G connection that drops just *after* the request reaches the
     * gateway — which network/ScopeSmsGateway calls the normal case here, not the
     * failure case — that loop re-sends the same SMS forever: the customer is
     * texted over and over and the agent pays for every copy. The transactionCode
     * unique index cannot help, because the row already exists; it is the *send*
     * that repeats.
     *
     * Counting at claim time makes the budget bound the damage no matter how the
     * attempt ends.
     */
    @Query(
        """
        UPDATE outbound_jobs
        SET status = :status, attemptCount = attemptCount + 1
        WHERE id = :id
        """,
    )
    suspend fun claimForSending(id: Long, status: OutboundJobStatus = OutboundJobStatus.SENDING)

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
     * Records how an attempt ended. Does **not** increment `attemptCount` —
     * [claimForSending] already did, so that an attempt which never comes back
     * still counts. Incrementing again here would double-charge every failure.
     */
    @Query(
        """
        UPDATE outbound_jobs
        SET status = :status, lastError = :error
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

    override suspend fun markSending(id: Long) = dao.claimForSending(id)

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
