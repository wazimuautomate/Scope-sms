package com.tricreta.scopesms.domain.parser

import com.tricreta.scopesms.domain.money.KshAmount

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ### 🔴 Read this before trusting a green run
 *
 * BUILD-PLAN Phase 2's exit criterion is "unit tests pass against all collected
 * real sample messages". **We have exactly one real sample** — [REAL_SAMPLE],
 * from CLAUDE.md — and memory.md flags getting 5–10 more from the agent as a
 * hard blocker.
 *
 * So be honest about what the rest of these tests are. Everything below
 * [REAL_SAMPLE] is a *constructed* variant: a hypothesis about how M-Pesa might
 * word things, not an observed message. They prove the parser is tolerant of
 * the variance we can reason about (spacing, comma grouping, missing clauses,
 * names with full stops). They cannot prove it handles variance nobody here has
 * seen — only real samples can, and this suite is where they go when they
 * arrive.
 *
 * A passing run means "no known case is broken", not "the parser is done".
 */
class MpesaParserTest {

    private companion object {
        /**
         * The client's actual till confirmation, verbatim from CLAUDE.md
         * (account details already redacted there).
         *
         * Note `Confirmed.on` and `PMKsh20.00` — M-Pesa's own spacing is
         * irregular, which is the single most important thing this sample
         * teaches.
         */
        const val REAL_SAMPLE =
            "UGFMXB3GR6 Confirmed.on 15/7/26 at 1:06 PMKsh20.00 received from " +
                "254700000000 Skycope Bonke. New Account balance is Ksh1300.22. " +
                "Transaction cost, Ksh0.00."
    }

    private fun parse(body: String): MpesaPayment {
        val result = MpesaParser.parse(body)
        assertTrue("Expected a parse, got $result for: $body", result is ParseResult.Parsed)
        return (result as ParseResult.Parsed).payment
    }

    private fun rejection(body: String): Rejection {
        val result = MpesaParser.parse(body)
        assertTrue("Expected a rejection, got $result for: $body", result is ParseResult.Rejected)
        return (result as ParseResult.Rejected).reason
    }

    // --- The one real message ------------------------------------------------

    @Test
    fun `parses every field of the real sample`() {
        val payment = parse(REAL_SAMPLE)

        assertEquals("UGFMXB3GR6", payment.transactionCode)
        assertEquals(KshAmount(2000), payment.amount)
        assertEquals("0700000000", payment.senderPhone)
        assertEquals("Skycope Bonke", payment.senderName)
        assertEquals("15/7/26", payment.date)
        assertEquals("1:06 PM", payment.time)
        assertEquals(KshAmount(130022), payment.balance)
        assertEquals(KshAmount(0), payment.transactionCost)
    }

    @Test
    fun `parses the real sample when it arrives wrapped across lines`() {
        // Multipart reassembly and OEM concatenation both introduce newlines
        // that aren't in the message as sent.
        val wrapped = REAL_SAMPLE.replace(" received from ", " received from\n")

        assertEquals(KshAmount(2000), parse(wrapped).amount)
    }

    // --- Amount variance -----------------------------------------------------

    @Test
    fun `parses an amount with thousands separators`() {
        val payment = parse(REAL_SAMPLE.replace("Ksh20.00 received", "Ksh1,500.00 received"))

        assertEquals(KshAmount(150000), payment.amount)
    }

    @Test
    fun `parses an amount with no decimal part`() {
        val payment = parse(REAL_SAMPLE.replace("Ksh20.00 received", "Ksh50 received"))

        assertEquals(KshAmount(5000), payment.amount)
    }

    @Test
    fun `parses an amount with a space after Ksh`() {
        val payment = parse(REAL_SAMPLE.replace("PMKsh20.00", "PM Ksh 20.00"))

        assertEquals(KshAmount(2000), payment.amount)
    }

    @Test
    fun `amount is exact in cents, not a rounded double`() {
        // The whole rules engine is an equality check on this value. 20.10 as a
        // double is 20.099999999999998; as cents it is 2010, exactly.
        val payment = parse(REAL_SAMPLE.replace("Ksh20.00 received", "Ksh20.10 received"))

        assertEquals(KshAmount(2010), payment.amount)
    }

    // --- Name variance (BUILD-PLAN calls these out explicitly) ---------------

    @Test
    fun `parses a name containing initials`() {
        // The lazy-with-lookahead capture exists for this: a naive "up to the
        // first full stop" would truncate this to "J".
        val payment = parse(REAL_SAMPLE.replace("Skycope Bonke", "J. K. Mwangi"))

        assertEquals("J. K. Mwangi", payment.senderName)
    }

    @Test
    fun `parses a long multi-word name`() {
        val name = "Anastasia Wanjiru Nyambura Kamau"
        val payment = parse(REAL_SAMPLE.replace("Skycope Bonke", name))

        assertEquals(name, payment.senderName)
    }

