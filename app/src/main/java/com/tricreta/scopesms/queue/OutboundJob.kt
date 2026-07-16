package com.tricreta.scopesms.queue

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One customer reply, durably recorded before any network call is attempted.
 *
 * Phase 5b. The row *is* the promise: once it exists, the SMS will go out or
 * fail loudly. The receiver writes it and returns in milliseconds; the worker
 * sends it whenever the network allows (`queue/README.md`).
 */
@Entity(
    tableName = "outbound_jobs",
    indices = [
        // The duplicate guard, enforced by SQLite rather than by application
        // code. Some OEMs redeliver SMS_RECEIVED, and under the burst this app
        // is specified for (~10 payments in 1–3s) a read-then-write check would
        // race: two deliveries of the same transaction can both see "no row
        // exists" before either inserts, and the customer gets charged-for
        // double SMS. A unique index makes the second insert a no-op at the
        // storage layer, where the atomicity actually lives.
        Index(value = ["transactionCode"], unique = true),
        // The worker's hot query is "give me the pending jobs, oldest first".
        Index(value = ["status", "createdAt"]),
    ],
)
data class OutboundJob(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /**
     * The M-Pesa transaction code (e.g. `UGFMXB3GR6`) — the natural idempotency
     * key. It comes from Safaricom, is unique per payment, and is the only
     * identifier that survives a redelivered broadcast.
     */
    val transactionCode: String,

    /** Recipient, as parsed. Normalised to the gateway's format at send time. */
    val phone: String,

    /** The fully-rendered template body (Phase 4). Rendering happens before enqueue. */
    val message: String,

    /**
     * The sender ID captured at enqueue time.
     *
     * Stored per-job rather than read at send time so that a job always sends
     * under the ID it was created with, even if the agent edits Settings while
     * jobs are still queued.
     */
    val senderId: String,

    val status: OutboundJobStatus = OutboundJobStatus.PENDING,

    val attemptCount: Int = 0,

    val createdAt: Long,

    /**
     * The last failure's human-readable reason ([com.tricreta.scopesms.network.SendFailure.description]).
     * Surfaced in the activity log (Phase 8) — a `FAILED` job with no reason is
     * exactly the silent drop `queue/README.md` forbids.
     */
    val lastError: String? = null,

    /** The gateway's message id once accepted; the handle for Phase 12 delivery lookups. */
    val gatewayMessageId: String? = null,
)

enum class OutboundJobStatus {
    /** Waiting for a worker. The state a job sits in while the phone has no data. */
    PENDING,

    /**
     * Claimed by a worker.
     *
     * A job stuck here means the process died mid-send — see
     * [OutboundJobStore.releaseStuckJobs].
     */
    SENDING,

    /** The gateway accepted it. Terminal, and the only happy ending. */
    SENT,

    /**
     * Gave up: a terminal failure, or retries exhausted. Terminal, but never
     * silent — `lastError` says why and the agent sees it in the activity log.
     */
    FAILED,
}
