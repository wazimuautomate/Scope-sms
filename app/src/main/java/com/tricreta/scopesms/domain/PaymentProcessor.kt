package com.tricreta.scopesms.domain

import com.tricreta.scopesms.domain.log.MatchType
import com.tricreta.scopesms.domain.log.NotifyStatus
import com.tricreta.scopesms.domain.notifications.NotificationToggles
import com.tricreta.scopesms.domain.notifications.ReplyDecision
import com.tricreta.scopesms.domain.notifications.decideReply
import com.tricreta.scopesms.domain.parser.MpesaPayment
import com.tricreta.scopesms.domain.rules.MatchOutcome
import com.tricreta.scopesms.domain.rules.RuleSnapshot
import com.tricreta.scopesms.domain.templates.TemplateEngine
import com.tricreta.scopesms.domain.templates.TemplateSnapshot
import com.tricreta.scopesms.domain.templates.TemplateType

/**
 * What the decide path concluded about one payment, and what it wants done.
 *
 * Pure data, produced by [PaymentPlanner]. The Android-side runner
 * (`telephony/PaymentPipeline`) turns it into a queue insert and a log row.
 *
 * Splitting it this way is what makes the app's central decision — *does this
 * customer get a text, and what does it say?* — testable on the JVM with no
 * Room, no WorkManager and no device. Everything above this line is a rule;
 * everything below it is plumbing.
 */
sealed interface PaymentPlan {

    /** How the payment classified, for the activity log. */
    val matchType: MatchType

    /** The bundle the payment bought, when it bought one. */
    val bundleDescription: String?

    /**
     * Send [body] to the customer, and log the row as
     * [NotifyStatus.QUEUED] until the gateway answers.
     */
    data class Reply(
        override val matchType: MatchType,
        override val bundleDescription: String?,
        val flow: TemplateType,
        val body: String,
    ) : PaymentPlan

    /**
     * Log it, send nothing.
     *
     * Covers both "the toggle is off" and "no prices are configured". They are
     * different [matchType]s and the agent reads them differently, but the
     * action — record it, stay quiet — is identical, so they share an arm.
     */
    data class LogOnly(
        override val matchType: MatchType,
        override val bundleDescription: String? = null,
    ) : PaymentPlan
}

/**
 * Turns a parsed payment into a decision. Pure, synchronous, total.
 *
 * This is the join between Phases 2, 3, 4 and 6 — the step every parallel
 * session left as a comment because it needed all four to exist at once:
 *
 * ```
 * parse (2) → classify (3) → apply toggles (6) → render (4) → [enqueue (5b)]
 * ```
 *
 * It takes snapshots rather than caches so it can't do I/O even by accident,
 * which is CLAUDE.md constraint 5 made structural rather than promised. The
 * caller awaits the caches; this decides.
 */
object PaymentPlanner {

    fun plan(
        payment: MpesaPayment,
        rules: RuleSnapshot,
        templates: TemplateSnapshot,
        toggles: NotificationToggles,
    ): PaymentPlan {
        val outcome = rules.classify(payment.amount)
        val decision = decideReply(outcome, toggles)

        return when (decision) {
            // Not a failure and not a suppression — the app simply isn't set up.
            // Logged so the agent can see the app noticed the payment; the
            // dashboard turns these into a "add your prices" prompt.
            is ReplyDecision.NoRulesConfigured ->
                PaymentPlan.LogOnly(MatchType.NO_RULES_CONFIGURED)

            is ReplyDecision.Suppressed ->
                PaymentPlan.LogOnly(
                    matchType = decision.flow.toMatchType(),
                    // Named even though nothing is sent: the agent asking "why
                    // didn't my customer get a confirmation?" wants to see that
                    // we knew exactly which bundle they bought.
                    bundleDescription = (outcome as? MatchOutcome.Matched)
                        ?.rule?.bundleDescription,
                )

            is ReplyDecision.Send -> {
                val rule = decision.matchedRule
                val values = when (decision.flow) {
                    TemplateType.MATCHED -> TemplateEngine.matchedValues(
                        name = payment.senderName,
                        amount = payment.amount,
                        phone = payment.senderPhone,
                        // Non-null exactly when the flow is MATCHED — ReplyDecision
                        // guarantees the pairing, and this is where that guarantee
                        // gets cashed in. A crash here would mean that invariant
                        // broke, and silently sending "{package}" to a customer
                        // would be worse than a loud failure in CI.
                        matchedRule = requireNotNull(rule) {
                            "MATCHED flow with no rule — ReplyDecision invariant broken"
                        },
                    )

                    TemplateType.UNMATCHED -> TemplateEngine.unmatchedValues(
                        name = payment.senderName,
                        amount = payment.amount,
                        phone = payment.senderPhone,
                        activeRules = rules.activeRules,
                    )
                }

                PaymentPlan.Reply(
                    matchType = decision.flow.toMatchType(),
                    bundleDescription = rule?.bundleDescription,
                    flow = decision.flow,
                    body = TemplateEngine.render(templates.forType(decision.flow), values),
                )
            }
        }
    }

    private fun TemplateType.toMatchType(): MatchType = when (this) {
        TemplateType.MATCHED -> MatchType.MATCHED
        TemplateType.UNMATCHED -> MatchType.UNMATCHED
    }
}

/** The log status a plan starts life in. */
val PaymentPlan.initialNotifyStatus: NotifyStatus
    get() = when (this) {
        is PaymentPlan.Reply -> NotifyStatus.QUEUED
        is PaymentPlan.LogOnly -> NotifyStatus.SILENT
    }
