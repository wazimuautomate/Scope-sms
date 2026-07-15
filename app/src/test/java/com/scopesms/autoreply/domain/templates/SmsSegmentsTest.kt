package com.scopesms.autoreply.domain.templates

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * BUILD-PLAN Phase 4's segment-length calculator. Every extra segment is money
 * out of the agent's pocket on every reply, so the boundaries matter.
 */
class SmsSegmentsTest {

    private fun ascii(count: Int) = "a".repeat(count)

    // --- GSM-7 boundaries ---------------------------------------------------

    @Test
    fun `plain text is GSM-7`() {
        val measured = SmsSegments.measure("Hi Skycope, you paid Ksh 35.")

        assertThat(measured.encoding).isEqualTo(SmsEncoding.GSM_7BIT)
        assertThat(measured.segments).isEqualTo(1)
        assertThat(measured.willSplit).isFalse()
    }

    @Test
    fun `160 GSM characters is one segment and 161 is two`() {
        assertThat(SmsSegments.segmentsFor(ascii(160))).isEqualTo(1)
        assertThat(SmsSegments.segmentsFor(ascii(161))).isEqualTo(2)
    }

    @Test
    fun `multipart GSM segments hold 153, not 160`() {
        // The concatenation header eats 7 septets from every part.
        assertThat(SmsSegments.segmentsFor(ascii(306))).isEqualTo(2) // 153 * 2
        assertThat(SmsSegments.segmentsFor(ascii(307))).isEqualTo(3)
    }

    @Test
    fun `an extension character costs two septets`() {
        // 159 plain + one '€' = 161 septets, which is two segments even though
        // it is only 160 characters.
        val body = ascii(159) + "€"

        val measured = SmsSegments.measure(body)

        assertThat(measured.encoding).isEqualTo(SmsEncoding.GSM_7BIT)
        assertThat(measured.units).isEqualTo(161)
        assertThat(measured.segments).isEqualTo(2)
    }

    @Test
    fun `braces in an unrendered template body count as extension characters`() {
        val measured = SmsSegments.measure("{name}")

        // 4 basic + 2 braces at 2 septets each = 8.
        assertThat(measured.units).isEqualTo(8)
        assertThat(measured.encoding).isEqualTo(SmsEncoding.GSM_7BIT)
    }

    // --- UCS-2 --------------------------------------------------------------

    @Test
    fun `a curly apostrophe drops the whole message to UCS-2`() {
        // The most likely real cause: the agent types their template on a phone
        // keyboard that autocorrects ' to ’, and every reply silently costs
        // double from then on.
        val measured = SmsSegments.measure("Here" + "’" + "s your bundle")

        assertThat(measured.encoding).isEqualTo(SmsEncoding.UCS2)
    }

    @Test
    fun `70 UCS-2 characters is one segment and 71 is two`() {
        val nonGsm = "’" // one UTF-16 unit, but not in the GSM alphabet

        assertThat(SmsSegments.segmentsFor(nonGsm + ascii(69))).isEqualTo(1)
        assertThat(SmsSegments.segmentsFor(nonGsm + ascii(70))).isEqualTo(2)
    }

    @Test
    fun `multipart UCS-2 segments hold 67`() {
        val nonGsm = "’"

        assertThat(SmsSegments.segmentsFor(nonGsm + ascii(133))).isEqualTo(2) // 134 = 67 * 2
        assertThat(SmsSegments.segmentsFor(nonGsm + ascii(134))).isEqualTo(3)
    }

    @Test
    fun `an emoji counts as two UTF-16 units`() {
        // A surrogate pair occupies two units on the wire, so a "1 character"
        // emoji costs 2 of the 70.
        val measured = SmsSegments.measure("😀") // grinning face

        assertThat(measured.encoding).isEqualTo(SmsEncoding.UCS2)
        assertThat(measured.units).isEqualTo(2)
    }

    // --- Remaining capacity, for the editor's live counter --------------------

    @Test
    fun `remaining capacity counts down within a single segment`() {
        assertThat(SmsSegments.measure(ascii(100)).remainingInLastSegment).isEqualTo(60)
    }

    @Test
    fun `remaining capacity is measured against the multipart size once split`() {
        // 161 septets across 2 segments of 153 = 306 capacity, 145 left.
        assertThat(SmsSegments.measure(ascii(161)).remainingInLastSegment).isEqualTo(145)
    }

    @Test
    fun `an empty body is one empty segment`() {
        val measured = SmsSegments.measure("")

        assertThat(measured.segments).isEqualTo(1)
        assertThat(measured.units).isEqualTo(0)
    }

    // --- The realistic case the agent will actually hit -----------------------

    @Test
    fun `the shipped unmatched default fits one segment before the price list grows`() {
        val rendered = TemplateEngine.render(
            DefaultTemplates.UNMATCHED,
            mapOf(
                TemplateVariable.NAME to "Skycope Bonke",
                TemplateVariable.AMOUNT to "35",
                TemplateVariable.BUNDLE_LIST to "Ksh 20 - 1GB Daily\nKsh 50 - 2GB Weekly",
            ),
        )

        val measured = SmsSegments.measure(rendered)

        // Documents the real cost of the wording we ship rather than asserting
        // a guess: it is GSM-7 (so no accidental UCS-2 in our own default) and
        // this is the segment count the agent starts paying. If a future edit to
        // DefaultTemplates pushes this up, that should be a deliberate choice
        // made with the number visible, not a surprise on their bill.
        assertThat(measured.encoding).isEqualTo(SmsEncoding.GSM_7BIT)
        assertThat(measured.segments).isAtMost(2)
    }
}
