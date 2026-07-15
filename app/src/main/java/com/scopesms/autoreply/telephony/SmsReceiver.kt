package com.scopesms.autoreply.telephony

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.scopesms.autoreply.di.AppContainer
import com.scopesms.autoreply.domain.parser.MpesaParser
import com.scopesms.autoreply.domain.parser.ParseResult
import com.scopesms.autoreply.domain.parser.Rejection
import com.scopesms.autoreply.domain.sim.SimFilter
import com.scopesms.autoreply.domain.sim.SimFilterDecision
import kotlinx.coroutines.launch

/**
 * Entry point for every incoming SMS. Everything this app does starts here.
 *
 * ### Shape of the work
 * `onReceive` runs on the main thread with a hard deadline (~10s with
 * [goAsync], and the system is entitled to kill the process the moment we
 * finish). So this method does the cheap, synchronous rejections inline and
 * hands the rest to a coroutine.
 *
 * Order is deliberate, cheapest and most-privacy-preserving first:
 * 1. Wrong action → return. Free.
 * 2. Not from M-Pesa → return. Free, and rejects spoofed payment texts.
 * 3. Not from a watched SIM → return. **Before parsing** — BUILD-PLAN Phase 2
 *    says "drop immediately", and it means we never even read the body of the
 *    agent's personal messages.
 * 4. Parse. Pure and fast.
 *
 * ### What it does not do
 * No network, no Room write, no template rendering (CLAUDE.md constraint 5).
 * Phase 3 matches the amount against rules, Phase 4 renders, Phase 5b enqueues
 * — see the seam at the end of [handle].
 */
