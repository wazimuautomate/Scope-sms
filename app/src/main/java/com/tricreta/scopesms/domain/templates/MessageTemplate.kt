package com.tricreta.scopesms.domain.templates

/**
 * The three independently-toggleable reply flows (CLAUDE.md, "What this app is").
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

    /**
     * The payment matched a bundle price, but arrived outside that bundle's
     * purchase window (see [com.tricreta.scopesms.domain.rules.PurchaseWindow]).
     * Reassure the customer their order is noted rather than confirming a
     * purchase Safaricom won't actually fulfil yet, and without asking them to
     * resend — they already paid.
     */
    OFF_WINDOW,
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

    /**
     * Just the first word of [NAME].
     *
     * Added so long M-Pesa names (some run 50 characters) don't push a
     * template into an extra billed SMS segment — the agent can use this
     * instead of the full name. Same missing-value handling as [NAME].
     */
    FIRST_NAME("{first_name}"),

    /**
     * Everything in [NAME] after the first word. Null (renders as a gap,
     * same as a missing [NAME]) when the name is a single word.
     */
    LAST_NAME("{last_name}"),

    /** What the customer actually paid, e.g. `20` or `20.50`. */
    AMOUNT("{amount}"),

    /** Customer's phone number as M-Pesa reported it. */
    PHONE("{phone}"),

    /** The whole active price list, one bundle per line. Unmatched flow only. */
    BUNDLE_LIST("{bundle_list}"),

    /** Active data bundles only, cheapest first. Unmatched flow only. */
    DATA_OFFERS("{data_offers}"),

    /** Active minutes bundles only, cheapest first. Unmatched flow only. */
    MINUTES_OFFERS("{minutes_offers}"),

    /** Active SMS bundles only, cheapest first. Unmatched flow only. */
    SMS_OFFERS("{sms_offers}"),

    /** Description of the bundle the payment matched. Matched flow only. */
    PACKAGE("{package}"),

    /**
     * How often the matched bundle can be bought: "once a day" or "as many
     * times as you like". Matched flow only — parallels [PACKAGE], since it
     * only means something for the bundle actually bought. Safaricom caps
     * some offers to one purchase per number per day; this lets the agent
     * mention that in the confirmation when it applies.
     */
    PURCHASE_LIMIT("{purchase_limit}"),

    /**
     * The matched bundle's purchase window, e.g. "4:00 PM to 10:59 PM" — always
     * non-blank, so the agent can build a sentence around it
     * ("{package} can only be purchased between {purchase_window}.") the same
     * way they would around [PACKAGE]. Off-window flow only: it only means
     * something for the bundle whose window the payment missed.
     */
    PURCHASE_WINDOW("{purchase_window}"),

    /**
     * The M-Pesa transaction code, e.g. `UGFMXB3GR6` — [MpesaPayment.transactionCode]
     * verbatim. Always present (the parser requires it to produce a payment at
     * all), so unlike [NAME]/[PHONE] it never degrades to a gap. Allowed in all
     * three flows: the agent may want to reference "your payment {mpesa_code}"
     * in any reply, and there's no flow it doesn't make sense in the way
     * `{package}` doesn't make sense in an unmatched reply.
     */
    MPESA_CODE("{mpesa_code}"),

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
            TemplateType.UNMATCHED ->
                setOf(
                    NAME, FIRST_NAME, LAST_NAME, AMOUNT, PHONE, MPESA_CODE,
                    BUNDLE_LIST, DATA_OFFERS, MINUTES_OFFERS, SMS_OFFERS,
                )
            TemplateType.MATCHED ->
                setOf(NAME, FIRST_NAME, LAST_NAME, AMOUNT, PHONE, MPESA_CODE, PACKAGE, PURCHASE_LIMIT)
            TemplateType.OFF_WINDOW ->
                setOf(NAME, FIRST_NAME, LAST_NAME, AMOUNT, PHONE, MPESA_CODE, PACKAGE, PURCHASE_WINDOW)
        }

        /** Every `{token}`-shaped run in [body], recognised or not. */
        fun tokensIn(body: String): List<String> =
            TOKEN_PATTERN.findAll(body).map { it.value }.toList()

        fun byToken(token: String): TemplateVariable? = entries.firstOrNull { it.token == token }

        /**
         * Matches `{name}` and also `{nmae}` — recognising typos is the point.
         *
         * The closing `}` MUST stay escaped (`\}`): Android's ICU regex engine
         * throws PatternSyntaxException on a lone unescaped `}` and force-closed a
         * screen at class-init on Samsung/Android 14; the desktop JVM tolerates it,
         * so CI never sees it. See v1.0.3.
         */
        private val TOKEN_PATTERN = Regex("""\{[a-zA-Z_][a-zA-Z0-9_]*\}""")
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
 * The text all three flows ship with.
 *
 * Rules are deliberately *not* seeded (BUILD-PLAN Phase 3: prompt the agent,
 * assume nothing) — but templates are, and the asymmetry is intentional. An
 * empty rule list is a safe state: [com.tricreta.scopesms.domain.rules.MatchOutcome.NoRulesConfigured]
 * means the app stays quiet. An empty *template* is not safe: the agent turns a
 * toggle on, a customer pays, and the app sends a blank SMS. There is no
 * sensible default price list, but there is a sensible default sentence.
 *
 * Kept short on purpose: every wrapped segment is money out of the agent's
 * pocket (see [SmsSegments]). Each fits one GSM-7 segment once rendered with
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

    val OFF_WINDOW = """
        Hi {name}, thank you for sending Ksh {amount}. {package} can only be purchased between {purchase_window}. Your order is noted - you will receive it automatically once that window opens, no need to resend.
    """.trimIndent()

    fun bodyFor(type: TemplateType): String = when (type) {
        TemplateType.UNMATCHED -> UNMATCHED
        TemplateType.MATCHED -> MATCHED
        TemplateType.OFF_WINDOW -> OFF_WINDOW
    }

    fun templateFor(type: TemplateType): MessageTemplate =
        MessageTemplate(type = type, body = bodyFor(type), isDefault = true)
}
