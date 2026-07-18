package com.tricreta.scopesms.domain.rules

/**
 * How often a single customer number can buy a bundle in one day.
 *
 * Safaricom caps some offers to one purchase per number per day; others can
 * be bought repeatedly. The agent records which is which per bundle (see
 * `PricingRuleEntity`, `PriceListCodec`) so it can be surfaced via the
 * `{purchase_limit}` template variable
 * (see [com.tricreta.scopesms.domain.templates.TemplateVariable.PURCHASE_LIMIT]).
 *
 * Stored by [name], so the constants are load-bearing: renaming one is a
 * schema change, not a refactor — same convention as [BundleCategory].
 */
enum class PurchaseLimit {
    ONCE_PER_DAY,
    MULTIPLE_PER_DAY,
    ;

    companion object {
        /**
         * The limit a bundle takes when none is chosen.
         *
         * [MULTIPLE_PER_DAY] — unrestricted — because it preserves today's
         * behaviour for every bundle the agent already entered before this
         * field existed; nothing the client is currently selling is
         * silently treated as once-a-day.
         */
        val DEFAULT = MULTIPLE_PER_DAY

        /**
         * Safe parse for a stored/imported value: an unknown or absent name
         * degrades to [DEFAULT] rather than throwing. Old rows (pre-dating
         * this field) and a hand-edited export file both land here.
         */
        fun fromName(name: String?): PurchaseLimit =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
