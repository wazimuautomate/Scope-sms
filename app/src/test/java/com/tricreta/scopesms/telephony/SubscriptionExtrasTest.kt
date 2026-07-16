package com.tricreta.scopesms.telephony

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which SIM a message arrived on is the input to the highest-severity decision
 * in the app (CLAUDE.md constraint 4), and the extras carrying it are
 * OEM-dependent. These tests pin the "we genuinely don't know" path, because
 * that is the one that quietly turns into a wrong answer if anyone ever gives
 * the lookup a default.
 */
class SubscriptionExtrasTest {

    /** Stands in for `Intent.getExtras()`. */
    private fun lookup(vararg pairs: Pair<String, Int>): (String) -> Int? {
        val map = pairs.toMap()
        return { key -> map[key] }
    }

    @Test
    fun `finds the documented subscription extra`() {
        val found = SubscriptionExtras.firstValid(
            SubscriptionExtras.SUBSCRIPTION_KEYS,
            lookup("android.telephony.extra.SUBSCRIPTION_INDEX" to 3),
        )

        assertEquals(3, found)
    }

    @Test
    fun `finds the AOSP subscription extra`() {
        // What the platform's SMS dispatcher actually writes.
        val found = SubscriptionExtras.firstValid(
            SubscriptionExtras.SUBSCRIPTION_KEYS,
            lookup("subscription" to 2),
        )

        assertEquals(2, found)
    }

    @Test
    fun `prefers the documented key when a device publishes several`() {
        // Some OEM builds ship both the AOSP key and a private one, and they
        // don't always agree. Precedence has to be deterministic rather than
        // dependent on Bundle iteration order.
        val found = SubscriptionExtras.firstValid(
            SubscriptionExtras.SUBSCRIPTION_KEYS,
            lookup(
                "android.telephony.extra.SUBSCRIPTION_INDEX" to 1,
                "subscription" to 99,
                "subId" to 42,
            ),
        )

        assertEquals(1, found)
    }

    @Test
    fun `returns null when no key is present`() {
        // The honest answer, and the one SimFilter is built to handle. A default
        // here would become a confident, wrong "slot 0".
        assertNull(SubscriptionExtras.firstValid(SubscriptionExtras.SUBSCRIPTION_KEYS, lookup()))
    }

    @Test
    fun `skips a key holding the invalid sentinel and keeps looking`() {
        // -1 is INVALID_SUBSCRIPTION_ID: present, but meaning "unknown".
        val found = SubscriptionExtras.firstValid(
            SubscriptionExtras.SUBSCRIPTION_KEYS,
            lookup(
                "android.telephony.extra.SUBSCRIPTION_INDEX" to -1,
                "subscription" to 5,
            ),
        )

        assertEquals(5, found)
    }

    @Test
    fun `returns null when every candidate is a sentinel`() {
        val found = SubscriptionExtras.firstValid(
            SubscriptionExtras.SUBSCRIPTION_KEYS,
            lookup(
                "android.telephony.extra.SUBSCRIPTION_INDEX" to -1,
                "subscription" to Int.MAX_VALUE,
            ),
        )

        assertNull(found)
    }

    @Test
    fun `finds OEM slot extras`() {
        assertEquals(0, SubscriptionExtras.firstValid(SubscriptionExtras.SLOT_KEYS, lookup("slot" to 0)))
        assertEquals(1, SubscriptionExtras.firstValid(SubscriptionExtras.SLOT_KEYS, lookup("simSlotIndex" to 1)))
    }

    @Test
    fun `slot zero is a real answer, not an absent one`() {
        // Slot 0 is SIM 1 — the most likely business SIM. Any truthiness-style
        // check would discard the most common correct value in the app.
        assertEquals(0, SubscriptionExtras.firstValid(SubscriptionExtras.SLOT_KEYS, lookup("slot" to 0)))
    }

    @Test
    fun `plausibility rejects the platform sentinels`() {
        assertFalse(SubscriptionExtras.isPlausible(-1))
        assertFalse(SubscriptionExtras.isPlausible(Int.MAX_VALUE))
        assertTrue(SubscriptionExtras.isPlausible(0))
        assertTrue(SubscriptionExtras.isPlausible(1))
    }

    @Test
    fun `subscription keys are preferred over slot keys by being a separate list`() {
        // Guards the ordering intent: a subscription ID resolves through the
        // platform's live mapping, a raw slot extra is whatever the OEM wrote.
        // If these lists ever merged, that precedence would be lost silently.
        assertTrue(SubscriptionExtras.SUBSCRIPTION_KEYS.none { it in SubscriptionExtras.SLOT_KEYS })
    }
}
