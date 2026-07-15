package com.scopesms.autoreply.domain.money

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The money type the whole matching decision rests on. If [KshAmount.parse] is
 * wrong, the rules engine compares the wrong number and the agent's customer
 * gets the wrong SMS.
 */
class KshAmountTest {

    @Test
    fun `parses whole shillings`() {
        assertThat(KshAmount.parse("20")).isEqualTo(KshAmount(2000))
    }

    @Test
    fun `parses two decimal places as M-Pesa writes them`() {
        assertThat(KshAmount.parse("20.00")).isEqualTo(KshAmount(2000))
        assertThat(KshAmount.parse("20.50")).isEqualTo(KshAmount(2050))
        assertThat(KshAmount.parse("0.99")).isEqualTo(KshAmount(99))
    }

    @Test
    fun `treats a single decimal place as tenths, not hundredths`() {
        // "20.5" is twenty shillings fifty cents. Reading it as 20.05 would
        // under-count by 45 cents and miss an exact bundle match.
        assertThat(KshAmount.parse("20.5")).isEqualTo(KshAmount(2050))
    }

    @Test
    fun `parses thousands separators`() {
        // From the real sample in CLAUDE.md: "New Account balance is Ksh1300.22".
        assertThat(KshAmount.parse("1,300.22")).isEqualTo(KshAmount(130022))
        assertThat(KshAmount.parse("1300.22")).isEqualTo(KshAmount(130022))
    }

    @Test
    fun `parses an optional currency prefix and surrounding space`() {
        assertThat(KshAmount.parse("Ksh20.00")).isEqualTo(KshAmount(2000))
        assertThat(KshAmount.parse("KSh 20")).isEqualTo(KshAmount(2000))
        assertThat(KshAmount.parse("KES20.00")).isEqualTo(KshAmount(2000))
        assertThat(KshAmount.parse("  20.00  ")).isEqualTo(KshAmount(2000))
    }

    @Test
    fun `rejects malformed input rather than guessing`() {
        // Every one of these would, if coerced to a number, be matched against
        // the price list as if it were a real payment.
        assertThat(KshAmount.parse("")).isNull()
        assertThat(KshAmount.parse("abc")).isNull()
        assertThat(KshAmount.parse("20.001")).isNull() // 3dp is not an M-Pesa amount
        assertThat(KshAmount.parse("1,30")).isNull() // half-grouped: regex grabbed the wrong span
        assertThat(KshAmount.parse("20 shillings")).isNull()
        assertThat(KshAmount.parse("-20")).isNull()
        assertThat(KshAmount.parse("20.")).isNull()
    }

    @Test
    fun `equality is exact, which is the whole point of using cents`() {
        assertThat(KshAmount.parse("20.00")).isEqualTo(KshAmount.ofShillings(20))
        assertThat(KshAmount.parse("20.01")).isNotEqualTo(KshAmount.ofShillings(20))
    }

    @Test
    fun `formats without trailing zero decimals`() {
        assertThat(KshAmount.ofShillings(20).format()).isEqualTo("20")
        assertThat(KshAmount(2050).format()).isEqualTo("20.50")
        assertThat(KshAmount(2005).format()).isEqualTo("20.05")
        assertThat(KshAmount(130022).format()).isEqualTo("1300.22")
        assertThat(KshAmount.ZERO.format()).isEqualTo("0")
    }

    @Test
    fun `orders by value`() {
        val sorted = listOf(KshAmount(10000), KshAmount(2000), KshAmount(5000)).sorted()
        assertThat(sorted).containsExactly(KshAmount(2000), KshAmount(5000), KshAmount(10000)).inOrder()
    }
}
