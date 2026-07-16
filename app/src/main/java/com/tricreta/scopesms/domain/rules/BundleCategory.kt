package com.tricreta.scopesms.domain.rules

/**
 * What a bundle sells: data, voice minutes, or SMS.
 *
 * Lets the agent group their price list and quote only a chosen category on the
 * unmatched reply, via the `{data_offers}` / `{minutes_offers}` / `{sms_offers}`
 * template variables (see
 * [com.tricreta.scopesms.domain.templates.BundleListRenderer]).
 *
 * Stored by [name] (see `PricingRuleEntity` + the Room migration), so the
 * constants are load-bearing: renaming one is a schema change, not a refactor.
 */
enum class BundleCategory {
    DATA,
    MINUTES,
    SMS,
    ;

    companion object {
        /** The category a bundle takes when none is chosen — the most common kind. */
        val DEFAULT = DATA

        /**
         * Safe parse for a stored/imported value: an unknown or absent name
         * degrades to [DEFAULT] rather than throwing. Old rows (pre-category) and
         * a hand-edited file both land here.
         */
        fun fromName(name: String?): BundleCategory =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
