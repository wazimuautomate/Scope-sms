package com.scopesms.autoreply.queue

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
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
        val queue = QueueGraph.outboundQueue(applicationContext)
            ?: return Result.success() // Not wired yet; nothing queued to lose.

        val summary = queue.drain()

        return when {
            // Something is still worth retrying — a 429, a 500, a dropped
            // connection. Result.retry() applies the backoff configured below
            // rather than spinning immediately.
            summary.retryable > 0 -> Result.retry()
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
                    // WorkManager's floor is 10s. With 5 attempts that spans
                    // ~10 minutes — see OutboundQueue.DEFAULT_MAX_ATTEMPTS.
                    MIN_BACKOFF_SECONDS,
                    TimeUnit.SECONDS,
                )
                // The customer is waiting on this SMS, so it should not sit
                // until the next maintenance window. Expedited quota is finite;
                // if it's exhausted the request drops to a normal one rather
                // than being rejected.
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }

        private const val MIN_BACKOFF_SECONDS = 10L
    }
}