    @Test
    fun `parses a single-word name`() {
        assertEquals("Otieno", parse(REAL_SAMPLE.replace("Skycope Bonke", "Otieno")).senderName)
    }

    @Test
    fun `name is null rather than blank when absent`() {
        // Phase 4 renders {name}; null says "no name" where "" invites "Hi ,".
        val payment = parse(REAL_SAMPLE.replace(" Skycope Bonke.", "."))

        assertNull(payment.senderName)
    }

    // --- Optional clauses ----------------------------------------------------

    @Test
    fun `parses when the balance and cost clauses are missing entirely`() {
        // A payment with an unfamiliar tail is still a payment. Refusing it
        // would cost the agent a customer over a clause we don't even use.
        val payment = parse(
            "UGFMXB3GR6 Confirmed.on 15/7/26 at 1:06 PMKsh20.00 received from " +
                "254700000000 Skycope Bonke.",
        )

        assertEquals(KshAmount(2000), payment.amount)
        assertEquals("Skycope Bonke", payment.senderName)
        assertNull(payment.balance)
        assertNull(payment.transactionCost)
    }

    @Test
    fun `parses the M-PESA wording of the balance clause`() {
        val payment = parse(
            REAL_SAMPLE.replace("New Account balance is", "New M-PESA balance is"),
        )

        assertEquals(KshAmount(130022), payment.balance)
    }

    @Test
    fun `parses a non-zero transaction cost`() {
        val payment = parse(REAL_SAMPLE.replace("Transaction cost, Ksh0.00", "Transaction cost, Ksh23.00"))

        assertEquals(KshAmount(2300), payment.transactionCost)
    }

    // --- Phone normalisation -------------------------------------------------

    @Test
    fun `normalises an international number to local form`() {
        // The gateway's /sendsms documents local 07.../01... form.
        assertEquals("0700000000", parse(REAL_SAMPLE).senderPhone)
    }

    @Test
    fun `accepts a plus-prefixed international number`() {
        val payment = parse(REAL_SAMPLE.replace("254700000000", "+254712345678"))

        assertEquals("0712345678", payment.senderPhone)
    }

    @Test
    fun `accepts a number already in local form`() {
        val payment = parse(REAL_SAMPLE.replace("254700000000", "0712345678"))

        assertEquals("0712345678", payment.senderPhone)
    }

    @Test
    fun `accepts an 01 prefix number`() {
        val payment = parse(REAL_SAMPLE.replace("254700000000", "254110000000"))

        assertEquals("0110000000", payment.senderPhone)
    }

    // --- Rejections ----------------------------------------------------------

    @Test
    fun `rejects a null or blank body without throwing`() {
        assertEquals(Rejection.EMPTY_BODY, rejection(""))
        assertEquals(Rejection.EMPTY_BODY, rejection("   "))
        assertTrue(MpesaParser.parse(null) is ParseResult.Rejected)
    }

    @Test
    fun `rejects a sent-money confirmation`() {
        // The agent paying someone. Replying would text the recipient a price
        // list because the agent spent money.
        val reason = rejection(
            "UGFMXB3GR6 Confirmed. Ksh500.00 sent to JOHN DOE 254700000000 on 15/7/26 " +
                "at 1:06 PM. New M-PESA balance is Ksh1300.22.",
        )

        assertEquals(Rejection.WRONG_TRANSACTION_TYPE, reason)
    }

    @Test
    fun `rejects a withdrawal confirmation`() {
        val reason = rejection(
            "UGFMXB3GR6 Confirmed.on 15/7/26 at 1:06 PM Withdraw Ksh500.00 from " +
                "123456 - Agent Name. New M-PESA balance is Ksh800.00.",
        )

        assertEquals(Rejection.WRONG_TRANSACTION_TYPE, reason)
    }

    @Test
    fun `rejects an airtime purchase confirmation`() {
        val reason = rejection(
            "UGFMXB3GR6 Confirmed. You bought Ksh100.00 of airtime on 15/7/26 at 1:06 PM.",
        )

        assertEquals(Rejection.WRONG_TRANSACTION_TYPE, reason)
    }

    @Test
    fun `rejects a balance check`() {
        val reason = rejection(
            "UGFMXB3GR6 Confirmed. Your M-PESA balance was Ksh1300.22 on 15/7/26 at 1:06 PM.",
        )

        assertEquals(Rejection.WRONG_TRANSACTION_TYPE, reason)
    }

    @Test
    fun `rejects a pay-bill payment made by the agent`() {
        val reason = rejection(
            "UGFMXB3GR6 Confirmed. Ksh200.00 paid to KPLC PREPAID on 15/7/26 at 1:06 PM.",
        )

        assertEquals(Rejection.WRONG_TRANSACTION_TYPE, reason)
    }

