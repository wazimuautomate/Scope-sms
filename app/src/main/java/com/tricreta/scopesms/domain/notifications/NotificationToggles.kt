package com.tricreta.scopesms.domain.notifications

/**
 * Which of the two reply flows the agent currently wants sent.
 *
 * Phase 6. The two are independent on purpose — BUILD-PLAN Phase 6 requires all
 * four combinations (unmatched-only, matched-only, both, neither) to be real,
 * reachable states, so this is deliberately **not** a single tri-state enum or a
 * master switch plus a mode.
 *
 * ### Why they are read together
 * This is a snapshot, not two loose booleans, because the decision path reads
 * both while classifying one payment. Reading them independently would let a
 * toggle flip between the two reads and produce a decision that matches neither
 * the old settings nor the new — rare, but the kind of bug that surfaces once a
 * month as "it texted a customer after I turned it off" and is unfalsifiable
 * after the fact. One value, one read, one consistent decision.
 */
data class NotificationToggles(
    /**
     * Reply to payments that match no bundle price.
     *
     * The original pain point: the agent currently phones these customers to
     * explain the price list (CLAUDE.md, "What this app is").
     */
    val unmatchedReplyEnabled: Boolean,

    /**
     * Send a purchase confirmation when a payment matches a bundle price.
     *
     * Higher volume than the unmatched flow, and the reason the toggles are
     * separate at all: a busy day of confirmations under one sender ID is a real
     * deliverability/ban risk with SMS gateways.
     */
    val matchedReplyEnabled: Boolean,
) {

    /** True when the agent has switched both flows off — a valid, supported state. */
    val allDisabled: Boolean get() = !unmatchedReplyEnabled && !matchedReplyEnabled

    companion object {

        /**
         * 🔴 **Unconfirmed with the client — see memory.md open decision 4.**
         *
         * BUILD-PLAN Phase 6 is explicit that the starting state is a product
         * decision for the agent to confirm, "not something to assume silently".
         * These are the plan's own recommendation, and they are here because the
         * code needs *some* value on first launch — not because the question has
         * been answered.
         *
         * The reasoning behind the recommendation: unmatched=ON because it is the
         * problem the app was bought to solve, so an agent who installs it and
         * changes nothing gets what they paid for. matched=OFF because it is the
         * higher-volume flow with the sender-ID ban risk, and the cautious default
         * for an opt-in cost is off.
         *
         * Deliberately safe to ship un-confirmed: on a fresh install the rule list
         * is empty, so every payment classifies as
         * [com.tricreta.scopesms.domain.rules.MatchOutcome.NoRulesConfigured] and
         * nothing sends regardless of these values. The default cannot text a
         * customer before the agent has entered prices.
         *
         * If the agent says otherwise, change these two lines and the four tests
         * in `NotificationTogglesTest` that pin them.
         */
        val DEFAULT = NotificationToggles(
            unmatchedReplyEnabled = true,
            matchedReplyEnabled = false,
        )
    }
}
