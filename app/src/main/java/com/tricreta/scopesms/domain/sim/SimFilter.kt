package com.tricreta.scopesms.domain.sim

/**
 * Decides whether an incoming SMS arrived on a SIM the agent asked us to
 * watch.
 *
 * This is the single highest-severity piece of logic in the app. CLAUDE.md
 * constraint 4 names the failure it prevents: the agent's *personal* SIM also
 * receives M-Pesa messages, and a payment from their landlord must never make
 * the gateway text that person a bundle price list. Getting it wrong is not a
 * cosmetic bug — it leaks the agent's private financial life to their
 * customers' inbox and spends their SMS balance doing it.
 *
 * Pure and synchronous: it takes a snapshot of state and returns a verdict. It
 * reads no settings, touches no database, and calls no Android API, which is
 * both what CLAUDE.md constraint 5 demands of the hot path and what lets every
 * branch below be tested on the JVM.
 */
object SimFilter {

    /**
     * @param selection what the agent chose in Settings.
     * @param slotIndex the physical slot the message arrived on, or `null` if
     *   it could not be resolved (see [SimFilterDecision.Drop.Reason.UNRESOLVED_SLOT]).
     * @param activeSlots slots currently holding a SIM. Used to rescue the
     *   `null` case when it is unambiguous.
     */
    fun evaluate(
        selection: SimSelection,
        slotIndex: Int?,
        activeSlots: Set<Int>,
    ): SimFilterDecision {
        // Cheapest and most common case: the agent watches everything, so the
        // slot doesn't matter and an unresolvable one is harmless.
        if (selection is SimSelection.AllSims) {
            return SimFilterDecision.Process(slotIndex)
        }

        val watched = (selection as SimSelection.Slots).slots
        if (watched.isEmpty()) {
            return SimFilterDecision.Drop(SimFilterDecision.Drop.Reason.NO_SIM_SELECTED)
        }

        // Resolve the slot, rescuing the ambiguous case where we can.
        //
        // A single-SIM device (or one with a single *active* SIM — the agent
        // may pull their personal card out) has only one slot the message could
        // possibly have come from. So an intent with no usable subscription
        // extra is still unambiguous, and dropping it would be throwing away a
        // real customer payment for no safety gain: there is no second SIM to
        // confuse it with. This matters because missing/renamed SMS_RECEIVED
        // extras are exactly the kind of thing the low-end OEM builds this app
        // targets get wrong.
        val effectiveSlot = slotIndex ?: activeSlots.singleOrNull()

        if (effectiveSlot == null) {
            // Genuinely ambiguous: multiple SIMs are in the device, we cannot
            // tell which one this came from, and the agent asked us to watch
            // only some of them. Dropping is the fail-safe. Constraint 4 ranks
            // a reply going to the wrong person above a missed reply, and this
            // is precisely that trade: process it and we might text the agent's
            // private contact; drop it and, at worst, one customer doesn't get
            // an automated price list. The drop is logged, not silent, so a
            // device that does this routinely shows up as a pattern rather than
            // as "the app randomly ignores payments".
            return SimFilterDecision.Drop(SimFilterDecision.Drop.Reason.UNRESOLVED_SLOT)
        }

        return if (effectiveSlot in watched) {
            SimFilterDecision.Process(effectiveSlot)
        } else {
            SimFilterDecision.Drop(SimFilterDecision.Drop.Reason.UNWATCHED_SIM)
        }
    }
}

/** Verdict from [SimFilter.evaluate]. */
sealed interface SimFilterDecision {

    /**
     * The message is from a watched SIM; carry on parsing.
     *
     * @param slotIndex the resolved slot, or `null` when the agent watches all
     *   SIMs and it was never needed. Carried for logging, not for further
     *   decisions.
     */
    data class Process(val slotIndex: Int?) : SimFilterDecision

    /**
     * Ignore this message.
     *
     * Carries a [reason] because "dropped" alone is undiagnosable. If the agent
     * reports "customers aren't getting replies", the difference between
     * [Reason.UNWATCHED_SIM] (working as configured — they picked the wrong
     * slot) and [Reason.UNRESOLVED_SLOT] (this device doesn't report SIM
     * identity the way we expect) is the whole investigation.
     */
    data class Drop(val reason: Reason) : SimFilterDecision {
        enum class Reason {
            /** Arrived on a SIM the agent didn't select. The normal, expected drop. */
            UNWATCHED_SIM,

            /** Multiple SIMs active and the intent didn't say which one. See [SimFilter]. */
            UNRESOLVED_SLOT,

            /** Stored selection watches no slots — corrupt or hand-edited settings. */
            NO_SIM_SELECTED,
        }
    }
}
