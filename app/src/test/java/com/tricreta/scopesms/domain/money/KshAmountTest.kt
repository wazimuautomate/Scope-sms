package com.tricreta.scopesms.domain.money

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

    // --- parseWholeShillings: what the agent is allowed to type ------------
    //
    // The client's requirement: bundle prices are plain integers, never 123.50.
    // Enforced at entry so that format() can be trusted to render a rule's price
    // with no decimal point anywhere else in the app.

    @Test
    fun `parses a whole-shilling price`() {
        assertThat(KshAmount.parseWholeShillings("50")).isEqualTo(KshAmount.ofShillings(50))
        assertThat(KshAmount.parseWholeShillings("1")).isEqualTo(KshAmount(100))
        assertThat(KshAmount.parseWholeShillings("1300")).isEqualTo(KshAmount(130_000))
    }

    @Test
    fun `tolerates whitespace and thousands separators`() {
        assertThat(KshAmount.parseWholeShillings("  50 ")).isEqualTo(KshAmount.ofShillings(50))
        assertThat(KshAmount.parseWholeShillings("1,300")).isEqualTo(KshAmount.ofShillings(1300))
    }

    @Test
    fun `rejects decimals`() {
        // The whole point. parse() would happily accept these; entry must not.
        assertThat(KshAmount.parseWholeShillings("50.50")).isNull()
        assertThat(KshAmount.parseWholeShillings("50.00")).isNull()
        assertThat(KshAmount.parseWholeShillings("0.5")).isNull()
    }

    @Test
    fun `rejects anything that is not a plain positive number`() {
        assertThat(KshAmount.parseWholeShillings("")).isNull()
        assertThat(KshAmount.parseWholeShillings("   ")).isNull()
        assertThat(KshAmount.parseWholeShillings("-50")).isNull()
        assertThat(KshAmount.parseWholeShillings("Ksh 50")).isNull()
        assertThat(KshAmount.parseWholeShillings("fifty")).isNull()
        assertThat(KshAmount.parseWholeShillings("5e3")).isNull()
    }

    @Test
    fun `rejects an amount that would overflow rather than wrapping negative`() {
        // ofShillings multiplies by 100. Without the guard this wraps to a
        // negative that could compare equal to something unrelated — and equality
        // is how every payment is matched.
        assertThat(KshAmount.parseWholeShillings("99999999999999999999")).isNull()
        assertThat(KshAmount.parseWholeShillings(Long.MAX_VALUE.toString())).isNull()
    }

    @Test
    fun `parseWholeShillings round-trips through format`() {
        // The editor pre-fills from a stored rule and saves back through this.
        // If format() ever emitted "50.00", parseWholeShillings would reject the
        // agent's own unedited price.
        val amount = KshAmount.parseWholeShillings("50")!!
        assertThat(amount.format()).isEqualTo("50")
        assertThat(KshAmount.parseWholeShillings(amount.format())).isEqualTo(amount)
        assertThat(amount.shillings).isEqualTo(50)
        assertThat(amount.isWholeShillings).isTrue()
    }

    @Test
    fun `a customer payment with cents is not whole shillings`() {
        // What the agent types is constrained; what a customer sends is not.
        assertThat(KshAmount(2050).isWholeShillings).isFalse()
        assertThat(KshAmount(2000).isWholeShillings).isTrue()
    }
}
