package com.tricreta.scopesms.telephony

import android.util.Log
import com.tricreta.scopesms.data.log.ActivityLogRepository
import com.tricreta.scopesms.data.settings.SettingsRepository
import com.tricreta.scopesms.domain.PaymentPlan
import com.tricreta.scopesms.domain.PaymentPlanner
import com.tricreta.scopesms.domain.initialNotifyStatus
import com.tricreta.scopesms.domain.log.NotifyStatus
import com.tricreta.scopesms.domain.parser.MpesaPayment
import com.tricreta.scopesms.domain.rules.RuleCache
import com.tricreta.scopesms.domain.templates.TemplateCache
import com.tricreta.scopesms.network.GatewayCredentialsProvider
import com.tricreta.scopesms.queue.OutboundQueue
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * Runs one parsed payment through decide → log → enqueue.
 *
 * This is the seam every parallel session left as a comment: Phase 2 parsed and
 * stopped, Phase 5b could enqueue but had nothing calling it, Phase 6 decided
 * but was never consulted. It exists to be the *only* place those meet.
 *
 * ## Order of operations, and why it is this way
 * Log first, enqueue second. Both are Room writes, so neither is free, but the
 * log row is the agent's record that the payment was seen at all. If the process
 * dies between the two, the agent sees a `QUEUED` reply that never sends — a
 * visible, diagnosable bug. Reversed, they'd see a customer texted with no trace
 * of why, which is the failure CLAUDE.md constraint 9 rates worst.
 *
 * The log insert also carries the duplicate guard: it is unique on
 * `transactionCode`, so a redelivered `SMS_RECEIVED` — routine on the Transsion
 * and Xiaomi handsets this ships to — returns false here and we stop before
 * enqueueing. The queue has its own unique index too; belt and braces, because
 * a double send means the customer is texted twice and the agent pays twice.
 *
 * ## Everything here is off the receiver's main thread
 * Called inside `goAsync()`. The Room writes and the cache awaits are why —
 * CLAUDE.md constraint 5 bars them from the synchronous path, not from the app.
 */
class PaymentPipeline(
    private val ruleCache: RuleCache,
    private val templateCache: TemplateCache,
    private val settings: SettingsRepository,
    private val activityLog: ActivityLogRepository,
    private val queue: OutboundQueue,
    private val credentials: GatewayCredentialsProvider,
    /** Kicks the WorkManager drain. Injected so JVM tests don't need WorkManager. */
    private val requestDrain: () -> Unit,
) {

    /**
     * @return true if this payment was processed, false if it was a duplicate or
     *   could not be decided. Only tests and diagnostics read it.
     */
    suspend fun process(payment: MpesaPayment): Boolean {
        val plan = plan(payment) ?: return false

        // Unique on transactionCode: false means we've already handled this
        // payment on an earlier delivery of the same broadcast.
        val isNew = activityLog.record(
            transactionCode = payment.transactionCode,
            senderName = payment.senderName,
            senderPhone = payment.senderPhone,
            amount = payment.amount,
            matchType = plan.matchType,
            notifyStatus = plan.initialNotifyStatus,
            bundleDescription = plan.bundleDescription,
            replyBody = (plan as? PaymentPlan.Reply)?.body,
        )
        if (!isNew) {
            Log.d(TAG, "Already handled ${payment.transactionCode}; ignoring redelivery.")
            return false
        }

        if (plan !is PaymentPlan.Reply) return true

        // Read at enqueue time, not send time: a job must go out under the
        // sender ID it was created with even if the agent edits Settings while
        // it's still queued (see OutboundJob.senderId).
        val senderId = credentials.credentials()?.senderId
        if (senderId == null) {
            // Not retryable and not silent. The agent finished setup enough to
            // turn a toggle on but never entered gateway credentials — the app
            // must say so, not sit on a queue that can never drain.
            Log.w(TAG, "No gateway credentials; cannot send reply for ${payment.transactionCode}.")
            activityLog.markFailed(
                payment.transactionCode,
                "SMS gateway is not set up — add your API key and sender ID in Settings",
            )
            return true
        }

        when (queue.enqueue(payment.transactionCode, payment.senderPhone, plan.body, senderId)) {
            is OutboundQueue.EnqueueResult.Queued -> requestDrain()

            // The log said this was new but the queue disagrees. Reachable only
            // if a previous run inserted the job and died before its log row
            // landed. The job is already queued and will drain — nothing to do.
            OutboundQueue.EnqueueResult.Duplicate ->
                Log.d(TAG, "Job already queued for ${payment.transactionCode}.")
        }
        return true
    }

    /**
     * Awaits the caches and decides. Null if the caches never loaded.
     *
     * The timeout exists because `awaitLoaded()` never resumes if Room cannot be
     * read at all (see `SnapshotCache`), and a receiver that hangs forever holds
     * its `goAsync()` slot until the system kills the process — taking the rest
     * of a burst with it. Expiry is logged loudly and drops the message, which is
     * the honest outcome: with no rules loaded we cannot tell a matched payment
     * from an unmatched one, and guessing would text a paying customer a price
     * list they didn't need.
     */
    private suspend fun plan(payment: MpesaPayment): PaymentPlan? = try {
        withTimeout(CACHE_TIMEOUT_MS) {
            PaymentPlanner.plan(
                payment = payment,
                rules = ruleCache.awaitLoaded(),
                templates = templateCache.awaitLoaded(),
                toggles = settings.currentNotificationToggles(),
            )
        }
    } catch (e: TimeoutCancellationException) {
        Log.e(
            TAG,
            "Rules/templates did not load within ${CACHE_TIMEOUT_MS}ms; " +
                "dropped payment ${payment.transactionCode}. The database may be unreadable.",
        )
        null
    }

    private companion object {
        const val TAG = "ScopeSms/Pipeline"

        /**
         * Generous on purpose. This is a cold-start read of two small tables on a
         * cheap handset under whatever load woke it; the receiver's own budget is
         * ~10s with `goAsync()`, so five leaves room to log and finish cleanly.
         * It is a deadlock guard, not a performance target — the warm path
         * resolves in microseconds because the cache is already loaded.
         */
        const val CACHE_TIMEOUT_MS = 5_000L
    }
}
