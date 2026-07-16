package com.tricreta.scopesms.telephony

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Lists the device's active SIMs.
 *
 * An interface so the SIM picker and the Phase 2 receiver can be tested against
 * a fake — `SubscriptionManager` is a system service that cannot be faked on
 * the JVM.
 */
interface SimReader {

    /**
     * Active SIMs, ordered by slot. Empty when READ_PHONE_STATE is denied, when
     * no SIM is present, or when the platform fails us — see
     * [AndroidSimReader.activeSims] for why those three are not distinguished.
     */
    fun activeSims(): List<SimInfo>

    /**
     * Physical slot for [subscriptionId], or `null` if that subscription isn't
     * currently active.
     *
     * This is the bridge the ingestion path depends on: the SMS intent carries
     * a subscription ID, the agent's setting is stored as a slot, and something
     * has to join the two. See `SimSelection` for why the setting isn't just
     * stored as a subscription ID in the first place.
     */
    fun slotForSubscriptionId(subscriptionId: Int): Int?
}

/**
 * Real implementation, backed by `SubscriptionManager`.
 *
 * Every read here is defensive. This runs on the low-end Transsion/Xiaomi
 * handsets common among Bingwa agents, where telephony APIs are among the most
 * OEM-modified parts of the platform, and a crash in here is a crash in the
 * broadcast receiver — i.e. the agent's customers silently stop getting
 * replies.
 */
class AndroidSimReader(private val context: Context) : SimReader {

    private val subscriptionManager: SubscriptionManager?
        get() = ContextCompat.getSystemService(context, SubscriptionManager::class.java)

    override fun activeSims(): List<SimInfo> {
        // Checked rather than caught: getActiveSubscriptionInfoList() throws
        // SecurityException without READ_PHONE_STATE, and "denied" is a routine
        // state (the agent hasn't finished onboarding), not an exceptional one.
        if (!hasPermission(Manifest.permission.READ_PHONE_STATE)) {
            Log.i(TAG, "SIM list requested without READ_PHONE_STATE; returning empty.")
            return emptyList()
        }

        val manager = subscriptionManager ?: run {
            Log.w(TAG, "SubscriptionManager unavailable; returning empty SIM list.")
            return emptyList()
        }

        return try {
            // Returns null — not an empty list — when no SIM is inserted.
            manager.activeSubscriptionInfoList
                .orEmpty()
                .map { it.toSimInfo() }
                .sortedBy { it.slotIndex }
        } catch (e: SecurityException) {
            // Belt and braces. The permission check above should make this
            // unreachable, but OEM builds have been known to demand extra
            // permissions here, and this method is called from the SMS receiver.
            // Returning empty degrades the SIM picker; throwing would take down
            // ingestion.
            Log.w(TAG, "Denied reading subscriptions despite holding READ_PHONE_STATE.", e)
            emptyList()
        }
    }

    override fun slotForSubscriptionId(subscriptionId: Int): Int? {
        // The platform's own "no subscription" sentinel. Arrives routinely from
        // SMS intents on single-SIM devices, so it's a normal input, not a bug.
        if (subscriptionId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) return null

        return activeSims().firstOrNull { it.subscriptionId == subscriptionId }?.slotIndex
    }

    private fun SubscriptionInfo.toSimInfo(): SimInfo = SimInfo(
        subscriptionId = subscriptionId,
        slotIndex = simSlotIndex,
        carrierName = carrierName?.toString().orEmpty(),
        phoneNumber = readNumber(),
        displayName = displayName?.toString()?.takeIf { it.isNotBlank() },
    )

    /**
     * Best-effort MSISDN read.
     *
     * `getNumber()` is one of the more thoroughly broken corners of the
     * telephony API: it returns empty far more often than not (Kenyan SIMs
     * commonly have no number provisioned on the card), it needs
     * READ_PHONE_NUMBERS from API 33, and it is deprecated from API 33 in
     * favour of `SubscriptionManager.getPhoneNumber(int)`.
     *
     * We deliberately don't branch to the new API. It requires the same
     * permission, has the same failure modes, and this value is only ever a
     * label on a radio button — the added compat path would be more code with
     * more ways to break for a cosmetic gain. If a future phase needs the
     * number for real logic (it shouldn't), revisit this.
     */
    @Suppress("DEPRECATION")
    private fun SubscriptionInfo.readNumber(): String? =
        try {
            number?.takeIf { it.isNotBlank() }
        } catch (e: SecurityException) {
            Log.i(TAG, "SIM number unavailable (READ_PHONE_NUMBERS not granted).", e)
            null
        }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val TAG = "SimReader"
    }
}