class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        // Everything below is wrapped: this is a system callback on the agent's
        // livelihood path, and an uncaught exception here is a "Scope SMS has
        // stopped" dialog plus a missed payment. BUILD-PLAN Phase 9 requires
        // log-and-skip, never crash. Cheap to do it from the start rather than
        // retrofitting it in Phase 9.
        try {
            val sms = readIntent(intent) ?: return

            if (!MpesaParser.isMpesaSender(sms.sender)) {
                // Not logging the body: it isn't ours to read, and it's someone's
                // private SMS. The address alone is what we'd need to diagnose a
                // wrong sender rule.
                Log.d(TAG, "Ignoring SMS from non-M-Pesa sender '${sms.sender}'.")
                return
            }

            val container = AppContainer.from(context)
            val pendingResult = goAsync()

            container.applicationScope.launch {
                try {
                    handle(container, sms)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed handling an M-Pesa SMS.", e)
                } finally {
                    // Must always run. Leaking a PendingResult wedges the
                    // broadcast until the system times it out and kills us —
                    // taking the rest of a burst with it.
                    pendingResult.finish()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed reading an incoming SMS.", e)
        }
    }

    /**
     * SIM filter, then parse. Runs off the main thread.
     */
    private suspend fun handle(container: AppContainer, sms: IncomingSms) {
        val decision = SimFilter.evaluate(
            selection = container.settings.currentSimSelection(),
            slotIndex = resolveSlot(container, sms),
            // Only consulted when the slot is unresolvable; see SimFilter.
            activeSlots = container.simReader.activeSims().map { it.slotIndex }.toSet(),
        )

        if (decision is SimFilterDecision.Drop) {
            Log.i(TAG, "Dropping M-Pesa SMS: ${decision.reason}.")
            return
        }

        when (val result = MpesaParser.parse(sms.body)) {
            is ParseResult.Parsed -> {
                val payment = result.payment
                // Amount and code only. Never the body, the payer's name or
                // their number — logcat is world-readable to anyone with adb,
                // and CLAUDE.md's privacy note keeps message content in the
                // local Room log and nowhere else.
                Log.i(
                    TAG,
                    "Parsed payment ${payment.transactionCode} of ${payment.amountCents}c " +
                        "on slot ${(decision as SimFilterDecision.Process).slotIndex}.",
                )

                // ── Seam for Phase 3+ ────────────────────────────────────────
                // Next: match payment.amountCents against the in-memory rule
                // cache (Phase 3), pick the matched/unmatched template (Phase 4),
                // check the relevant toggle (Phase 6), and write an OutboundJob
                // row (Phase 5b). Do the enqueue here and return — do NOT call
                // the gateway from this receiver (constraint 5).
                //
                // Dedupe on payment.transactionCode when wiring Phase 5b: some
                // OEMs deliver SMS_RECEIVED more than once, and a double send
                // means the customer is texted twice and the agent pays twice.
            }

            is ParseResult.Rejected -> when (result.reason) {
                // Routine: the agent's own M-Pesa activity. Not worth a warning.
                Rejection.WRONG_TRANSACTION_TYPE ->
                    Log.d(TAG, "Ignoring M-Pesa SMS: ${result.reason}.")

                // Interesting: M-Pesa sent us something shaped like a payment
                // that we couldn't read. Most likely a till-format variant the
                // parser hasn't been shown — see MpesaParser's known-limitation
                // note. Warn so it surfaces rather than dissolving into debug.
                else ->
                    Log.w(TAG, "Unparsed M-Pesa SMS: ${result.reason}.")
            }
        }
    }

    /**
     * Physical slot the message arrived on, or null if it can't be established.
     *
     * Prefers the subscription ID resolved through the platform's live mapping;
     * falls back to a raw slot extra only when there's no subscription to
     * resolve. Null is a legitimate answer — [SimFilter] is built to handle it.
     */
    private fun resolveSlot(container: AppContainer, sms: IncomingSms): Int? {
        if (sms.subscriptionId != null) {
            container.simReader.slotForSubscriptionId(sms.subscriptionId)?.let { return it }
            // A subscription ID that maps to no active SIM: the card was pulled
            // between delivery and now, or the OEM's ID doesn't match
            // SubscriptionManager's. Fall through to the raw slot rather than
            // giving up.
            Log.d(TAG, "Subscription ${sms.subscriptionId} maps to no active SIM.")
        }
        return sms.rawSlotIndex
    }

    /**
     * Pulls the message out of the intent.
     *
     * Not JVM-testable — `SmsMessage` can only be built by the platform — so it
     * stays thin, and the parts that hold decisions ([SubscriptionExtras],
     * [MpesaParser]) live outside it where tests can reach them.
     */
    private fun readIntent(intent: Intent): IncomingSms? {
        // Handles the PDU/format decoding, including the 3GPP2 case, rather
        // than us re-implementing createFromPdu by hand.
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (parts.isNullOrEmpty()) return null

        // A long M-Pesa message arrives as several PDUs and each part holds only
        // its own slice of the text. Concatenating is what makes the balance and
        // cost clauses — and the tail of a long name — visible to the parser.
        val body = parts.joinToString(separator = "") { it.messageBody.orEmpty() }
        if (body.isBlank()) return null

        return IncomingSms(
            sender = parts.first().originatingAddress,
            body = body,
            subscriptionId = SubscriptionExtras.firstValid(SubscriptionExtras.SUBSCRIPTION_KEYS) {
                intent.readInt(it)
            },
            rawSlotIndex = SubscriptionExtras.firstValid(SubscriptionExtras.SLOT_KEYS) {
                intent.readInt(it)
            },
        )
    }

    /**
     * Reads an int extra, or null when it's absent.
     *
     * Deliberately not `getIntExtra(key, 0)`: a default would turn every device
     * that doesn't publish the key into a confident "slot 0", which is a wrong
     * answer dressed as a right one. Some OEMs write the value as a Long or a
     * String, so those are accepted too — a `ClassCastException` here would be
     * a crash in the receiver.
     */
    private fun Intent.readInt(key: String): Int? = when (val value = extras?.get(key)) {
        is Int -> value
        is Long -> value.toInt()
        is String -> value.toIntOrNull()
        else -> null
    }

    private companion object {
        const val TAG = "SmsReceiver"
    }
}

/**
 * An incoming SMS, flattened off the platform types at the boundary.
 *
 * @param subscriptionId null when no extra carried one.
 * @param rawSlotIndex slot straight from an OEM extra; null when absent.
 */
data class IncomingSms(
    val sender: String?,
    val body: String,
    val subscriptionId: Int?,
    val rawSlotIndex: Int?,
)
