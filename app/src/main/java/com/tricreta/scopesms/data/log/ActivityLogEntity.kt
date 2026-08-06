package com.tricreta.scopesms.data.log

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.tricreta.scopesms.domain.log.ActivityRecord
import com.tricreta.scopesms.domain.log.MatchType
import com.tricreta.scopesms.domain.log.NotifyStatus
import com.tricreta.scopesms.domain.money.KshAmount

/**
 * The stored shape of one processed payment. Phase 8.
 *
 * Room never sees [KshAmount] or the domain enums — the amount is a raw cents
 * `Long` and the enums are `String`s, converted in this file. That is the
 * convention [KshAmount] asks for ("Room stores the raw cents as a Long column;
 * the conversion happens in data/, so no @TypeConverter is needed") and it keeps
 * `domain/` free of Room, which is what lets the engines be JVM-tested without
 * Robolectric — memory.md flags that Robolectric would force CI onto JDK 21.
 *
 * Enums are stored by `name` rather than ordinal deliberately: an ordinal column
 * silently re-points every historical row if someone inserts an enum constant in
 * the middle, turning the agent's history into fiction with no error anywhere.
 */
@Entity(
    tableName = "activity_log",
    indices = [
        // Unique: some OEMs redeliver SMS_RECEIVED for the same message, so the
        // same M-Pesa transaction can be processed twice. Phase 5b dedupes the
        // *send* on this same code; this dedupes the *log*, so one payment is one
        // row no matter how many times Android hands it to us.
        Index(value = ["transaction_code"], unique = true),
        // The log list and every stat query filter/sort on time.
        Index(value = ["timestamp"]),
    ],
)
data class ActivityLogEntity(

    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "timestamp")
    val timestamp: Long,

    @ColumnInfo(name = "transaction_code")
    val transactionCode: String,

    @ColumnInfo(name = "sender_name")
    val senderName: String?,

    @ColumnInfo(name = "sender_phone")
    val senderPhone: String,

    /** Whole cents. See [KshAmount] for why this is not a Double. */
    @ColumnInfo(name = "amount_cents")
    val amountCents: Long,

    @ColumnInfo(name = "match_type")
    val matchType: String,

    @ColumnInfo(name = "notify_status")
    val notifyStatus: String,

    @ColumnInfo(name = "bundle_description")
    val bundleDescription: String? = null,

    @ColumnInfo(name = "reply_body")
    val replyBody: String? = null,

    @ColumnInfo(name = "gateway_message_id")
    val gatewayMessageId: String? = null,

    @ColumnInfo(name = "failure_reason")
    val failureReason: String? = null,

    /**
     * Which [com.tricreta.scopesms.network.GatewayProvider] this reply actually
     * went out through, by name — null for rows logged before this column
     * existed (or a [NotifyStatus.SILENT] row that never sent). The activity
     * log's "check status" action reads this to know which gateway to ask;
     * null there is treated as BlazeTech, the only gateway that could have
     * sent a pre-existing row. Stored as a raw name, not the enum itself —
     * `domain/` stays free of `network/` (see `domain/README.md`), so
     * resolution happens where the check is actually made (the UI layer).
     */
    @ColumnInfo(name = "provider")
    val provider: String? = null,
) {

    /**
     * Maps to the domain shape.
     *
     * An unrecognised enum string degrades to a safe value rather than throwing.
     * This row was written by an older version of the app; the agent opening
     * their history should see a slightly vague row, not a crash loop on the
     * activity screen with no way back.
     */
    fun toRecord(): ActivityRecord = ActivityRecord(
        id = id,
        timestamp = timestamp,
        transactionCode = transactionCode,
        senderName = senderName,
        senderPhone = senderPhone,
        amount = KshAmount(amountCents),
        matchType = MatchType.entries.firstOrNull { it.name == matchType }
            ?: MatchType.NO_RULES_CONFIGURED,
        notifyStatus = NotifyStatus.entries.firstOrNull { it.name == notifyStatus }
            ?: NotifyStatus.SILENT,
        bundleDescription = bundleDescription,
        replyBody = replyBody,
        gatewayMessageId = gatewayMessageId,
        failureReason = failureReason,
        provider = provider,
    )

    companion object {

        fun fromRecord(record: ActivityRecord): ActivityLogEntity = ActivityLogEntity(
            id = record.id,
            timestamp = record.timestamp,
            transactionCode = record.transactionCode,
            senderName = record.senderName,
            senderPhone = record.senderPhone,
            amountCents = record.amount.cents,
            matchType = record.matchType.name,
            notifyStatus = record.notifyStatus.name,
            bundleDescription = record.bundleDescription,
            replyBody = record.replyBody,
            gatewayMessageId = record.gatewayMessageId,
            failureReason = record.failureReason,
            provider = record.provider,
        )
    }
}
