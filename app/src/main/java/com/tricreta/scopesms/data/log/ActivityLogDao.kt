package com.tricreta.scopesms.data.log

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Reads and writes the activity log. Phase 8.
 *
 * Every read returns a [Flow] so the dashboard and log screens re-render when the
 * SMS receiver writes a row from a background process start — the agent watching
 * the dashboard while a payment lands sees it appear without a refresh.
 */
@Dao
interface ActivityLogDao {

    /**
     * Records a processed payment.
     *
     * [OnConflictStrategy.IGNORE] against the unique `transaction_code` index, so
     * a redelivered `SMS_RECEIVED` is a no-op rather than a second row or a
     * crash. **First write wins** — the first decision is the one that got acted
     * on by the queue, so it is the true one; a later duplicate must not be able
     * to overwrite it.
     *
     * @return the new row id, or -1 if the transaction was already logged.
     *   Phase 5b can use -1 to skip enqueueing a duplicate send.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entry: ActivityLogEntity): Long

    /**
     * Attaches the gateway's verdict to an already-logged payment.
     *
     * Keyed on `transaction_code`, not `id`: the queue worker knows which M-Pesa
     * transaction it just sent for, and making the code the join key means the
     * worker never has to carry a row id it could get wrong.
     */
    @Query(
        """
        UPDATE activity_log
        SET notify_status = :status,
            gateway_message_id = :gatewayMessageId,
            failure_reason = :failureReason
        WHERE transaction_code = :transactionCode
        """,
    )
    suspend fun updateSendResult(
        transactionCode: String,
        status: String,
        gatewayMessageId: String?,
        failureReason: String?,
    ): Int

    /** True when this transaction has already been logged. */
    @Query("SELECT EXISTS(SELECT 1 FROM activity_log WHERE transaction_code = :transactionCode)")
    suspend fun exists(transactionCode: String): Boolean

    // --- Log list -----------------------------------------------------------

    /** Newest first — the agent's last few minutes are what they came to see. */
    @Query("SELECT * FROM activity_log ORDER BY timestamp DESC LIMIT :limit")
    fun recent(limit: Int = DEFAULT_PAGE): Flow<List<ActivityLogEntity>>

    /**
     * The log screen's search/filter (BUILD-PLAN Phase 8: "search/filter by
     * date/status/flow type").
     *
     * Every filter is nullable and means "no constraint" when null, so one query
     * serves every combination of the screen's controls rather than a
     * combinatorial pile of DAO methods.
     *
     * The text search covers name, phone and transaction code — the three things
     * an agent has to hand when a customer says "I paid and got nothing".
     * `:query` is bound, never concatenated, so a name containing a quote is data
     * rather than SQL.
     */
    @Query(
        """
        SELECT * FROM activity_log
        WHERE (:query IS NULL
                OR sender_name LIKE '%' || :query || '%'
                OR sender_phone LIKE '%' || :query || '%'
                OR transaction_code LIKE '%' || :query || '%')
          AND (:matchType IS NULL OR match_type = :matchType)
          AND (:notifyStatus IS NULL OR notify_status = :notifyStatus)
          AND (:since IS NULL OR timestamp >= :since)
          AND (:until IS NULL OR timestamp < :until)
        ORDER BY timestamp DESC
        LIMIT :limit
        """,
    )
    fun search(
        query: String?,
        matchType: String?,
        notifyStatus: String?,
        since: Long?,
        until: Long?,
        limit: Int = DEFAULT_PAGE,
    ): Flow<List<ActivityLogEntity>>

    // --- Dashboard stats ----------------------------------------------------

    /**
     * The four tiles in one query and one pass.
     *
     * Counted in SQL rather than by loading the day's rows and counting in
     * Kotlin: this runs on every dashboard composition, the log grows without
     * bound, and the tiles are four integers — there is no reason to move rows
     * across the JNI boundary to produce them.
     *
     * The day boundary is passed in rather than computed with SQLite's `date()`
     * because "today" is the agent's local day. SQLite would have to be told the
     * zone anyway, and the caller already knows it — see
     * [ActivityLogRepository.statsForToday].
     */
    @Query(
        """
        SELECT
            COUNT(*) AS processed,
            COALESCE(SUM(match_type = 'MATCHED'   AND notify_status = 'SENT'), 0) AS matchedNotified,
            COALESCE(SUM(match_type = 'UNMATCHED' AND notify_status = 'SENT'), 0) AS unmatchedReplied,
            COALESCE(SUM(notify_status = 'FAILED'), 0) AS failed
        FROM activity_log
        WHERE timestamp >= :since AND timestamp < :until
        """,
    )
    fun statsBetween(since: Long, until: Long): Flow<StatsRow>

    companion object {
        /**
         * Caps the rows any one screen can pull.
         *
         * The log is unbounded and this app runs on 1–2GB handsets; an
         * unlimited query would eventually OOM the agent's phone on the screen
         * they open when something is already wrong. 500 is far more than fits on
         * screen and covers a heavy day. If the agent ever needs deeper history
         * than this, the answer is paging, not a bigger number.
         */
        const val DEFAULT_PAGE = 500
    }
}

/** Projection for [ActivityLogDao.statsBetween]. Room maps the column names by hand. */
data class StatsRow(
    val processed: Int,
    val matchedNotified: Int,
    val unmatchedReplied: Int,
    val failed: Int,
)
