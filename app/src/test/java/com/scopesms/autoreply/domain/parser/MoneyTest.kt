package com.scopesms.autoreply.domain.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Money is integer cents because the app's core operation is
 * `payment.amount == rule.amount`. Every case here is one where a Double would
 * either lose the comparison or silently round the agent's money.
 */
class MoneyTest {

    @Test
    fun `parses whole shillings`() {
        assertEquals(2000L, Money.parseCents("20"))
        assertEquals(0L, Money.parseCents("0"))
    }

    @Test
    fun `parses two decimal places`() {
        assertEquals(2000L, Money.parseCents("20.00"))
        assertEquals(2010L, Money.parseCents("20.10"))
        assertEquals(130022L, Money.parseCents("1300.22"))
    }

    @Test
    fun `parses one decimal place as tenths, not hundredths`() {
        // "20.5" is 20 shillings 50 cents. Read naively it becomes 5 cents, and
        // the agent's 20.50 bundle silently stops matching.
        assertEquals(2050L, Money.parseCents("20.5"))
    }

    @Test
    fun `parses thousands separators`() {
        assertEquals(150_000L, Money.parseCents("1,500.00"))
        assertEquals(100_000_000L, Money.parseCents("1,000,000.00"))
    }

    @Test
    fun `tolerates surrounding whitespace`() {
        assertEquals(2000L, Money.parseCents("  20.00 "))
    }

    @Test
    fun `rejects malformed input rather than guessing`() {
        assertNull(Money.parseCents(""))
        assertNull(Money.parseCents("   "))
        assertNull(Money.parseCents("abc"))
        assertNull(Money.parseCents("20.00.00"))
        assertNull(Money.parseCents("20."))
        assertNull(Money.parseCents("-20.00"))
        // Three decimals isn't a currency amount we recognise — refusing beats
        // silently truncating a real customer's payment.
        assertNull(Money.parseCents("20.000"))
    }

    @Test
    fun `rejects a value too large for a Long instead of overflowing`() {
        // Overflow would wrap to a negative amount, which could then match a
        // rule it has nothing to do with.
        assertNull(Money.parseCents("99999999999999999999"))
    }

    @Test
    fun `formats cents for display`() {
        assertEquals("20.00", Money.format(2000))
        assertEquals("20.10", Money.format(2010))
        // The padStart case: 5 cents must render as ".05", not ".5".
        assertEquals("20.05", Money.format(2005))
        assertEquals("0.00", Money.format(0))
        assertEquals("1300.22", Money.format(130022))
    }

    @Test
    fun `format round-trips through parse`() {
        listOf(0L, 5L, 100L, 2000L, 2050L, 130022L).forEach { cents ->
            assertEquals(cents, Money.parseCents(Money.format(cents)))
        }
    }
}
