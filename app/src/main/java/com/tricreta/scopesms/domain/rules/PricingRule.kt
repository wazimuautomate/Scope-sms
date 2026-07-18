package com.tricreta.scopesms.domain.rules

import com.tricreta.scopesms.domain.money.KshAmount

/**
 * One line of the agent's price list: "Ksh 50 buys 2GB Weekly".
 *
 * The domain model, not the Room row — `data/rules/PricingRuleEntity` is the
 * stored shape. They are kept separate so that `domain/` stays free of Room
 * types and testable on the JVM without Robolectric (see `domain/README.md`).
 *
 * @param isActive false hides the rule from matching without deleting it, so
 *   the agent can pause a bundle they've stopped selling and bring it back
 *   later without retyping it.
 * @param category which kind of bundle this is (data/minutes/sms), so the
 *   unmatched reply can quote one category at a time. Defaults to
 *   [BundleCategory.DEFAULT]; pre-category rows migrate to it.
 * @param purchaseLimit how often one customer can buy this bundle in a day —
 *   Safaricom restricts some offers to once per number per day. Defaults to
 *   [PurchaseLimit.DEFAULT]; pre-existing rows migrate to it.
 */
data class PricingRule(
    val id: Long,
    val amount: KshAmount,
    val bundleDescription: String,
    val isActive: Boolean = true,
    val category: BundleCategory = BundleCategory.DEFAULT,
    val purchaseLimit: PurchaseLimit = PurchaseLimit.DEFAULT,
)

/**
 * What the rules engine concluded about an incoming payment.
 *
 * This is deliberately a three-way result rather than a nullable
 * `PricingRule?`, and that distinction is load-bearing.
 *
 * "No rule matched Ksh 35" and "the agent hasn't entered any prices yet" are
 * both `null` in a nullable API, and treating them the same is an outright bug:
 * on a fresh install with an empty rule list, *every* payment fails to match, so
 * every paying customer would be texted a price list that renders empty. The
 * agent's first day using the app would be spent apologising to customers.
 * BUILD-PLAN Phase 3 says the app must prompt for prices before it does
 * anything — [NoRulesConfigured] is how that instruction is made unmissable
 * rather than left to a comment.
 *
 * Callers on the decide path (Phase 5b/6) must handle all three arms; the
 * compiler enforces it via `when`.
 */
sealed interface MatchOutcome {

    /**
     * The price list is empty. Send nothing — this is a setup state, not a
     * customer who paid the wrong amount.
     */
    data object NoRulesConfigured : MatchOutcome

    /** The amount is a known bundle price. Matched-flow candidate. */
    data class Matched(val rule: PricingRule) : MatchOutcome

    /** Rules exist and none has this amount. Unmatched-flow candidate. */
    data object Unmatched : MatchOutcome
}

/**
 * An immutable, indexed view of the price list, built once per change and read
 * many times.
 *
 * CLAUDE.md constraint 5 requires the receive path to survive ~10 SMS arriving
 * in 1–3 seconds, with the match decided in-memory rather than by a Room query
 * per message. This class is that in-memory form: construction does the sorting
 * and indexing, [classify] is a single hash lookup, and instances are shared
 * freely across threads because nothing in them can change after construction.
 *
 * Build via [from]; [RuleCache] owns the lifecycle.
 */
class RuleSnapshot private constructor(
    /** Every rule, active or not, in the order stored. For the UI. */
    val allRules: List<PricingRule>,
    /** Active rules only, cheapest first. Renders `{bundle_list}`. */
    val activeRules: List<PricingRule>,
    private val byAmount: Map<KshAmount, PricingRule>,
    /**
     * Amounts carrying more than one active rule.
     *
     * Empty in the normal case. Exposed so the rules screen (Phase 7) can warn
     * the agent that two bundles share a price and only one of them will ever
     * be quoted — the engine resolves it deterministically (see [from]), but
     * silently, and silently is not good enough for a decision that puts words
     * in the agent's mouth.
     */
    val duplicateAmounts: Set<KshAmount>,
) {

    /** True when the agent has no usable price list — see [MatchOutcome.NoRulesConfigured]. */
    val hasNoActiveRules: Boolean get() = activeRules.isEmpty()

    /**
     * Classifies a payment. O(1): one hash lookup, no I/O, safe from any thread.
     */
    fun classify(amount: KshAmount): MatchOutcome = when {
        activeRules.isEmpty() -> MatchOutcome.NoRulesConfigured
        else -> byAmount[amount]?.let(MatchOutcome::Matched) ?: MatchOutcome.Unmatched
    }

    companion object {
        val EMPTY = RuleSnapshot(emptyList(), emptyList(), emptyMap(), emptySet())

        /**
         * Indexes a rule list.
         *
         * **Duplicate amounts resolve to the highest id — the most recently
         * added rule wins.** The DB permits duplicates on purpose: a unique
         * index would reject the agent's data entry mid-flow, and it couldn't
         * express the rule that actually matters ("unique among *active*"),
         * because Room's `@Index` has no partial-index/`WHERE` support.
         *
         * Most-recent-wins is the reading of the agent's intent that matches
         * what they did: someone who re-prices Ksh 50 from "1.5GB" to "2GB" by
         * adding a new row rather than editing the old one means the new one.
         * The alternative — oldest-wins — would make their correction silently
         * do nothing, which is the worse failure. Either way the collision is
         * reported through [duplicateAmounts] instead of being swallowed.
         */
        fun from(rules: List<PricingRule>): RuleSnapshot {
            val active = rules.filter { it.isActive }

            val byAmount = LinkedHashMap<KshAmount, PricingRule>(active.size)
            val duplicates = LinkedHashSet<KshAmount>()
            // Ascending id, so a later put() overwrites an earlier one.
            for (rule in active.sortedBy { it.id }) {
                if (byAmount.put(rule.amount, rule) != null) duplicates += rule.amount
            }

            return RuleSnapshot(
                allRules = rules,
                activeRules = active.sortedBy { it.amount },
                byAmount = byAmount,
                duplicateAmounts = duplicates,
            )
        }
    }
}
