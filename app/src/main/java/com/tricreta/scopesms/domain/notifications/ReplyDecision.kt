package com.tricreta.scopesms.domain.notifications

import com.tricreta.scopesms.domain.rules.MatchOutcome
import com.tricreta.scopesms.domain.rules.PricingRule
import com.tricreta.scopesms.domain.templates.TemplateType

/**
 * What should happen to one classified payment.
 *
 * Phase 6. This is the single place the toggles are applied, sitting between
 * the rules engine (Phase 3, which says *what the payment is*) and the outbound
 * queue (Phase 5b, which says *how a reply gets sent*). Neither of those two
 * knows about the toggles, and that separation is why this is a pure function
 * over data rather than a branch buried in the receiver: it is the rule that
 * decides whether the agent's customer gets a text, so it is the one that most
 * deserves to be readable and exhaustively testable without an Android device.
 *
 * Every arm is logged by Phase 8 — including the ones that send nothing.
 * "The app stayed silent" is a thing the agent will ask about, and the log has to
 * be able to answer *why*.
 */
sealed interface ReplyDecision {

    /** The flow this decision concerns, for logging. Null when there is no flow yet. */
    val flow: TemplateType?

    /**
     * Enqueue a reply on [flow].
     *
     * @param matchedRule the rule the payment matched — non-null exactly when
     *   [flow] is [TemplateType.MATCHED] or [TemplateType.OFF_WINDOW], because
     *   `{package}` (and, for off-window, `{purchase_window}`) has to name
     *   something and only those two flows have a rule to name.
     */
    data class Send(
        override val flow: TemplateType,
        val matchedRule: PricingRule?,
    ) : ReplyDecision

    /**
     * The flow fired, but the agent has it switched off. Log it, send nothing.
     *
     * BUILD-PLAN Phase 6 is explicit that this must still reach the activity log
     * ("matched, notification off") rather than vanishing. An agent wondering why
     * a customer wasn't texted needs to see that the app noticed the payment and
     * chose silence deliberately — otherwise the only available explanation is
     * "the app is broken", and they'd be right to think so.
     */
    data class Suppressed(override val flow: TemplateType) : ReplyDecision

    /**
     * The agent hasn't entered any bundle prices. Send nothing.
     *
     * Distinct from [Suppressed] because nothing is switched off and nothing is
     * broken — this is an un-set-up app, and the fix is "add your prices", not
     * "check your toggles". Phase 7's dashboard surfaces it as a setup prompt;
     * conflating it with a suppressed reply would send the agent hunting through
     * Settings for a switch that was never the problem.
     */
    data object NoRulesConfigured : ReplyDecision {
        override val flow: TemplateType? get() = null
    }
}

/**
 * Applies the agent's toggles to a classified payment.
 *
 * Pure, total, and synchronous: no I/O, no Android types, safe from the binder
 * thread the SMS receiver runs on (CLAUDE.md constraint 5). The `when` is
 * exhaustive over [MatchOutcome]'s arms, so a new arm added later fails the
 * build here rather than silently falling through to "send nothing" — which is
 * the failure mode that would cost the agent customers quietly.
 */
fun decideReply(outcome: MatchOutcome, toggles: NotificationToggles): ReplyDecision =
    when (outcome) {
        // Checked before the toggles on purpose. An empty price list beats both
        // switches: with no rules, `{bundle_list}` renders empty and an
        // unmatched reply would text a paying customer a blank price list. The
        // toggle says "the agent wants this flow"; this says "there is nothing
        // truthful to send yet".
        MatchOutcome.NoRulesConfigured -> ReplyDecision.NoRulesConfigured

        is MatchOutcome.Matched ->
            if (toggles.matchedReplyEnabled) {
                ReplyDecision.Send(TemplateType.MATCHED, outcome.rule)
            } else {
                ReplyDecision.Suppressed(TemplateType.MATCHED)
            }

        is MatchOutcome.OutOfWindow ->
            if (toggles.offWindowReplyEnabled) {
                ReplyDecision.Send(TemplateType.OFF_WINDOW, outcome.rule)
            } else {
                ReplyDecision.Suppressed(TemplateType.OFF_WINDOW)
            }

        MatchOutcome.Unmatched ->
            if (toggles.unmatchedReplyEnabled) {
                // No rule to carry: by definition nothing matched.
                ReplyDecision.Send(TemplateType.UNMATCHED, matchedRule = null)
            } else {
                ReplyDecision.Suppressed(TemplateType.UNMATCHED)
            }
    }

/** Convenience for the two call sites that only care whether a job gets queued. */
val ReplyDecision.willSend: Boolean get() = this is ReplyDecision.Send

/** The rule a [ReplyDecision.Send] matched, if any. Keeps Phase 5b's `when` short. */
val ReplyDecision.matchedRuleOrNull: PricingRule?
    get() = (this as? ReplyDecision.Send)?.matchedRule
