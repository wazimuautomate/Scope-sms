package com.tricreta.scopesms.domain.log

import com.tricreta.scopesms.domain.money.KshAmount
import com.tricreta.scopesms.domain.templates.TemplateType

/**
 * How the rules engine classified a logged payment.
 *
 * Mirrors [com.tricreta.scopesms.domain.rules.MatchOutcome]'s arms rather than
 * BUILD-PLAN Phase 8's stated two (`MATCHED|UNMATCHED`).
 *
 * **The third arm ([NO_RULES_CONFIGURED]) is a deliberate addition to the plan**
 * (workflow rule 7 — it is recorded in memory.md). A payment that arrives before
 * the agent has entered any prices is neither matched nor unmatched, and forcing
 * it into `UNMATCHED` would be an outright lie in the one record the agent uses
 * to diagnose the app: it would read as "a customer paid the wrong amount and we
 * replied/stayed silent" when what actually happened is "the app isn't set up
 * yet". Those need different fixes, so they need different rows.
 *
 * **The fourth arm ([OFF_WINDOW]) is the bundle purchase-window feature**: the
 * amount matched a bundle price, but arrived outside that bundle's allowed
 * hours. Distinct from [MATCHED] for the same reason [NO_RULES_CONFIGURED] is
 * distinct from [UNMATCHED] — "confirmed and sent" and "matched but reassured,
 * not confirmed" are different outcomes the agent needs to tell apart at a
 * glance.
 */
enum class MatchType {
    MATCHED,
    UNMATCHED,

    /** Payment arrived with an empty price list. See [ReplyDecision.NoRulesConfigured]. */
    NO_RULES_CONFIGURED,

    /** Matched a bundle price, but outside that bundle's purchase window. */
    OFF_WINDOW,
    ;

    /** The reply flow this classification belongs to, or null for [NO_RULES_CONFIGURED]. */
    val flow: TemplateType?
        get() = when (this) {
            MATCHED -> TemplateType.MATCHED
            UNMATCHED -> TemplateType.UNMATCHED
            NO_RULES_CONFIGURED -> null
            OFF_WINDOW -> TemplateType.OFF_WINDOW
        }
}

/**
 * What happened to the reply for a logged payment.
 *
 * [QUEUED] is not in BUILD-PLAN Phase 8's list (`SENT|SILENT|FAILED`) and is
 * also a deliberate addition. Sending is asynchronous — Phase 5b writes a job
 * row and a worker drains it later — so between the decision and the gateway's
 * answer there is a real, observable state that is none of the other three.
 * Without it the log would have to either lie (claim `SENT` before the gateway
 * agreed) or hide the row until the send resolved, and a reply stuck behind a
 * dead network would then be invisible in the one place the agent looks.
 */
enum class NotifyStatus {

    /** Queued for the gateway; no answer yet. Normal, and briefly true for every send. */
    QUEUED,

    /** The gateway accepted it. [ActivityRecord.gatewayMessageId] is set. */
    SENT,

    /**
     * Deliberately not sent — the flow's toggle is off, or there was no price
     * list. Not a failure: the app did what it was told.
     */
    SILENT,

    /** The gateway refused, or the queue exhausted its retries. [ActivityRecord.failureReason] says why. */
    FAILED,
    ;

    /** Failed sends are money-adjacent; the dashboard makes them urgent (BUILD-PLAN Phase 8). */
    val isFailure: Boolean get() = this == FAILED
}

/**
 * One processed payment, as the agent sees it in the activity log.
 *
 * The domain shape, not the Room row — `data/log/ActivityLogEntity` is stored.
 * Kept separate so `domain/` stays Room-free and JVM-testable, per
 * `domain/README.md` and the convention Phase 3/4 set with `PricingRule`.
 *
 * Holds a [KshAmount] rather than a number, per [KshAmount]'s "convention for
 * other phases" note: the money type is carried across the app, not re-derived.
 */
data class ActivityRecord(
    val id: Long,

    /** When the payment SMS was processed. Epoch millis. */
    val timestamp: Long,

    /**
     * The M-Pesa transaction code, e.g. `TFA1B2C3D4`.
     *
     * The natural key. Unique in the table — see `ActivityLogDao.insert`.
     */
    val transactionCode: String,

    /**
     * The customer's name as M-Pesa reported it, or null when the message didn't
     * carry one. Phase 4's `{name}` handles the absence; the log shows it plainly
     * rather than inventing a placeholder.
     */
    val senderName: String?,

    val senderPhone: String,
    val amount: KshAmount,
    val matchType: MatchType,
    val notifyStatus: NotifyStatus,

    /** The matched bundle's description, when [matchType] is [MatchType.MATCHED]. */
    val bundleDescription: String?,

    /**
     * The exact text sent (or that would have been sent). Null for
     * [NotifyStatus.SILENT] rows, where no body was ever rendered.
     *
     * Stays on-device: CLAUDE.md constraint 7 permits SMS content in the local
     * Room database and nowhere else.
     */
    val replyBody: String?,

    /** The gateway's id for a [NotifyStatus.SENT] reply, for chasing delivery later. */
    val gatewayMessageId: String?,

    /**
     * Why a [NotifyStatus.FAILED] reply failed, in the agent's terms — this is
     * `SendFailure.description` from Phase 5, never a raw exception and never
     * anything containing the API key.
     */
    val failureReason: String?,
)

/**
 * The dashboard's four tiles for one day (BUILD-PLAN Phase 8).
 *
 * Computed by the database rather than by counting a loaded list: the log grows
 * without bound and the dashboard is the first screen drawn on launch.
 */
data class DashboardStats(
    /** Every payment processed today, whatever happened to it. */
    val processed: Int,

    /** Matched payments whose purchase confirmation was sent. */
    val matchedNotified: Int,

    /** Unmatched payments whose price-list reply was sent. */
    val unmatchedReplied: Int,

    /** Sends that failed. Non-zero is urgent — the agent has customers waiting. */
    val failed: Int,
) {

    /** Drives the dashboard's alert treatment. */
    val hasFailures: Boolean get() = failed > 0

    companion object {
        val EMPTY = DashboardStats(processed = 0, matchedNotified = 0, unmatchedReplied = 0, failed = 0)
    }
}
