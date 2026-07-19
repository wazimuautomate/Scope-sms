package com.tricreta.scopesms.queue

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import android.util.Log
import androidx.work.WorkerParameters
import com.tricreta.scopesms.di.appContainer
import java.util.concurrent.TimeUnit

/**
 * Drains the outbound queue over the network. Phase 5b.
 *
 * WorkManager, not a foreground service: CLAUDE.md constraint 6 bars a
 * persistent foreground service for detection, and explicitly names WorkManager
 * as the sanctioned tool for the outbound queue's background network work.
 *
 * The worker holds no state. Everything durable is a row in `outbound_jobs`, so
 * being killed mid-drain costs at most one in-flight attempt, which
 * [OutboundJobStore.releaseStuckJobs] reclaims on the next run.
 */
class SendJobWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // Resolved per-run rather than injected: WorkManager constructs workers
        // reflectively, so the graph has to be reachable from process scope.
        // Phase 5b's QueueGraph placeholder was absorbed into AppContainer during
        // integration, exactly as its own doc invited.
        val summary = applicationContext.appContainer.outboundQueue.drain()

        return when {
            // Either something is worth retrying (a 429, a 500, a dropped
            // connection) or the page came back full and there is more behind it.
            // Result.retry() applies the backoff configured below rather than
            // spinning immediately.
            summary.shouldReschedule -> Result.retry()
            else -> Result.success()
        }
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "scope-sms-outbound-drain"

        /**
         * Asks for a drain.
         *
         * Called after [OutboundQueue.enqueue] from the receiver, and on process
         * start to pick up anything left from a previous run.
         *
         * `KEEP` rather than `REPLACE`: under the ~10-in-1–3s burst this is
         * called ten times in a couple of seconds, and REPLACE would cancel the
         * in-flight drain nine times over — each cancellation stranding a job
         * mid-send. One drain already picks up every pending row, including ones
         * inserted after it started, so the later calls have nothing to add.
         */
        fun enqueueDrain(context: Context) {
            val request = OneTimeWorkRequestBuilder<SendJobWorker>()
                .setConstraints(
                    Constraints.Builder()
                        // The offline case from CLAUDE.md constraint 2: with no
                        // data at arrival, the job stays PENDING and WorkManager
                        // itself runs this the moment connectivity returns. That
                        // is what makes "never dropped" true rather than aspirational.
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    // WorkManager's floor is 10s. Backoff now only paces the
                    // worker coming back for a *backlog* (DrainSummary.morePending);
                    // per-job retries were removed (send-once, DEFAULT_MAX_ATTEMPTS=1),
                    // so a failed send is terminal and never re-billed.
                    MIN_BACKOFF_SECONDS,
                    TimeUnit.SECONDS,
                )
                // The customer is waiting on this SMS, so it should not sit
                // until the next maintenance window. Expedited quota is finite;
                // if it's exhausted the request drops to a normal one rather
                // than being rejected.
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()

            // WorkManager.getInstance throws if its initializer hasn't run. That
            // is normally guaranteed — its InitializationProvider is created
            // before Application.onCreate — but "normally" is doing real work
            // there. Every caller is either Application.onCreate or the SMS
            // receiver, and in both an uncaught throw is far more expensive than
            // a skipped drain: onCreate would be a dead app at launch, and the
            // receiver would abandon a payment it had already decided on. The job
            // row is already durably stored by the time anyone calls this, so the
            // worst case is a reply that waits for the next trigger rather than a
            // reply that is lost.
            try {
                WorkManager.getInstance(context)
                    .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, request)
            } catch (e: IllegalStateException) {
                Log.e(TAG, "WorkManager unavailable; queued replies wait for the next trigger.", e)
            }
        }

        private const val PERIODIC_WORK_NAME = "scope-sms-outbound-drain-periodic"

        /**
         * A safety net that drains on a fixed cadence, independent of any SMS.
         *
         * [enqueueDrain] only fires on a fresh payment or a process start, and
         * under `ExistingWorkPolicy.KEEP` a burst's later re-triggers are dropped
         * while a drain is already in flight. On the stricter background limits of
         * newer One UI a worker can also be killed mid-send, leaving a job SENDING
         * that only the *next* drain's `releaseStuckJobs` reclaims — and if no new
         * SMS arrives, that next drain never comes. That is the "stays on Sending…"
         * the agent reported on the A16/A07/A06 while an older A05 was fine.
         *
         * This periodic request closes the gap: WorkManager runs it roughly every
         * [PERIODIC_INTERVAL_MINUTES] minutes (its floor is 15), so a stranded job
         * is reclaimed and retried within that window at worst, with no user
         * action. It reuses the same [doWork]/[OutboundQueue.drain] and so is
         * idempotent with the one-time path. `KEEP` so re-registering on every
         * start doesn't reset the running schedule.
         *
         * No `setExpedited` here — expedited is a one-time-work concept; a periodic
         * safety net is deferrable by nature. This is not a substitute for the
         * battery-optimization exemption the reliability screen already prompts
         * for: on a doze-restricted app even periodic work is deferred, so both
         * matter.
         */
        fun enqueuePeriodicDrain(context: Context) {
            val request = PeriodicWorkRequestBuilder<SendJobWorker>(
                PERIODIC_INTERVAL_MINUTES,
                TimeUnit.MINUTES,
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    MIN_BACKOFF_SECONDS,
                    TimeUnit.SECONDS,
                )
                .build()

            // Same guard and rationale as enqueueDrain: a skipped schedule is far
            // cheaper than an uncaught throw on a process-start or receiver path.
            try {
                WorkManager.getInstance(context)
                    .enqueueUniquePeriodicWork(
                        PERIODIC_WORK_NAME,
                        ExistingPeriodicWorkPolicy.KEEP,
                        request,
                    )
            } catch (e: IllegalStateException) {
                Log.e(TAG, "WorkManager unavailable; periodic safety-net drain not scheduled.", e)
            }
        }

        private const val TAG = "ScopeSms/SendWorker"

        private const val MIN_BACKOFF_SECONDS = 10L

        /** WorkManager's minimum periodic interval is 15 minutes; match its floor. */
        private const val PERIODIC_INTERVAL_MINUTES = 15L
    }
}
