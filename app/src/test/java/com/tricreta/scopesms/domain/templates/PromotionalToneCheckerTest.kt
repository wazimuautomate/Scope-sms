package com.tricreta.scopesms.domain.templates

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The sender ID is registered as *transactional* with Safaricom — a message
 * that reads as promotional risks the carrier silently blocking it, with
 * nothing for the gateway clients (`network/BlazeTechGateway`,
 * `network/HostPinnacleGateway`) to report. This is the safety net
 * that catches that in the editor, before a template is ever saved.
 */
class PromotionalToneCheckerTest {

    @Test
    fun `both shipped default templates are clean`() {
        assertThat(PromotionalToneChecker.check(DefaultTemplates.UNMATCHED).soundsPromotional).isFalse()
        assertThat(PromotionalToneChecker.check(DefaultTemplates.MATCHED).soundsPromotional).isFalse()
    }

    @Test
    fun `plain transactional wording is clean`() {
        val body = "Hi {name}, we received Ksh {amount}. Thank you for your business."
        assertThat(PromotionalToneChecker.check(body).soundsPromotional).isFalse()
    }

    // --- This app's own vocabulary must not false-positive -------------------

    @Test
    fun `bundle offers price list does not trigger a false positive`() {
        // The exact shape of the real unmatched-flow default: "offers" and a
        // price list are this app's single most common legitimate message.
        val body = "Our current offers:\nKsh 20 - 1GB Daily\nKsh 50 - 2GB Weekly"
        assertThat(PromotionalToneChecker.check(body).soundsPromotional).isFalse()
    }

    @Test
    fun `a bundle literally named Free Minutes does not trigger a false positive`() {
        val body = "Thank you for buying Free Minutes Bundle for Ksh 20."
        assertThat(PromotionalToneChecker.check(body).soundsPromotional).isFalse()
    }

    @Test
    fun `short known abbreviations in caps are not shouting`() {
        val body = "Your PIN and SMS balance with KSH and MPESA are OK via ATM or USD."
        assertThat(PromotionalToneChecker.check(body).soundsPromotional).isFalse()
    }

    @Test
    fun `a single exclamation mark is not excessive punctuation`() {
        assertThat(PromotionalToneChecker.check("Thank you!").soundsPromotional).isFalse()
    }

    @Test
    fun `a single emoji is not flagged`() {
        assertThat(PromotionalToneChecker.check("Thank you 😀").soundsPromotional).isFalse()
    }

    // --- Each category is actually detected -----------------------------------

    @Test
    fun `urgency language is flagged`() {
        val result = PromotionalToneChecker.check("Hurry, limited time offer today!")
        assertThat(result.issues.map { it.category }).contains(ToneIssueCategory.URGENCY)
    }

    @Test
    fun `call to action language is flagged`() {
        val result = PromotionalToneChecker.check("Buy now and click here to order more.")
        assertThat(result.issues.map { it.category }).contains(ToneIssueCategory.CALL_TO_ACTION)
    }

    @Test
    fun `word-based discount framing is flagged`() {
        val result = PromotionalToneChecker.check("Big discount this week, best price around.")
        assertThat(result.issues.map { it.category }).contains(ToneIssueCategory.DISCOUNT_FRAMING)
    }

    @Test
    fun `numeric percent-off discount framing is flagged`() {
        val result = PromotionalToneChecker.check("Get 50% off today.")
        val discount = result.issues.filter { it.category == ToneIssueCategory.DISCOUNT_FRAMING }
        assertThat(discount).isNotEmpty()
        assertThat(discount.first().matchedText).ignoringCase().contains("50%")
    }

    @Test
    fun `prize and incentive language is flagged`() {
        val result = PromotionalToneChecker.check("Congratulations you've won! Claim your prize now.")
        assertThat(result.issues.map { it.category }).contains(ToneIssueCategory.PRIZE_OR_INCENTIVE)
    }

    @Test
    fun `all-caps shouting is flagged`() {
        val result = PromotionalToneChecker.check("AMAZING DEAL TODAY")
        val shouted = result.issues.filter { it.category == ToneIssueCategory.SHOUTING }
            .map { it.matchedText }
        assertThat(shouted).containsAtLeast("AMAZING", "DEAL", "TODAY")
    }

    @Test
    fun `repeated exclamation marks are flagged`() {
        val result = PromotionalToneChecker.check("Buy now!!!")
        assertThat(result.issues.map { it.category }).contains(ToneIssueCategory.EXCESSIVE_PUNCTUATION)
    }

    @Test
    fun `repeated question marks are flagged`() {
        val result = PromotionalToneChecker.check("Why wait??")
        assertThat(result.issues.map { it.category }).contains(ToneIssueCategory.EXCESSIVE_PUNCTUATION)
    }

    @Test
    fun `two or more emoji are flagged`() {
        val result = PromotionalToneChecker.check("Sale now 🎉🎉")
        assertThat(result.issues.map { it.category }).contains(ToneIssueCategory.EMOJI)
    }

    @Test
    fun `matching is case-insensitive`() {
        val result = PromotionalToneChecker.check("HURRY, BUY NOW")
        assertThat(result.issues.map { it.category })
            .containsAtLeast(ToneIssueCategory.URGENCY, ToneIssueCategory.CALL_TO_ACTION)
    }

    @Test
    fun `a repeated phrase is reported once, not once per occurrence`() {
        val result = PromotionalToneChecker.check("Hurry! Hurry! Hurry!")
        val urgencyHits = result.issues.filter { it.category == ToneIssueCategory.URGENCY }
        assertThat(urgencyHits).hasSize(1)
    }

    @Test
    fun `a clean result exposes no issues`() {
        assertThat(ToneCheckResult.CLEAN.soundsPromotional).isFalse()
        assertThat(ToneCheckResult.CLEAN.issues).isEmpty()
    }
}
