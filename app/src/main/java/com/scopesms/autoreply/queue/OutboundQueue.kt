package com.scopesms.autoreply.queue

import com.scopesms.autoreply.network.ScopeSmsGateway
import com.scopesms.autoreply.network.SendOutcome

/**
 * The boundary between deciding to send and actually sending. Phase 5b.
 *
 * [enqueue] is called from the SMS receiver's decide path and must stay fast and
 * I/O-light — one indexed insert, no network (CLAUDE.md constraint 5).
 * [drain] is called from [SendJobWorker] and is where the slow, failure-prone
 * network work happens, off the ingestion path entirely.
 *
 * Pure Kotlin over [OutboundJobStore], so the rules below are provable on the
 * JVM in CI.
 */
class OutboundQueue(
    private val store: OutboundJobStore,
    private val gateway: ScopeSmsGateway,
    /**
     * Where a send's outcome goes once it is known. Wired to the activity log by
     * `di/AppContainer`.
     *
     * A port rather than a direct `ActivityLogRepository` dependency: this class
     * is pure Kotlin over [OutboundJobStore] precisely so its retry rules are
     * provable on the JVM in CI, and taking a Room repository would drag
     * Robolectric into every one of those tests.
     */
    private val results: SendResultListener = SendResultListener.None,
    /** Injectable so tests don't depend on wall-clock time. */
    private val now: () -> Long = System::currentTimeMillis,
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
) {

    sealed interface EnqueueResult {
        data class Queued(val id: Long) : EnqueueResult

        /**
         * A job for this transaction was already queued, so this call did
         * nothing — the correct outcome for a redelivered broadcast, not an
         * error.
         */
        data object Duplicate : EnqueueResult
    }

    data class DrainSummary(
        val sent: Int = 0,
        /** Terminal failures and exhausted retries. */
        val failed: Int = 0,
        /** Jobs left PENDING for a later attempt — the signal to reschedule. */
        val retryable: Int = 0,
    ) {
        val processed: Int get() = sent + failed + retryable
    }

    /**
     * Durably records one reply. Fast, idempotent on [transactionCode], no network.
     *
     * @param message the already-rendered template body (Phase 4). Rendering
     *   happens before this call so the queue never depends on a template that
     *   the agent may have edited by the time the job drains.
     */
    suspend fun enqueue(
        transactionCode: String,
        phone: String,
        message: String,
        senderId: String,
    ): EnqueueResult {
        val id = store.insertIfNew(
            OutboundJob(
                transactionCode = transactionCode,
                phone = phone,
                message = message,
                senderId = senderId,
                status = OutboundJobStatus.PENDING,
                createdAt = now(),
            ),
        )
        return if (id == null) EnqueueResult.Duplicate else EnqueueResult.Queued(id)
    }

    /**
     * Sends every pending job it can, one at a time.
     *
     * **Sequential on purpose.** Concurrency here would buy nothing — the client's
     * worst case is ~10 messages, and the gateway's limit is 100/minute, so
     * firing them in parallel mainly raises the odds of tripping a 429. The
     * burst requirement is about never blocking *ingestion*, which [enqueue]
     * already guarantees by returning before any of this runs.
     *
     * Never throws: [ScopeSmsGateway] returns failures as values, and anything
     * unexpected is recorded against the job rather than killing the drain and
     * stranding the jobs behind it.
     */
    suspend fun drain(): DrainSummary {
        // Reclaim anything a previous process death left mid-flight, before
        // reading the pending list — otherwise those jobs are invisible here.
        store.releaseStuckJobs()

        var summary = DrainSummary()
        for (job in store.pendingJobs()) {
            summary = summary + sendOne(job)
        }
        return summary
    }

    private suspend fun sendOne(job: OutboundJob): DrainSummary {
        store.markSending(job.id)

        val outcome = gateway.sendSms(phone = job.phone, message = job.message)

        return when (outcome) {
            is SendOutcome.Sent -> {
                store.markSent(job.id, outcome.messageId)
                results.onSent(job.transactionCode, outcome.messageId)
                DrainSummary(sent = 1)
            }

            is SendOutcome.Failed -> {
                val reason = outcome.reason
                // attemptCount is the count *before* this attempt, so this
                // attempt is number attemptCount + 1.
                val attemptsUsed = job.attemptCount + 1
                val budgetExhausted = attemptsUsed >= maxAttempts

                when {
                    !reason.retryable -> {
                        // A bad API key or an unregistered sender ID fails
                        // identically forever. Retrying burns the agent's time
                        // and hides the real problem, which they must fix in
                        // Settings (network/README.md).
                        store.markFailed(job.id, reason.description)
                        results.onFailed(job.transactionCode, reason.description)
                        DrainSummary(failed = 1)
                    }

                    budgetExhausted -> {
                        val detail = "${reason.description} (gave up after $attemptsUsed attempts)"
                        store.markFailed(job.id, detail)
                        results.onFailed(job.transactionCode, detail)
                        DrainSummary(failed = 1)
                    }

                    else -> {
                        // Back to PENDING with the reason recorded. WorkManager's
                        // backoff decides when we try again. Deliberately *not*
                        // reported to [results]: the activity log row stays
                        // QUEUED, which is the truth — the reply is still coming.
                        // Flipping it to FAILED and back would have the agent
                        // chasing a customer the app is about to text anyway.
                        store.markRetryable(job.id, reason.description)
                        DrainSummary(retryable = 1)
                    }
                }
            }
        }
    }

    private operator fun DrainSummary.plus(other: DrainSummary) = DrainSummary(
        sent = sent + other.sent,
        failed = failed + other.failed,
        retryable = retryable + other.retryable,
    )

    companion object {
        /**
         * Bounded, per `queue/README.md` ("exhausted retries → FAILED with a
         * readable reason"). Five attempts against WorkManager's exponential
         * backoff spans roughly ten minutes — long enough to ride out a tunnel,
         * a gateway blip or a quick top-up, short enough that the agent learns
         * a message failed while the customer is still in front of them.
         */
        const val DEFAULT_MAX_ATTEMPTS = 5
    }
}

/**
 * Where the queue reports a send's final outcome.
 *
 * Only terminal states are reported. A retryable failure leaves the job PENDING
 * and says nothing — see the `else` arm of [OutboundQueue.sendOne].
 */
interface SendResultListener {

    suspend fun onSent(transactionCode: String, gatewayMessageId: String?)

    /** @param reason `SendFailure.description` — agent-readable, never holds the API key. */
    suspend fun onFailed(transactionCode: String, reason: String)

    /** For tests and for a queue running before the log exists. */
    object None : SendResultListener {
        override suspend fun onSent(transactionCode: String, gatewayMessageId: String?) = Unit
        override suspend fun onFailed(transactionCode: String, reason: String) = Unit
    }
}
