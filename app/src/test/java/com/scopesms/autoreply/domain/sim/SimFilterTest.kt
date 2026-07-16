package com.scopesms.autoreply.domain.sim

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The SIM filter decides whether a payment SMS is the agent's business traffic
 * or their private life. CLAUDE.md constraint 4 calls a mistake here the
 * highest-severity class of bug in the app, so every branch is pinned.
 */
class SimFilterTest {

    private val bothSlots = setOf(0, 1)

    // --- Watching everything -------------------------------------------------

    @Test
    fun `all-sims selection processes any slot`() {
        val decision = SimFilter.evaluate(SimSelection.AllSims, slotIndex = 1, activeSlots = bothSlots)

        assertEquals(SimFilterDecision.Process(1), decision)
    }

    @Test
    fun `all-sims selection processes even when the slot is unknown`() {
        // No slot to check against, so nothing to get wrong.
        val decision = SimFilter.evaluate(SimSelection.AllSims, slotIndex = null, activeSlots = bothSlots)

        assertEquals(SimFilterDecision.Process(null), decision)
    }

    // --- Watching one slot ---------------------------------------------------

    @Test
    fun `watched slot is processed`() {
        val decision = SimFilter.evaluate(SimSelection.slot(0), slotIndex = 0, activeSlots = bothSlots)

        assertEquals(SimFilterDecision.Process(0), decision)
    }

    @Test
    fun `unwatched slot is dropped`() {
        // The core scenario: the agent watches the business SIM in slot 0, and a
        // payment lands on their personal SIM in slot 1. Replying would text the
        // agent's own contact a bundle price list.
        val decision = SimFilter.evaluate(SimSelection.slot(0), slotIndex = 1, activeSlots = bothSlots)

        assertEquals(SimFilterDecision.Drop(SimFilterDecision.Drop.Reason.UNWATCHED_SIM), decision)
    }

    @Test
    fun `multi-slot selection processes each watched slot`() {
        val selection = SimSelection.Slots(setOf(0, 1))

        assertEquals(
            SimFilterDecision.Process(0),
            SimFilter.evaluate(selection, slotIndex = 0, activeSlots = bothSlots),
        )
        assertEquals(
            SimFilterDecision.Process(1),
            SimFilter.evaluate(selection, slotIndex = 1, activeSlots = bothSlots),
        )
    }

    // --- Unresolvable slots (the OEM reality) --------------------------------

    @Test
    fun `unknown slot on a single-SIM device resolves to the only active slot`() {
        // Some OEM builds omit or rename the subscription extra on SMS_RECEIVED.
        // With one SIM in the device there is only one place the message can
        // have come from, so dropping it would throw away a real customer
        // payment for no safety gain.
        val decision = SimFilter.evaluate(SimSelection.slot(0), slotIndex = null, activeSlots = setOf(0))

        assertEquals(SimFilterDecision.Process(0), decision)
    }

    @Test
    fun `unknown slot on a single-SIM device is still dropped if that slot is unwatched`() {
        // Resolving the slot must not become a licence to ignore the selection.
        val decision = SimFilter.evaluate(SimSelection.slot(0), slotIndex = null, activeSlots = setOf(1))

        assertEquals(SimFilterDecision.Drop(SimFilterDecision.Drop.Reason.UNWATCHED_SIM), decision)
    }

    @Test
    fun `unknown slot with two SIMs active is dropped, not guessed`() {
        // The fail-safe. We cannot tell business traffic from personal, and
        // constraint 4 ranks a misdirected reply above a missed one.
        val decision = SimFilter.evaluate(SimSelection.slot(0), slotIndex = null, activeSlots = bothSlots)

        assertEquals(SimFilterDecision.Drop(SimFilterDecision.Drop.Reason.UNRESOLVED_SLOT), decision)
    }

    @Test
    fun `unknown slot with no active SIMs is dropped`() {
        // SIM pulled mid-delivery. Nothing to resolve against.
        val decision = SimFilter.evaluate(SimSelection.slot(0), slotIndex = null, activeSlots = emptySet())

        assertEquals(SimFilterDecision.Drop(SimFilterDecision.Drop.Reason.UNRESOLVED_SLOT), decision)
    }

    // --- Degenerate stored state ---------------------------------------------

    @Test
    fun `empty slot selection drops everything with a distinct reason`() {
        // Not reachable from the UI, but reachable from a hand-edited or
        // half-written settings file. It must be diagnosable, not look like an
        // ordinary unwatched-SIM drop.
        val decision = SimFilter.evaluate(SimSelection.Slots(emptySet()), slotIndex = 0, activeSlots = bothSlots)

        assertEquals(SimFilterDecision.Drop(SimFilterDecision.Drop.Reason.NO_SIM_SELECTED), decision)
    }
}