    @Test
    fun `rejects unrelated text with the distinct not-a-payment reason`() {
        // NOT_A_RECEIVED_MESSAGE is the signal that the regex may be wrong,
        // so it must not be conflated with a deliberately-ignored type.
        assertEquals(Rejection.NOT_A_RECEIVED_MESSAGE, rejection("Hey, are we still on for lunch?"))
    }

    @Test
    fun `rejects a received message whose phone number cannot be dialled`() {
        // A shortcode can't receive an SMS reply; better to reject than to have
        // the gateway bill the agent for a message to nowhere.
        val reason = rejection(REAL_SAMPLE.replace("254700000000", "20880"))

        // The number never satisfies the pattern's phone group, so this surfaces
        // as a non-match rather than UNREADABLE_PHONE. Either way it is not sent.
        assertTrue(
            "Expected the message to be refused, was $reason",
            reason == Rejection.UNREADABLE_PHONE || reason == Rejection.NOT_A_RECEIVED_MESSAGE,
        )
    }

    @Test
    fun `rejects a landline-prefixed number`() {
        assertNull(MpesaParser.normalizeKenyanMsisdn("254200000000"))
    }

    // --- Sender authentication ----------------------------------------------

    @Test
    fun `accepts the M-Pesa sender id`() {
        assertTrue(MpesaParser.isMpesaSender("MPESA"))
        assertTrue(MpesaParser.isMpesaSender("M-PESA"))
        assertTrue(MpesaParser.isMpesaSender("mpesa"))
        assertTrue(MpesaParser.isMpesaSender(" MPESA "))
    }

    @Test
    fun `rejects a spoofed payment from an ordinary number`() {
        // Without this, anyone who knows the agent's number could text them a
        // fake "Ksh20 received" and make the app send a stranger an SMS at the
        // agent's expense.
        assertTrue(!MpesaParser.isMpesaSender("+254700000000"))
        assertTrue(!MpesaParser.isMpesaSender("MPESA-KE"))
        assertTrue(!MpesaParser.isMpesaSender("SAFARICOM"))
        assertTrue(!MpesaParser.isMpesaSender(null))
        assertTrue(!MpesaParser.isMpesaSender(""))
    }

    // --- Agent-whitelisted senders (e.g. a reseller's own SKYSCOPE_ number) --

    @Test
    fun `accepts a whitelisted sender in addition to the official shortcode`() {
        assertTrue(MpesaParser.isMpesaSender("SKYSCOPE_", setOf("SKYSCOPE_")))
        // The official shortcode still works when a whitelist is configured.
        assertTrue(MpesaParser.isMpesaSender("MPESA", setOf("SKYSCOPE_")))
    }

    @Test
    fun `whitelist match is case-insensitive and trims both sides`() {
        assertTrue(MpesaParser.isMpesaSender("skyscope_", setOf("SKYSCOPE_")))
        assertTrue(MpesaParser.isMpesaSender(" SKYSCOPE_ ", setOf(" skyscope_ ")))
    }

    @Test
    fun `an empty whitelist changes nothing — default behaviour is preserved`() {
        assertTrue(!MpesaParser.isMpesaSender("SKYSCOPE_"))
        assertTrue(!MpesaParser.isMpesaSender("SKYSCOPE_", emptySet()))
    }

    @Test
    fun `a sender not on the whitelist and not the official shortcode is still rejected`() {
        assertTrue(!MpesaParser.isMpesaSender("SOMEONE_ELSE", setOf("SKYSCOPE_")))
    }

    // --- Never throws --------------------------------------------------------

    @Test
    fun `survives adversarial input without throwing`() {
        // BUILD-PLAN Phase 9: malformed SMS must log and skip, never crash. An
        // exception escaping here reaches the BroadcastReceiver.
        val nasty = listOf(
            "Confirmed.on at PMKsh received from",
            "UGFMXB3GR6 Confirmed.on 99/99/99 at 99:99 PMKsh received from 254700000000 X.",
            "Ksh".repeat(5000),
            "UGFMXB3GR6 Confirmed.on 15/7/26 at 1:06 PMKsh99999999999999999999.00 received from 254700000000 X.",
            " ￿ ${'\uD83D'}${'\uDE00'}",
        )

        nasty.forEach { input ->
            // The assertion is that this line does not throw.
            MpesaParser.parse(input)
        }
    }

    @Test
    fun `an amount too large for a Long is rejected, not silently wrapped`() {
        val reason = rejection(
            REAL_SAMPLE.replace("Ksh20.00 received", "Ksh99999999999999999999.00 received"),
        )

        assertEquals(Rejection.UNREADABLE_AMOUNT, reason)
    }
}
