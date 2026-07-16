package com.tricreta.scopesms.queue

/**
 * Persistence for the outbound queue, behind a port.
 *
 * **Why a port and not just the DAO.** The queue's rules — dedupe, retry
 * budgets, terminal-vs-retryable, never dropping a job — are the most important
 * logic in the app, and `queue/README.md` requires them proven by a burst test
 * in CI. Room's generated code needs an Android runtime, so testing against the
 * DAO directly would mean Robolectric, which needs **JDK 21** to run against
 * SDK 36+ while CI provisions JDK 17 (memory.md flags this as a trap waiting to
 * bite). Rather than bump the whole pipeline to satisfy one test, the rules are
 * kept as pure Kotlin over this interface and exercised on the JVM in
 * milliseconds. [RoomOutboundJobStore] is then a thin adapter with no logic to
 * get wrong.
 *
 * Implementations must be safe to call concurrently — the burst case is several
 * SMS landing at once.
 */
interface OutboundJobStore {

    /**
     * Inserts [job] unless its `transactionCode` is already queued.
     *
     * @return the new row id, or `null` if a job for that transaction already
     *   existed and this call was ignored.
     *
     * **This must be atomic.** Two concurrent calls with the same
     * transactionCode must produce exactly one row and one `null` — never two
     * rows. Room gets this from the unique index; a fake must reproduce it, or
     * the burst test passes while production double-sends.
     */
    suspend fun insertIfNew(job: OutboundJob): Long?

    /** Pending jobs, oldest first, for the worker to drain. */
    suspend fun pendingJobs(limit: Int = 100): List<OutboundJob>

    suspend fun markSending(id: Long)

    suspend fun markSent(id: Long, gatewayMessageId: String)

    /** Records a failed attempt and leaves the job PENDING for another try. */
    suspend fun markRetryable(id: Long, error: String)

    /** Terminal. [error] is what the agent reads in the activity log. */
    suspend fun markFailed(id: Long, error: String)

    /**
     * Returns SENDING jobs back to PENDING.
     *
     * Called on worker start. A job is marked SENDING immediately before the
     * HTTP call, so a process death mid-send — routine on the low-end,
     * aggressively-managed devices this ships to — would otherwise strand it in
     * SENDING forever: never sent, never retried, never reported. That is the
     * silent drop `queue/README.md` forbids, and it is invisible without this.
     */
    suspend fun releaseStuckJobs(): Int

    suspend fun jobByTransactionCode(transactionCode: String): OutboundJob?
}
