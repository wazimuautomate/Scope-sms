package com.tricreta.scopesms.domain.notifications

import com.tricreta.scopesms.domain.money.KshAmount
import com.tricreta.scopesms.domain.rules.MatchOutcome
import com.tricreta.scopesms.domain.rules.PricingRule
import com.tricreta.scopesms.domain.templates.TemplateType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 6's exit criterion: "unit tests confirming all four toggle-state
 * combinations produce the correct enqueue/no-enqueue behavior."
 *
 * The four combinations are enumerated explicitly rather than generated, because
 * the point is to be able to read the truth table off the test file and check it
 * against what the agent asked for.
 */
class ReplyDecisionTest {

    private val rule = PricingRule(
        id = 1,
        amount = KshAmount.ofShillings(50),
        bundleDescription = "2GB Weekly",
    )

    private fun toggles(unmatched: Boolean, matched: Boolean, offWindow: Boolean = true) =
        NotificationToggles(
            unmatchedReplyEnabled = unmatched,
            matchedReplyEnabled = matched,
            offWindowReplyEnabled = offWindow,
        )

    // --- Combination 1 of 4: both flows on -----------------------------------

    @Test
    fun `both on - matched payment sends the matched flow`() {
        val decision = decideReply(MatchOutcome.Matched(rule), toggles(unmatched = true, matched = true))

        assertEquals(ReplyDecision.Send(TemplateType.MATCHED, rule), decision)
        assertTrue(decision.willSend)
    }

    @Test
    fun `both on - unmatched payment sends the unmatched flow`() {
        val decision = decideReply(MatchOutcome.Unmatched, toggles(unmatched = true, matched = true))

        assertEquals(ReplyDecision.Send(TemplateType.UNMATCHED, null), decision)
        assertTrue(decision.willSend)
    }

    // --- Combination 2 of 4: unmatched only (the recommended default) --------

    @Test
    fun `unmatched only - unmatched payment sends`() {
        val decision = decideReply(MatchOutcome.Unmatched, toggles(unmatched = true, matched = false))

        assertEquals(ReplyDecision.Send(TemplateType.UNMATCHED, null), decision)
    }

    @Test
    fun `unmatched only - matched payment is suppressed, not dropped`() {
        val decision = decideReply(MatchOutcome.Matched(rule), toggles(unmatched = true, matched = false))

        // Suppressed rather than "nothing": Phase 8 still logs this as
        // "matched, notification off". BUILD-PLAN Phase 6 requires it.
        assertEquals(ReplyDecision.Suppressed(TemplateType.MATCHED), decision)
        assertFalse(decision.willSend)
    }

    // --- Combination 3 of 4: matched only ------------------------------------

    @Test
    fun `matched only - matched payment sends`() {
        val decision = decideReply(MatchOutcome.Matched(rule), toggles(unmatched = false, matched = true))

        assertEquals(ReplyDecision.Send(TemplateType.MATCHED, rule), decision)
    }

    @Test
    fun `matched only - unmatched payment is suppressed`() {
        val decision = decideReply(MatchOutcome.Unmatched, toggles(unmatched = false, matched = true))

        assertEquals(ReplyDecision.Suppressed(TemplateType.UNMATCHED), decision)
        assertFalse(decision.willSend)
    }

    // --- Combination 4 of 4: both off ----------------------------------------

    @Test
    fun `both off - nothing sends on either flow`() {
        val allOff = toggles(unmatched = false, matched = false, offWindow = false)

        assertEquals(ReplyDecision.Suppressed(TemplateType.MATCHED), decideReply(MatchOutcome.Matched(rule), allOff))
        assertEquals(ReplyDecision.Suppressed(TemplateType.UNMATCHED), decideReply(MatchOutcome.Unmatched, allOff))
        assertTrue(allOff.allDisabled)
    }

    // --- The off-window flow, its own toggle ----------------------------------

    @Test
    fun `off-window toggle on - an out-of-window match sends the off-window flow`() {
        val decision = decideReply(MatchOutcome.OutOfWindow(rule), toggles(unmatched = true, matched = true))

        assertEquals(ReplyDecision.Send(TemplateType.OFF_WINDOW, rule), decision)
        assertTrue(decision.willSend)
    }

