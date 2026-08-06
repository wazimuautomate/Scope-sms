package com.tricreta.scopesms.queue

import com.tricreta.scopesms.network.GatewayProvider
import com.tricreta.scopesms.network.GatewayRegistry
import com.tricreta.scopesms.network.SendOutcome

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
    /**
     * Resolves a job's captured [GatewayProvider] to the [com.tricreta.scopesms.network.SmsGateway]
     * that actually sends it. A registry, not a single gateway, because a job
     * must always go out through the account it was created under — see
     * [OutboundJob.provider] — and the agent may have switched the *active*
     * provider in Settings while this job was still queued.
     */
    private val gateways: GatewayRegistry,
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
    /**
     * Send-path logging for field diagnosis — logcat in production, no-op in
     * tests. A port rather than a direct `android.util.Log` call so this class
     * stays pure Kotlin and JVM-testable (this module runs unit tests WITHOUT
     * `returnDefaultValues`, so a real `Log` call would throw "not mocked").
     */
    private val log: OutboundLog = OutboundLog.None,
    /** Injectable so tests don't depend on wall-clock time. */
    private val now: () -> Long = System::currentTimeMillis,
    /**
     * Attempts allowed per job. **1 = send exactly once (the default, and the
     * client's directive).** See the send-once guard in [sendOne]: a job that has
     * already spent its one attempt is failed, never re-sent, because a re-send
     * risks a duplicate billed SMS. Kept injectable so a test can prove the guard.
     */
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
        /**
         * A bounded page came back full of rows this drain had already handled,
         * so there is very likely more behind the limit.
         *
         * [drain] loops over freshly-queued rows within a single run, but still
         * reads a bounded page at a time and works through at most
         * [MAX_DRAIN_PAGES] of them. Without this signal a backlog larger than
         * that ceiling — or one held up entirely by retryables at the front of the
         * queue — would strand its tail until the process next started, possibly
         * the next morning, with those customers still waiting on their prices.
         * Reporting it lets the worker come back through WorkManager's backoff.
         */
        val morePending: Boolean = false,
    ) {
        val processed: Int get() = sent + failed + retryable

        /** True when the worker should ask to run again. */
        val shouldReschedule: Boolean get() = retryable > 0 || morePending
    }

    /**
     * Durably records one reply. Fast, idempotent on [transactionCode], no network.
     *
     * @param message the already-rendered template body (Phase 4). Rendering
     *   happens before this call so the queue never depends on a template that
     *   the agent may have edited by the time the job drains.
     * @param provider the gateway this job sends through, captured now rather
     *   than resolved at send time — see [OutboundJob.provider].
     */
    suspend fun enqueue(
        transactionCode: String,
        phone: String,
        message: String,
        senderId: String,
        provider: GatewayProvider,
    ): EnqueueResult {
        val id = store.insertIfNew(
            OutboundJob(
                transactionCode = transactionCode,
                phone = phone,
                message = message,
                senderId = senderId,
                status = OutboundJobStatus.PENDING,
                createdAt = now(),
                provider = provider.name,
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
     * Never throws: every [com.tricreta.scopesms.network.SmsGateway] returns
     * failures as values, and anything unexpected is recorded against the job
     * rather than killing the drain and stranding the jobs behind it.
     */
    suspend fun drain(): DrainSummary {
        // Reclaim anything a previous process death left mid-flight, before
        // reading the pending list — otherwise those jobs are invisible here.
        store.releaseStuckJobs()

        // Rows already handled in *this* drain. A retryable failure re-queues the
        // same row as PENDING, so without this the loop below would re-read and
        // re-send it and never terminate. Tracking ids lets one drain finish rows
        // queued *after* it started — the tail of a burst, whose own drain
        // re-trigger `ExistingWorkPolicy.KEEP` drops while this worker is still
        // running — without ever touching a row twice. That tail was the reported
        // "stays on Sending…": inserted mid-drain, invisible to the old single
        // snapshot, and left for an SMS or app restart that might never come.
        val handled = HashSet<Long>()
        var summary = DrainSummary()

        repeat(MAX_DRAIN_PAGES) {
            val page = store.pendingJobs(limit = PAGE_SIZE)
            val fresh = page.filterNot { it.id in handled }
            if (fresh.isEmpty()) {
                // Nothing new to send. A full page of already-handled rows can
                // still hide more behind the limit (a backlog > PAGE_SIZE); report
                // it so the worker comes back through WorkManager's backoff rather
                // than spin here on the retryables sitting at the front of the queue.
                return summary.copy(morePending = summary.morePending || page.size == PAGE_SIZE)
            }
            for (job in fresh) {
                handled += job.id
                summary = summary + sendOne(job)
            }
        }

        // Hit the page ceiling with fresh rows still arriving. Hand back to
        // WorkManager rather than hold the worker past its execution window.
        return summary.copy(morePending = true)
    }

    /**
     * Sends one already-queued job RIGHT NOW, bypassing the drain and the
     * send-once guard.
     *
     * A deliberate, manual action from the activity log for a message that is
     * stuck or failed — the agent has decided it should go out despite send-once.
     * It marks the job and reports the outcome through the same result sink a
     * drain uses, so the activity-log row updates too. Reuses the job's captured
     * `senderId`, exactly like [sendOne].
     */
    suspend fun forceSend(transactionCode: String): ForceSendResult {
        val job = store.jobByTransactionCode(transactionCode) ?: return ForceSendResult.NoJob

        store.markSending(job.id)
        log.sending(job.transactionCode, job.phone, job.senderId)

        // The job's OWN captured provider, exactly like sendOne — see
        // OutboundJob.provider for why "whichever is active now" would be wrong.
        val gateway = gateways.forProvider(GatewayProvider.fromName(job.provider))
        return when (val outcome = gateway.sendSms(job.phone, job.message, job.senderId)) {
            is SendOutcome.Sent -> {
                log.sent(job.transactionCode, outcome.messageId)
                store.markSent(job.id, outcome.messageId)
                results.onSent(job.transactionCode, outcome.messageId)
                ForceSendResult.Sent
            }

            is SendOutcome.Failed -> {
                val reason = outcome.reason.description
                log.failed(job.transactionCode, reason)
                store.markFailed(job.id, reason)
                results.onFailed(job.transactionCode, reason)
                ForceSendResult.Failed(reason)
            }
        }
    }

    /**
     * Cancels unsent jobs — deletes everything still PENDING or in-flight SENDING,
     * returning how many were removed.
     *
     * The queue half of the agent's "clear pending": a cleared message must not
     * still go out. Terminal (SENT/FAILED) jobs are left alone.
     */
    suspend fun cancelPending(): Int =
        store.deleteByStatus(OutboundJobStatus.PENDING) +
            store.deleteByStatus(OutboundJobStatus.SENDING)

    private suspend fun sendOne(job: OutboundJob): DrainSummary {
        // Send-once guard (2026-07-19, client directive — stop duplicate billing).
        // A job is attempted AT MOST once. The attempt is burned at claim time
        // (OutboundJobDao.claimForSending), so a job arriving here with an attempt
        // already spent is one a previous run claimed and then died on — its SMS
        // may already have gone out and been charged by the gateway. Re-sending it
        // is exactly the duplicate the client is paying for, so we refuse: record
        // it FAILED (reason logged + shown in the activity log) and leave recovery
        // to a deliberate, manual Force-send by the agent.
        if (job.attemptCount >= maxAttempts) {
            log.failed(job.transactionCode, STRANDED_REASON)
            store.markFailed(job.id, STRANDED_REASON)
            results.onFailed(job.transactionCode, STRANDED_REASON)
            return DrainSummary(failed = 1)
        }

        store.markSending(job.id)
        log.sending(job.transactionCode, job.phone, job.senderId)

        // job.senderId AND job.provider, not the current settings: the job must
        // go out under the ID and account it was created with. See
        // OutboundJob.senderId, OutboundJob.provider and SmsGateway.sendSms.
        val gateway = gateways.forProvider(GatewayProvider.fromName(job.provider))
        val outcome = gateway.sendSms(
            phone = job.phone,
            message = job.message,
            senderId = job.senderId,
        )

        return when (outcome) {
            is SendOutcome.Sent -> {
                log.sent(job.transactionCode, outcome.messageId)
                store.markSent(job.id, outcome.messageId)
                results.onSent(job.transactionCode, outcome.messageId)
                DrainSummary(sent = 1)
            }

            is SendOutcome.Failed -> {
                // No automatic retry, on purpose (send-once). Even a
                // "retryable"-typed failure (429, timeout, dropped socket) is
                // recorded terminally: we cannot tell whether the SMS reached the
                // gateway before the failure, so retrying risks a second billed
                // message. The reason is logged and shown in the activity log;
                // Force-send is the manual, deliberate recovery.
                val reason = outcome.reason.description
                log.failed(job.transactionCode, reason)
                store.markFailed(job.id, reason)
                results.onFailed(job.transactionCode, reason)
                DrainSummary(failed = 1)
            }
        }
    }

    private operator fun DrainSummary.plus(other: DrainSummary) = DrainSummary(
        sent = sent + other.sent,
        failed = failed + other.failed,
        retryable = retryable + other.retryable,
        morePending = morePending || other.morePending,
    )

    companion object {
        /**
         * **Send exactly once.** Was 5 (bounded retries with backoff); the client
         * reported those retries re-sending — and re-billing — the same SMS, so as
         * of 2026-07-19 a job gets one attempt and then lands in FAILED with a
         * readable reason. A genuine transient failure is now the agent's call to
         * Force-send, not the queue's to re-attempt. See the guard in [sendOne].
         */
        const val DEFAULT_MAX_ATTEMPTS = 1

        /** The activity-log/queue reason for a job the send-once guard refuses to re-send. */
        const val STRANDED_REASON =
            "Not resent — the app stopped mid-send, so this may already have gone out. " +
                "Force-send it only if the customer never received it."

        /**
         * How many jobs one drain claims. Bounded so a huge backlog can't hold
         * the worker past WorkManager's execution window; the leftovers are
         * reported through [DrainSummary.morePending] rather than forgotten.
         */
        const val PAGE_SIZE = 100

        /**
         * How many [PAGE_SIZE] pages one drain works through before handing back
         * to WorkManager. Bounds the worst case — a pathological stream of inserts
         * arriving faster than they can be sent — so the loop in [drain] can never
         * hold the worker indefinitely. Any realistic backlog is far under
         * [PAGE_SIZE] × [MAX_DRAIN_PAGES] and drains in a single run.
         */
        const val MAX_DRAIN_PAGES = 50
    }
}

/**
 * Where the queue reports a send's final outcome.
 *
 * Under send-once every attempt is terminal, so every send reports here — SENT
 * via [onSent], any failure via [onFailed]. (Historically a retryable failure
 * stayed silent while the job cycled PENDING→SENDING→PENDING; there is no such
 * path now, which is why failures are always visible in the activity log.)
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

/**
 * Send-path logging for field diagnosis — the client asked to "log everything
 * important when messages are being sent" so failures can be understood.
 *
 * A port rather than a direct `android.util.Log` call so [OutboundQueue] stays
 * pure Kotlin and JVM-testable. The production impl (di/AppContainer) writes to
 * logcat; tests use [None].
 *
 * **Never** pass the API key or the full message body through here — logcat is
 * scooped up wholesale by bug reports (see the note on `AppContainer.keepInSync`).
 * The impl masks the phone number; transaction code and gateway reason are safe.
 */
interface OutboundLog {
    /** A send is about to hit the gateway. */
    fun sending(transactionCode: String, phone: String, senderId: String)

    /** The gateway accepted it. [messageId] is its reply id, which may be blank. */
    fun sent(transactionCode: String, messageId: String)

    /** The send failed terminally (send-once: no retry). [reason] is the gateway's reason. */
    fun failed(transactionCode: String, reason: String)

    object None : OutboundLog {
        override fun sending(transactionCode: String, phone: String, senderId: String) = Unit
        override fun sent(transactionCode: String, messageId: String) = Unit
        override fun failed(transactionCode: String, reason: String) = Unit
    }
}

/** Outcome of a manual [OutboundQueue.forceSend]. */
sealed interface ForceSendResult {
    /** The gateway accepted the message. */
    data object Sent : ForceSendResult

    /** The gateway refused it; [reason] is the agent-readable description. */
    data class Failed(val reason: String) : ForceSendResult

    /**
     * No queued job exists for this transaction — a SILENT row, or one logged
     * before it was ever enqueued. The caller decides whether to reconstruct the
     * send from the activity record instead.
     */
    data object NoJob : ForceSendResult
}
