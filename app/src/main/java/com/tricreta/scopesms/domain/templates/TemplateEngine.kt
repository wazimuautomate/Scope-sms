package com.tricreta.scopesms.domain.templates

import com.tricreta.scopesms.domain.money.KshAmount
import com.tricreta.scopesms.domain.rules.BundleCategory
import com.tricreta.scopesms.domain.rules.PricingRule

/** What's wrong with a template body, for the editor to show before saving. */
data class TemplateValidation(
    /** `{nmae}` — token-shaped, not a real variable. Almost always a typo. */
    val unknownTokens: List<String>,
    /** Real variables that carry no value in this flow, e.g. `{package}` in an unmatched reply. */
    val disallowedVariables: List<TemplateVariable>,
) {
    val isValid: Boolean get() = unknownTokens.isEmpty() && disallowedVariables.isEmpty()
}

/**
 * Substitutes `{variable}` tokens into a template body. Shared by both flows.
 *
 * Pure and synchronous — it runs on the receive path, which CLAUDE.md
 * constraint 5 keeps free of I/O.
 *
 * ## The rule that shapes this class
 * Whatever comes out of here is sent to a paying customer under the agent's own
 * sender ID. There is no draft step and no human in the loop. So the engine
 * never emits a token: a customer receiving *"Hi {name}, we received Ksh 20"*
 * makes the agent look broken, and BUILD-PLAN Phase 4 requires no crash when a
 * variable is missing. Every path here ends in readable text.
 */
object TemplateEngine {

    /**
     * Renders [body], replacing each recognised token with its value.
     *
     * Two distinct kinds of "missing" are handled differently, on purpose:
     *
     * - **A recognised variable with no value** — `{name}` when M-Pesa didn't
     *   report a name — becomes empty, and the surrounding text is tidied so the
     *   gap doesn't show ("Hi {name}, thanks" → "Hi, thanks", not "Hi , thanks").
     *   The message still reads like a person wrote it.
     * - **An unrecognised token** — `{nmae}` — is left exactly as typed. It is
     *   the agent's typo, and deleting it silently would hide the mistake at the
     *   one moment they could still catch it: the Phase 7 preview renders through
     *   this method, so a stray `{nmae}` shows up there in full view. Templates
     *   are also validated on save ([validate]), so this is a backstop, not the
     *   only line of defence.
     *
     * Values are inserted literally — a customer named `A$AP` or a bundle
     * described as `2GB $ 50` cannot be misread as a regex backreference.
     */
    fun render(body: String, values: Map<TemplateVariable, String?>): String {
        var leftAGap = false

        val rendered = TOKEN_PATTERN.replace(body) { match ->
            val variable = TemplateVariable.byToken(match.value)
                ?: return@replace match.value // unknown token: leave visible

            val value = values[variable]?.trim().orEmpty()
            if (value.isEmpty()) leftAGap = true
            value
        }

        // Only tidy when something actually rendered empty. On the normal path
        // the agent's text goes out byte-for-byte as they typed it — including
        // any spacing they meant — and the preview they approved is what the
        // customer gets.
        return if (leftAGap) tidy(rendered) else rendered
    }

    /** Renders a template. See [render]. */
    fun render(template: MessageTemplate, values: Map<TemplateVariable, String?>): String =
        render(template.body, values)

    /**
     * Checks a body against a flow's allowed variables, for the editor to
     * surface *before* the agent saves — not at 2am when a customer pays.
     */
    fun validate(body: String, type: TemplateType): TemplateValidation {
        val allowed = TemplateVariable.allowedFor(type)
        val tokens = TemplateVariable.tokensIn(body)

        return TemplateValidation(
            unknownTokens = tokens.filter { TemplateVariable.byToken(it) == null }.distinct(),
            disallowedVariables = tokens.mapNotNull(TemplateVariable::byToken)
                .filter { it !in allowed }
                .distinct(),
        )
    }

    /**
     * Values for the unmatched flow: the customer paid an amount that buys
     * nothing, so quote the price list.
     *
     * [name] and [phone] are nullable because the parser may not find them in a
     * given SMS variant; both degrade to a gap rather than a token.
     */
    fun unmatchedValues(
        name: String?,
        amount: KshAmount,
        phone: String?,
        activeRules: List<PricingRule>,
    ): Map<TemplateVariable, String?> = mapOf(
        TemplateVariable.NAME to name,
        TemplateVariable.AMOUNT to amount.format(),
        TemplateVariable.PHONE to phone,
        TemplateVariable.BUNDLE_LIST to BundleListRenderer.render(activeRules),
        TemplateVariable.DATA_OFFERS to BundleListRenderer.render(activeRules, BundleCategory.DATA),
        TemplateVariable.MINUTES_OFFERS to BundleListRenderer.render(activeRules, BundleCategory.MINUTES),
        TemplateVariable.SMS_OFFERS to BundleListRenderer.render(activeRules, BundleCategory.SMS),
    )

    /** Values for the matched flow: confirm what [matchedRule] says they bought. */
    fun matchedValues(
        name: String?,
        amount: KshAmount,
        phone: String?,
        matchedRule: PricingRule,
    ): Map<TemplateVariable, String?> = mapOf(
        TemplateVariable.NAME to name,
        TemplateVariable.AMOUNT to amount.format(),
        TemplateVariable.PHONE to phone,
        TemplateVariable.PACKAGE to matchedRule.bundleDescription,
    )

    /**
     * Closes the holes an empty substitution leaves: doubled spaces, a space
     * stranded before punctuation, trailing spaces.
     *
     * Works line by line so it can collapse horizontal runs without touching
     * newlines — `{bundle_list}` renders as one line per bundle, and flattening
     * that would turn the price list into an unreadable smear.
     */
    private fun tidy(text: String): String = text
        .lineSequence()
        .map { line ->
            line.replace(HORIZONTAL_RUN, " ")
                .replace(SPACE_BEFORE_PUNCTUATION, "$1")
                .trimEnd()
        }
        .joinToString("\n")
        .replace(BLANK_LINE_RUN, "\n\n")
        .trim()

    // The closing brace MUST stay escaped as `\}`. Android's ICU-backed regex
    // engine (Samsung/Android 14+) throws PatternSyntaxException on a lone
    // unescaped `}`, which crashed this class' static init the instant a screen
    // touched it; the desktop JVM (Robolectric/CI) tolerates it, so no unit test
    // catches a regression here. See v1.0.3.
    private val TOKEN_PATTERN = Regex("""\{[a-zA-Z_][a-zA-Z0-9_]*\}""")
    private val HORIZONTAL_RUN = Regex("""[ \t]{2,}""")
    private val SPACE_BEFORE_PUNCTUATION = Regex(""" +([,.!?;:])""")
    private val BLANK_LINE_RUN = Regex("""\n{3,}""")
}

/**
 * Renders the active price list for `{bundle_list}` and the per-category
 * `{data_offers}` / `{minutes_offers}` / `{sms_offers}` variables.
 *
 * Cheapest first — the agent's customers are choosing on price, and it keeps
 * the same amount in the same place every time.
 */
object BundleListRenderer {

    /**
     * @param category null renders every active bundle (`{bundle_list}`); a
     *   category renders only that kind. An empty result (no active bundles in
     *   that category) is a blank string, which [TemplateEngine.render] tidies
     *   into a clean gap rather than a dangling label.
     */
    fun render(activeRules: List<PricingRule>, category: BundleCategory? = null): String = activeRules
        .filter { category == null || it.category == category }
        .sortedBy { it.amount }
        .joinToString("\n") { "Ksh ${it.amount.format()} - ${it.bundleDescription}" }
}