    @Test
    fun `off-window toggle off - an out-of-window match is suppressed, not dropped`() {
        val decision = decideReply(
            MatchOutcome.OutOfWindow(rule),
            toggles(unmatched = true, matched = true, offWindow = false),
        )

        assertEquals(ReplyDecision.Suppressed(TemplateType.OFF_WINDOW), decision)
        assertFalse(decision.willSend)
    }

    @Test
    fun `an out-of-window match carries the rule so {package} and {purchase_window} can name it`() {
        val decision = decideReply(MatchOutcome.OutOfWindow(rule), toggles(unmatched = true, matched = true))

        assertEquals("2GB Weekly", decision.matchedRuleOrNull?.bundleDescription)
    }

    @Test
    fun `the off-window toggle is independent of the other two`() {
        // Matched and unmatched both off must not suppress an out-of-window
        // reply — it is its own flow with its own switch.
        val decision = decideReply(
            MatchOutcome.OutOfWindow(rule),
            toggles(unmatched = false, matched = false, offWindow = true),
        )

        assertTrue(decision.willSend)
    }

    @Test
    fun `all three off - nothing sends on any flow`() {
        val allOff = toggles(unmatched = false, matched = false, offWindow = false)

        assertEquals(
            ReplyDecision.Suppressed(TemplateType.OFF_WINDOW),
            decideReply(MatchOutcome.OutOfWindow(rule), allOff),
        )
        assertTrue(allOff.allDisabled)
    }

    // --- The empty-price-list state beats every toggle combination -----------

    @Test
    fun `no rules configured - sends nothing whatever the toggles say`() {
        // All four combinations, since this is the arm that must not depend on
        // them: an empty price list renders {bundle_list} empty, so an unmatched
        // reply here would text a paying customer a blank price list.
        for (unmatched in listOf(true, false)) {
            for (matched in listOf(true, false)) {
                val decision = decideReply(MatchOutcome.NoRulesConfigured, toggles(unmatched, matched))

                assertEquals(
                    "toggles(unmatched=$unmatched, matched=$matched) must still send nothing",
                    ReplyDecision.NoRulesConfigured,
                    decision,
                )
                assertFalse(decision.willSend)
            }
        }
    }

    @Test
    fun `no rules configured is distinct from a suppressed flow`() {
        // The dashboard tells the agent to add prices in one case and to check
        // their toggles in the other. Collapsing them sends them hunting for a
        // switch that was never the problem.
        val decision = decideReply(MatchOutcome.NoRulesConfigured, toggles(unmatched = true, matched = true))

        assertNull(decision.flow)
        assertTrue(decision !is ReplyDecision.Suppressed)
    }

    // --- The matched rule has to survive to the template engine --------------

    @Test
    fun `matched send carries the rule so {package} can name it`() {
        val decision = decideReply(MatchOutcome.Matched(rule), toggles(unmatched = false, matched = true))

        assertEquals("2GB Weekly", decision.matchedRuleOrNull?.bundleDescription)
    }

    @Test
    fun `unmatched send carries no rule`() {
        val decision = decideReply(MatchOutcome.Unmatched, toggles(unmatched = true, matched = false))

        assertNull(decision.matchedRuleOrNull)
    }

    // --- Pins the shipped default, which is still unconfirmed ----------------

    @Test
    fun `default toggles are unmatched-on matched-off off-window-on`() {
        // 🔴 memory.md open decision 4: unmatched/matched are BUILD-PLAN's
        // recommendation, NOT a confirmed client answer. If the agent asks for
        // something else, change NotificationToggles.DEFAULT and this test
        // together — deliberately pinned so the change is a decision, not a
        // drift. offWindowReplyEnabled defaulting on is a separate, safe
        // decision — see NotificationToggles.DEFAULT's doc comment: it cannot
        // fire for any bundle until the agent sets a restricted window.
        assertTrue(NotificationToggles.DEFAULT.unmatchedReplyEnabled)
        assertFalse(NotificationToggles.DEFAULT.matchedReplyEnabled)
        assertTrue(NotificationToggles.DEFAULT.offWindowReplyEnabled)
    }

    @Test
    fun `default cannot text anyone before the agent enters prices`() {
        // Why shipping the unconfirmed default is safe: a fresh install has no
        // rules, so every payment lands on NoRulesConfigured regardless.
        val decision = decideReply(MatchOutcome.NoRulesConfigured, NotificationToggles.DEFAULT)

        assertFalse(decision.willSend)
    }
}
