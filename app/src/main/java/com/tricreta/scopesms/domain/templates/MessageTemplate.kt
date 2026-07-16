package com.tricreta.scopesms.domain.templates

/**
 * The two independently-toggleable reply flows (CLAUDE.md, "What this app is").
 */
enum class TemplateType {

    /**
     * The payment matched no bundle price. Quote the price list — this is the
     * original pain point the app exists to remove.
     */
    UNMATCHED,

    /**
     * The payment matched a bundle price. Confirm the purchase.
     *
     * Higher volume than [UNMATCHED], and the reason the toggles are separate:
     * a busy day's worth of confirmations under one sender ID is a real
     * deliverability/ban risk with SMS gateways.
     */
    MATCHED,
}

/**
 * A variable the agent can drop into a template body.
 *
 * A closed set, not free-form string keys, so that the Phase 7 UI can offer
 * exactly the right chips per template type and [TemplateEngine.validate] can
 * tell a typo from a real variable before the agent saves it.
 */
enum class TemplateVariable(val token: String) {

    /** Customer's name as M-Pesa reported it. May be absent — see [TemplateEngine]. */
    NAME("{name}"),

    /** What the customer actually paid, e.g. `20` or `20.50`. */
    AMOUNT("{amount}"),

    /** Customer's phone number as M-Pesa reported it. */
    PHONE("{phone}"),

    /** The active price list, one bundle per line. Unmatched flow only. */
    BUNDLE_LIST("{bundle_list}"),

    /** Description of the bundle the payment matched. Matched flow only. */
    PACKAGE("{package}"),

    ;

    companion object {

        /**
         * Which variables carry a real value for a given flow, per BUILD-PLAN
         * Phase 4.
         *
         * The exclusions aren't arbitrary. `{package}` in an unmatched reply has
         * no matched rule to name — by definition, nothing matched.
         * `{bundle_list}` in a purchase confirmation would append the agent's
         * whole price list to every confirmation, paying for extra SMS segments
         * to tell a customer who just bought correctly about prices they didn't
         * ask for.
         */
        fun allowedFor(type: TemplateType): Set<TemplateVariable> = when (type) {
            TemplateType.UNMATCHED -> setOf(NAME, AMOUNT, PHONE, BUNDLE_LIST)
            TemplateType.MATCHED -> setOf(NAME, AMOUNT, PHONE, PACKAGE)
        }

        /** Every `{token}`-shaped run in [body], recognised or not. */
        fun tokensIn(body: String): List<String> =
            TOKEN_PATTERN.findAll(body).map { it.value }.toList()

        fun byToken(token: String): TemplateVariable? = entries.firstOrNull { it.token == token }

        /** Matches `{name}` and also `{nmae}` — recognising typos is the point. */
        private val TOKEN_PATTERN = Regex("""\{[a-zA-Z_][a-zA-Z0-9_]*}""")
    }
}

/**
 * One editable reply body. Exactly one per [TemplateType] exists at any time.
 *
 * @param isDefault true while the body is still [DefaultTemplates]' text. Lets
 *   the UI show "Reset to default" only when it would do something, and lets
 *   onboarding tell "the agent hasn't written this yet" from "the agent wrote
 *   something that happens to be short".
 */
data class MessageTemplate(
    val type: TemplateType,
    val body: String,
    val isDefault: Boolean,
)

/**
 * The text both flows ship with.
 *
 * Rules are deliberately *not* seeded (BUILD-PLAN Phase 3: prompt the agent,
 * assume nothing) — but templates are, and the asymmetry is intentional. An
 * empty rule list is a safe state: [com.tricreta.scopesms.domain.rules.MatchOutcome.NoRulesConfigured]
 * means the app stays quiet. An empty *template* is not safe: the agent turns a
 * toggle on, a customer pays, and the app sends a blank SMS. There is no
 * sensible default price list, but there is a sensible default sentence.
 *
 * Kept short on purpose: every wrapped segment is money out of the agent's
 * pocket (see [SmsSegments]). Both fit one GSM-7 segment once rendered with
 * typical values — the unmatched one plus a short price list will spill to two,
 * which is why the Phase 7 editor shows a live segment count.
 */
object DefaultTemplates {

    val UNMATCHED = """
        Hi {name}, we received Ksh {amount} but it does not match any of our bundle prices. Our current offers:
        {bundle_list}
        Please send the exact amount for the bundle you want.
    """.trimIndent()

    val MATCHED = """
        Hi {name}, thank you for buying {package} for Ksh {amount}. It is being processed now.
    """.trimIndent()

    fun bodyFor(type: TemplateType): String = when (type) {
        TemplateType.UNMATCHED -> UNMATCHED
        TemplateType.MATCHED -> MATCHED
    }

    fun templateFor(type: TemplateType): MessageTemplate =
        MessageTemplate(type = type, body = bodyFor(type), isDefault = true)
}
