package com.tricreta.scopesms.domain.sim

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Encode/decode is what the agent's SIM choice survives a reboot on — Phase 1's
 * exit criterion says the filter must persist across app restarts and reboot,
 * and this is the logic that decides whether it does.
 */
class SimSelectionTest {

    @Test
    fun `all-sims round trips`() {
        assertEquals(SimSelection.AllSims, roundTrip(SimSelection.AllSims))
    }

    @Test
    fun `single slot round trips`() {
        assertEquals(SimSelection.slot(1), roundTrip(SimSelection.slot(1)))
    }

    @Test
    fun `multiple slots round trip`() {
        val selection = SimSelection.Slots(setOf(0, 1))

        assertEquals(selection, roundTrip(selection))
    }

    @Test
    fun `encoding is stable regardless of set order`() {
        // Set iteration order isn't guaranteed. If the encoding weren't sorted,
        // the same selection could write two different strings, and DataStore
        // would treat them as changes — waking every collector, including the
        // hot-path cache, for a no-op.
        assertEquals(
            SimSelection.encode(SimSelection.Slots(setOf(0, 1))),
            SimSelection.encode(SimSelection.Slots(setOf(1, 0))),
        )
    }

    @Test
    fun `encoded form is the documented one`() {
        // Pinned because it's a persisted format: changing it silently resets
        // every existing install's SIM choice back to the default.
        assertEquals("ALL", SimSelection.encode(SimSelection.AllSims))
        assertEquals("SLOTS:0,1", SimSelection.encode(SimSelection.Slots(setOf(1, 0))))
    }

    // --- Degraded input: every branch falls back rather than throwing ---------
    //
    // decode() runs on the SMS hot path. An exception there takes out ingestion
    // entirely — a bad setting becomes a total outage.

    @Test
    fun `unwritten setting decodes to the default`() {
        assertEquals(SimSelection.DEFAULT, SimSelection.decode(null))
    }

    @Test
    fun `blank setting decodes to the default`() {
        assertEquals(SimSelection.DEFAULT, SimSelection.decode(""))
        assertEquals(SimSelection.DEFAULT, SimSelection.decode("   "))
    }

    @Test
    fun `unrecognised token decodes to the default`() {
        // e.g. a value written by a future version the agent downgraded from.
        assertEquals(SimSelection.DEFAULT, SimSelection.decode("SOMETHING_ELSE"))
    }

    @Test
    fun `slots token with nothing parseable decodes to the default`() {
        assertEquals(SimSelection.DEFAULT, SimSelection.decode("SLOTS:"))
        assertEquals(SimSelection.DEFAULT, SimSelection.decode("SLOTS:abc"))
    }

    @Test
    fun `garbage entries are dropped but valid ones survive`() {
        assertEquals(SimSelection.slot(1), SimSelection.decode("SLOTS:abc,1"))
    }

    @Test
    fun `negative slots are rejected`() {
        // -1 is the platform's INVALID_SIM_SLOT_INDEX. Stored, it would match no
        // real SIM and silently drop every message.
        assertEquals(SimSelection.DEFAULT, SimSelection.decode("SLOTS:-1"))
        assertEquals(SimSelection.slot(0), SimSelection.decode("SLOTS:-1,0"))
    }

    @Test
    fun `whitespace around slots is tolerated`() {
        assertEquals(SimSelection.Slots(setOf(0, 1)), SimSelection.decode("SLOTS: 0 , 1 "))
    }

    @Test
    fun `default is all-sims`() {
        // A fresh install watching nothing would look identical to a broken one.
        // Safe because a fresh install has no rules and no gateway credentials,
        // so it cannot send anything regardless.
        assertEquals(SimSelection.AllSims, SimSelection.DEFAULT)
    }

    private fun roundTrip(selection: SimSelection): SimSelection =
        SimSelection.decode(SimSelection.encode(selection))
}
