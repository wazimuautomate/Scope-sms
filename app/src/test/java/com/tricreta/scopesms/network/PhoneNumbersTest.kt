package com.tricreta.scopesms.network

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The gateway documents `07XXXXXXXX`/`01XXXXXXXX`; M-Pesa hands us `254…`.
 * This is the seam, so it gets its own tests.
 */
class PhoneNumbersTest {

    @Test
    fun `the format M-Pesa actually sends is converted to local`() {
        // Straight from the sample in CLAUDE.md: "...received from 254700000000".
        assertThat(PhoneNumbers.toLocalFormat("254700000000")).isEqualTo("0700000000")
        assertThat(PhoneNumbers.toLocalFormat("254712345678")).isEqualTo("0712345678")
    }

    @Test
    fun `already-local numbers pass through unchanged`() {
        assertThat(PhoneNumbers.toLocalFormat("0700000000")).isEqualTo("0700000000")
        assertThat(PhoneNumbers.toLocalFormat("0112345678")).isEqualTo("0112345678")
    }

    @Test
    fun `formats an agent might type by hand are accepted`() {
        assertThat(PhoneNumbers.toLocalFormat("+254 712 345 678")).isEqualTo("0712345678")
        assertThat(PhoneNumbers.toLocalFormat("0712-345-678")).isEqualTo("0712345678")
        assertThat(PhoneNumbers.toLocalFormat(" 0712 345 678 ")).isEqualTo("0712345678")
        assertThat(PhoneNumbers.toLocalFormat("712345678")).isEqualTo("0712345678")
    }

    @Test
    fun `the 01 range is accepted`() {
        // Safaricom issues 011x; rejecting it would silently drop real customers.
        assertThat(PhoneNumbers.toLocalFormat("254110000000")).isEqualTo("0110000000")
    }

    @Test
    fun `implausible numbers are rejected rather than sent`() {
        // Each becomes a terminal InvalidPhone, which surfaces in the activity
        // log — usually the visible symptom of a Phase 2 parser bug.
        assertThat(PhoneNumbers.toLocalFormat("")).isNull()
        assertThat(PhoneNumbers.toLocalFormat("not-a-number")).isNull()
        assertThat(PhoneNumbers.toLocalFormat("070000000")).isNull() // 9 digits, leading 0
        assertThat(PhoneNumbers.toLocalFormat("07000000000")).isNull() // 11 digits
        assertThat(PhoneNumbers.toLocalFormat("254600000000")).isNull() // not a mobile prefix
        assertThat(PhoneNumbers.toLocalFormat("0600000000")).isNull()
        assertThat(PhoneNumbers.toLocalFormat("1234")).isNull()
    }

    @Test
    fun `a paybill-style shortcode is rejected`() {
        // M-Pesa messages contain short codes; none of them is a customer we can
        // reply to, and sending to one would just burn gateway credit.
        assertThat(PhoneNumbers.toLocalFormat("888222")).isNull()
    }

    // --- toInternationalFormat (HostPinnacle's `mobile` field) --------------

    @Test
    fun `the format M-Pesa actually sends passes through unchanged internationally`() {
        assertThat(PhoneNumbers.toInternationalFormat("254700000000")).isEqualTo("254700000000")
        assertThat(PhoneNumbers.toInternationalFormat("254712345678")).isEqualTo("254712345678")
    }

    @Test
    fun `local numbers are converted to international`() {
        assertThat(PhoneNumbers.toInternationalFormat("0700000000")).isEqualTo("254700000000")
        assertThat(PhoneNumbers.toInternationalFormat("0112345678")).isEqualTo("254112345678")
    }

    @Test
    fun `international formats an agent might type by hand are accepted`() {
        assertThat(PhoneNumbers.toInternationalFormat("+254 712 345 678")).isEqualTo("254712345678")
        assertThat(PhoneNumbers.toInternationalFormat("0712-345-678")).isEqualTo("254712345678")
        assertThat(PhoneNumbers.toInternationalFormat(" 0712 345 678 ")).isEqualTo("254712345678")
        assertThat(PhoneNumbers.toInternationalFormat("712345678")).isEqualTo("254712345678")
    }

    @Test
    fun `the 01 range is accepted internationally`() {
        assertThat(PhoneNumbers.toInternationalFormat("254110000000")).isEqualTo("254110000000")
    }

    @Test
    fun `implausible numbers are rejected rather than sent internationally`() {
        assertThat(PhoneNumbers.toInternationalFormat("")).isNull()
        assertThat(PhoneNumbers.toInternationalFormat("not-a-number")).isNull()
        assertThat(PhoneNumbers.toInternationalFormat("070000000")).isNull() // 9 digits, leading 0
        assertThat(PhoneNumbers.toInternationalFormat("07000000000")).isNull() // 11 digits
        assertThat(PhoneNumbers.toInternationalFormat("254600000000")).isNull() // not a mobile prefix
        assertThat(PhoneNumbers.toInternationalFormat("0600000000")).isNull()
        assertThat(PhoneNumbers.toInternationalFormat("1234")).isNull()
    }

    @Test
    fun `a paybill-style shortcode is rejected internationally`() {
        assertThat(PhoneNumbers.toInternationalFormat("888222")).isNull()
    }
}
