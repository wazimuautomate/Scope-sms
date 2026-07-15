package com.scopesms.autoreply.data.log

import com.scopesms.autoreply.domain.log.ActivityRecord
import com.scopesms.autoreply.domain.log.DashboardStats
import com.scopesms.autoreply.domain.log.MatchType
import com.scopesms.autoreply.domain.log.NotifyStatus
import com.scopesms.autoreply.domain.money.KshAmount
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The activity log, in domain terms. Phase 8.
 *
 * Owns the Room↔domain mapping and the definition of "today", so that neither
 * the DAO nor the UI has to know about the other's types.
 */
class ActivityLogRepository(
    private val dao: ActivityLogDao,
    /**
     * Injected so the day-boundary logic is testable without waiting for
     * midnight. Defaults to the system zone — "today" means the agent's local
     * day, which is the only definition that matches what they see on their own
     * clock. Nairobi is UTC+3, so a UTC-based "today" would roll the dashboard
     * over at 3am local and show a busy morning's work as yesterday's.
     */
    private val clock: Clock = Clock.systemDefaultZone(),
) {

    /** Newest first, capped. Drives the activity log screen's default view. */
    val recent: Flow<List<ActivityRecord>> =
        dao.recent().map { rows -> rows.map(ActivityLogEntity::toRecord) }

    /**
     * Today's four dashboard tiles, recomputed whenever the log changes.
     *
     * The boundary is captured when this is called. The dashboard is a
     * foreground screen the agent looks at for seconds or minutes, so a session
     * running across midnight keeping yesterday's boundary is acceptable and
     * self-correcting on the next open — the alternative, a timer that
     * invalidates the query at midnight, is machinery for a case that doesn't
     * hurt anyone.
     */
    fun statsForToday(): Flow<DashboardStats> {
        val zone = clock.zone
        val startOfDay = LocalDate.now(clock).atStartOfDay(zone).toInstant().toEpochMilli()
        val startOfTomorrow = LocalDate.now(clock).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

        return dao.statsBetween(since = startOfDay, until = startOfTomorrow).map { row ->
            DashboardStats(
                processed = row.processed,
                matchedNotified = row.matchedNotified,
                unmatchedReplied = row.unmatchedReplied,
                failed = row.failed,
            )
        }
    }

    /** The log screen's filter. Null means "no constraint" for every argument. */
    fun search(
        query: String? = null,
        matchType: MatchType? = null,
        notifyStatus: NotifyStatus? = null,
        since: Long? = null,
        until: Long? = null,
    ): Flow<List<ActivityRecord>> =
        dao.search(
            // Blank is the search box's empty state, not a search for "".
            query = query?.takeIf { it.isNotBlank() },
            matchType = matchType?.name,
            notifyStatus = notifyStatus?.name,
            since = since,
            until = until,
        ).map { rows -> rows.map(ActivityLogEntity::toRecord) }

    /**
     * Records a processed payment.
     *
     * Returns false if this transaction was already logged — a redelivered
     * `SMS_RECEIVED`. Phase 5b should treat false as "already handled, don't
     * enqueue", which is the log-side half of the duplicate guard.
     */
    suspend fun record(
        transactionCode: String,
        senderName: String?,
        senderPhone: String,
        amount: KshAmount,
        matchType: MatchType,
        notifyStatus: NotifyStatus,
        bundleDescription: String? = null,
        replyBody: String? = null,
        timestamp: Long = clock.millis(),
    ): Boolean {
        val rowId = dao.insert(
            ActivityLogEntity(
                timestamp = timestamp,
                transactionCode = transactionCode,
                senderName = senderName,
                senderPhone = senderPhone,
                amountCents = amount.cents,
                matchType = matchType.name,
                notifyStatus = notifyStatus.name,
                bundleDescription = bundleDescription,
                replyBody = replyBody,
            ),
        )
        return rowId != INSERT_IGNORED
    }

    /** Marks a queued reply as accepted by the gateway. */
    suspend fun markSent(transactionCode: String, gatewayMessageId: String?) {
        dao.updateSendResult(
            transactionCode = transactionCode,
            status = NotifyStatus.SENT.name,
            gatewayMessageId = gatewayMessageId,
            failureReason = null,
        )
    }

    /**
     * Marks a queued reply as failed.
     *
     * @param reason `SendFailure.description` from Phase 5 — the agent-readable
     *   one. Never a raw exception, and never anything holding the API key.
     */
    suspend fun markFailed(transactionCode: String, reason: String) {
        dao.updateSendResult(
            transactionCode = transactionCode,
            status = NotifyStatus.FAILED.name,
            gatewayMessageId = null,
            failureReason = reason,
        )
    }

    private companion object {
        /** What Room's `@Insert(onConflict = IGNORE)` returns when it skipped the row. */
        const val INSERT_IGNORED = -1L
    }
}
