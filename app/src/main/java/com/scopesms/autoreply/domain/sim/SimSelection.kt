package com.scopesms.autoreply.domain.sim

/**
 * Which SIM(s) the agent wants Scope SMS to watch.
 *
 * ### Why this identifies SIMs by physical slot, not subscription ID
 *
 * A subscription ID is a per-SIM row ID handed out by the platform, and it is
 * **not stable**: re-seat a SIM, factory reset, or in some OEM cases just
 * reboot, and the same physical card comes back with a different ID. BUILD-PLAN
 * Phase 9 already flags this ("dual-SIM devices can reorder subscription IDs on
 * some OEMs"). Persisting a subscription ID as the agent's choice would mean
 * their setting silently starts pointing at the *other* SIM — their personal
 * one — which is CLAUDE.md constraint 4's worst case: replies going out for
 * their private M-Pesa traffic.
 *
 * The slot index is the physical tray. "The SIM in slot 1" survives all of the
 * above and is also what the agent actually reasons about.
 *
 * The cost: subscription ID is what arrives in the SMS intent, so the receiver
 * has to resolve subId → slot at delivery time (see `SimReader`). That
 * resolution is the price of a setting that doesn't rot.
 */
sealed interface SimSelection {

    /** Watch every SIM in the device. The agent's "Both" option. */
    data object AllSims : SimSelection

    /**
     * Watch only these physical slots. Slot indices are 0-based, as the
     * platform reports them — "SIM 1" in the UI is slot 0.
     *
     * An empty set is representable and means "watch nothing". That is not a
     * state the UI should be able to produce, but it is a state the *stored*
     * value can be in if a previous version wrote it or the file is
     * hand-edited, so [SimFilter] handles it explicitly rather than assuming.
     */
    data class Slots(val slots: Set<Int>) : SimSelection

    companion object {
        /**
         * What a fresh install gets, before onboarding.
         *
         * Deliberately [AllSims] rather than "nothing": an un-configured app
         * that silently ignores every payment looks identical to a broken one,
         * and the agent has no way to tell which. Watching everything is the
         * visible, diagnosable default. It is safe *because* it cannot send
         * anything on its own — there are no pricing rules (Phase 3) and no
         * gateway credentials (Phase 5) on a fresh install, so nothing goes out
         * until the agent has been through setup and picked a SIM anyway.
         */
        val DEFAULT: SimSelection = AllSims

        /** Convenience for the common single-slot case. */
        fun slot(index: Int): SimSelection = Slots(setOf(index))

        /**
         * Serialised form for DataStore. Kept here, next to the model, so the
         * round trip is one file and one test rather than logic smeared into
         * the persistence layer.
         *
         * Format: `ALL`, or `SLOTS:0,1`. Deliberately boring and
         * human-readable — this value is the difference between replying to
         * customers and replying to the agent's private contacts, and being
         * able to eyeball it in a bug report is worth more than compactness.
         */
        fun encode(selection: SimSelection): String = when (selection) {
            is AllSims -> ALL_TOKEN
            is Slots -> SLOTS_PREFIX + selection.slots.sorted().joinToString(",")
        }

        /**
         * Inverse of [encode]. Returns [DEFAULT] for anything unrecognised —
         * null (never written), empty, a token from a future version, or a
         * corrupted file.
         *
         * Falling back rather than throwing is deliberate: this is read on the
         * SMS hot path, and an exception there would take out ingestion
         * entirely, turning a bad *setting* into a total outage. The agent can
         * see and fix a wrong SIM choice in Settings; they cannot fix an app
         * that stopped receiving.
         */
        fun decode(raw: String?): SimSelection {
            if (raw.isNullOrBlank()) return DEFAULT
            if (raw == ALL_TOKEN) return AllSims
            if (!raw.startsWith(SLOTS_PREFIX)) return DEFAULT

            val slots = raw.removePrefix(SLOTS_PREFIX)
                .split(",")
                .mapNotNull { it.trim().toIntOrNull() }
                .filter { it >= 0 }
                .toSet()

            // "SLOTS:" with nothing parseable after it is corruption, not an
            // intentional "watch nothing" — treat it as unrecognised.
            return if (slots.isEmpty()) DEFAULT else Slots(slots)
        }

        private const val ALL_TOKEN = "ALL"
        private const val SLOTS_PREFIX = "SLOTS:"
    }
}
