package com.tricreta.scopesms.domain.templates

import com.google.common.truth.Truth.assertThat
import com.tricreta.scopesms.domain.money.KshAmount
import com.tricreta.scopesms.domain.rules.BundleCategory
import com.tricreta.scopesms.domain.rules.PricingRule
import com.tricreta.scopesms.domain.rules.PurchaseLimit
import org.junit.Test

/**
 * BUILD-PLAN Phase 4's exit criteria: correct substitution for both template
 * types, and no crash on a missing variable.
 *
 * Everything rendered here goes straight to a paying customer under the agent's
 * sender ID, with no draft step and no human reading it first.
 */
class TemplateEngineTest {

    private fun rule(id: Long, shillings: Long, description: String) =
        PricingRule(id, KshAmount.ofShillings(shillings), description)

    private val priceList = listOf(
        rule(1, 20, "1GB Daily"),
        rule(2, 50, "2GB Weekly"),
    )

    // --- Unmatched flow -----------------------------------------------------

    @Test
    fun `unmatched template substitutes every variable`() {
        val body = "Hi {name}, we got Ksh {amount} from {phone}. Offers:\n{bundle_list}"

        val rendered = TemplateEngine.render(
            body,
            TemplateEngine.unmatchedValues(
                name = "Skycope Bonke",
                amount = KshAmount.ofShillings(35),
                phone = "254700000000",
                activeRules = priceList,
            ),
        )

        assertThat(rendered).isEqualTo(
            "Hi Skycope Bonke, we got Ksh 35 from 254700000000. Offers:\n" +
                "Ksh 20 - 1GB Daily\n" +
                "Ksh 50 - 2GB Weekly",
        )
    }

    @Test
    fun `bundle list renders cheapest first regardless of input order`() {
        val rendered = TemplateEngine.render(
            "{bundle_list}",
            TemplateEngine.unmatchedValues(
                name = "A",
                amount = KshAmount.ofShillings(35),
                phone = "254700000000",
                activeRules = listOf(rule(1, 100, "5GB"), rule(2, 20, "1GB"), rule(3, 50, "2GB")),
            ),
        )

        assertThat(rendered).isEqualTo("Ksh 20 - 1GB\nKsh 50 - 2GB\nKsh 100 - 5GB")
    }

    // --- Per-category offers ------------------------------------------------

    private fun rule(id: Long, shillings: Long, description: String, category: BundleCategory) =
        PricingRule(id, KshAmount.ofShillings(shillings), description, category = category)

    private val mixedList = listOf(
        rule(1, 50, "2GB Weekly", BundleCategory.DATA),
        rule(2, 20, "1GB Daily", BundleCategory.DATA),
        rule(3, 30, "50 mins", BundleCategory.MINUTES),
        rule(4, 10, "200 SMS", BundleCategory.SMS),
    )

    @Test
    fun `data_offers lists only data bundles, cheapest first`() {
        val rendered = TemplateEngine.render(
            "{data_offers}",
            TemplateEngine.unmatchedValues("A", KshAmount.ofShillings(35), "0700", mixedList),
        )
        assertThat(rendered).isEqualTo("Ksh 20 - 1GB Daily\nKsh 50 - 2GB Weekly")
    }

    @Test
    fun `minutes_offers and sms_offers each list only their own category`() {
        val values = TemplateEngine.unmatchedValues("A", KshAmount.ofShillings(35), "0700", mixedList)
        assertThat(TemplateEngine.render("{minutes_offers}", values)).isEqualTo("Ksh 30 - 50 mins")
        assertThat(TemplateEngine.render("{sms_offers}", values)).isEqualTo("Ksh 10 - 200 SMS")
    }

    @Test
    fun `bundle_list still lists every category`() {
        val rendered = TemplateEngine.render(
            "{bundle_list}",
            TemplateEngine.unmatchedValues("A", KshAmount.ofShillings(35), "0700", mixedList),
        )
        // Cheapest first across all categories.
        assertThat(rendered).isEqualTo(
            "Ksh 10 - 200 SMS\nKsh 20 - 1GB Daily\nKsh 30 - 50 mins\nKsh 50 - 2GB Weekly",
        )
    }

    @Test
    fun `a category with no bundles renders as a gap, never a raw token`() {
        val dataOnly = listOf(rule(1, 20, "1GB", BundleCategory.DATA))
        val rendered = TemplateEngine.render(
            "Our minutes:\n{minutes_offers}\nThanks.",
            TemplateEngine.unmatchedValues("A", KshAmount.ofShillings(35), "0700", dataOnly),
        )
        assertThat(rendered).doesNotContain("{minutes_offers}")
        assertThat(rendered).doesNotContain("Ksh")
        assertThat(rendered).contains("Our minutes:")
        assertThat(rendered).contains("Thanks.")
    }

    @Test
    fun `per-category offers are allowed in the unmatched flow only`() {
        val perCategory = listOf(
            TemplateVariable.DATA_OFFERS,
            TemplateVariable.MINUTES_OFFERS,
            TemplateVariable.SMS_OFFERS,
        )
        assertThat(TemplateVariable.allowedFor(TemplateType.UNMATCHED)).containsAtLeastElementsIn(perCategory)
        assertThat(TemplateVariable.allowedFor(TemplateType.MATCHED)).containsNoneIn(perCategory)
    }

    // --- Name splitting (long M-Pesa names push templates into extra segments) -

    @Test
    fun `first_name and last_name split a two-word name`() {
        val values = TemplateEngine.unmatchedValues(
            name = "Skycope Bonke",
            amount = KshAmount.ofShillings(35),
            phone = "254700000000",
            activeRules = priceList,
        )

        assertThat(TemplateEngine.render("{first_name}", values)).isEqualTo("Skycope")
        assertThat(TemplateEngine.render("{last_name}", values)).isEqualTo("Bonke")
    }

    @Test
    fun `first_name and last_name split the first word from everything after it`() {
        // A middle name or long name stays together as the "last name" rather
        // than being dropped — the point is a shorter greeting, not a lossy one.
        val values = TemplateEngine.matchedValues(
            name = "John Michael Doe",
            amount = KshAmount.ofShillings(50),
            phone = "254700000000",
            matchedRule = rule(2, 50, "2GB Weekly"),
        )

        assertThat(TemplateEngine.render("{first_name}", values)).isEqualTo("John")
        assertThat(TemplateEngine.render("{last_name}", values)).isEqualTo("Michael Doe")
    }

    @Test
    fun `a single-word name leaves last_name as a clean gap, not a raw token`() {
        val rendered = TemplateEngine.render(
            "Hi {first_name} {last_name}, thanks.",
            TemplateEngine.matchedValues(
                name = "Skycope",
                amount = KshAmount.ofShillings(50),
                phone = "254700000000",
                matchedRule = rule(2, 50, "2GB Weekly"),
            ),
        )

        assertThat(rendered).doesNotContain("{last_name}")
        assertThat(rendered).isEqualTo("Hi Skycope, thanks.")
    }

    @Test
    fun `a null name leaves both first_name and last_name as gaps`() {
        val rendered = TemplateEngine.render(
            "Hi {first_name} {last_name}, thanks.",
            TemplateEngine.matchedValues(
                name = null,
                amount = KshAmount.ofShillings(50),
                phone = "254700000000",
                matchedRule = rule(2, 50, "2GB Weekly"),
            ),
        )

        assertThat(rendered).isEqualTo("Hi, thanks.")
    }

    @Test
    fun `name variables are allowed in both flows`() {
        val names = setOf(TemplateVariable.NAME, TemplateVariable.FIRST_NAME, TemplateVariable.LAST_NAME)
        assertThat(TemplateVariable.allowedFor(TemplateType.UNMATCHED)).containsAtLeastElementsIn(names)
        assertThat(TemplateVariable.allowedFor(TemplateType.MATCHED)).containsAtLeastElementsIn(names)
    }

    // --- Purchase limit (once/day vs multiple/day) --------------------------

    @Test
    fun `purchase_limit describes an once-a-day bundle`() {
        val values = TemplateEngine.matchedValues(
            name = "A",
            amount = KshAmount.ofShillings(50),
            phone = "0700",
            matchedRule = rule(2, 50, "2GB Weekly").copy(purchaseLimit = PurchaseLimit.ONCE_PER_DAY),
        )

        assertThat(TemplateEngine.render("{purchase_limit}", values)).isEqualTo("once a day")
    }

    @Test
    fun `purchase_limit describes an unrestricted bundle`() {
        val values = TemplateEngine.matchedValues(
            name = "A",
            amount = KshAmount.ofShillings(50),
            phone = "0700",
            matchedRule = rule(2, 50, "2GB Weekly").copy(purchaseLimit = PurchaseLimit.MULTIPLE_PER_DAY),
        )

        assertThat(TemplateEngine.render("{purchase_limit}", values)).isEqualTo("as many times as you like")
    }

    @Test
    fun `purchase_limit is allowed in the matched flow only`() {
        assertThat(TemplateVariable.allowedFor(TemplateType.MATCHED))
            .contains(TemplateVariable.PURCHASE_LIMIT)
        assertThat(TemplateVariable.allowedFor(TemplateType.UNMATCHED))
            .doesNotContain(TemplateVariable.PURCHASE_LIMIT)
    }

    // --- Matched flow -------------------------------------------------------

    @Test
    fun `matched template substitutes the purchased package`() {
        val body = "Hi {name}, thank you for buying {package} for Ksh {amount}."

        val rendered = TemplateEngine.render(
            body,
            TemplateEngine.matchedValues(
                name = "Skycope Bonke",
                amount = KshAmount.ofShillings(50),
                phone = "254700000000",
                matchedRule = rule(2, 50, "2GB Weekly"),
            ),
        )

        assertThat(rendered).isEqualTo("Hi Skycope Bonke, thank you for buying 2GB Weekly for Ksh 50.")
    }

    // --- Missing variables: the "no crash" criterion -------------------------

    @Test
    fun `a missing name leaves readable text, never a raw token`() {
        // The parser may not find a name in an SMS variant. The customer must
        // not receive "Hi {name},".
        val rendered = TemplateEngine.render(
            "Hi {name}, thank you for buying {package}.",
            TemplateEngine.matchedValues(
                name = null,
                amount = KshAmount.ofShillings(50),
                phone = "254700000000",
                matchedRule = rule(2, 50, "2GB Weekly"),
            ),
        )

        assertThat(rendered).doesNotContain("{name}")
        assertThat(rendered).isEqualTo("Hi, thank you for buying 2GB Weekly.")
    }

    @Test
    fun `a blank name is treated as missing`() {
        val rendered = TemplateEngine.render(
            "Hi {name}, thanks.",
            mapOf(TemplateVariable.NAME to "   "),
        )

        assertThat(rendered).isEqualTo("Hi, thanks.")
    }

    @Test
    fun `an empty substitution mid-sentence does not leave a double space`() {
        val rendered = TemplateEngine.render(
            "Dear {name} your payment is received.",
            mapOf(TemplateVariable.NAME to null),
        )

        assertThat(rendered).isEqualTo("Dear your payment is received.")
    }

    @Test
    fun `a variable with no entry in the map at all renders empty`() {
        // Not merely null — absent. A caller that forgets a key must not ship a
        // token to a customer.
        val rendered = TemplateEngine.render("Hi {name}, you paid {amount}.", emptyMap())

        assertThat(rendered).isEqualTo("Hi, you paid.")
    }

    @Test
    fun `rendering with no variables at all is unchanged`() {
        val body = "Thanks for your payment."

        assertThat(TemplateEngine.render(body, emptyMap())).isEqualTo(body)
    }

    // --- Unknown tokens -----------------------------------------------------

    @Test
    fun `an unknown token is left visible rather than silently dropped`() {
        // The agent's typo. The Phase 7 preview renders through this method, so
        // leaving it is what lets them see and fix it. Deleting it would hide
        // the mistake at the only moment it's catchable.
        val rendered = TemplateEngine.render(
            "Hi {nmae}, you paid {amount}.",
            mapOf(TemplateVariable.AMOUNT to "20"),
        )

        assertThat(rendered).isEqualTo("Hi {nmae}, you paid 20.")
    }

    // --- Injection-shaped values --------------------------------------------

    @Test
    fun `values containing regex replacement syntax are inserted literally`() {
        // A customer named "A$AP" or a bundle described with "$1" must not be
        // read as a backreference.
        val rendered = TemplateEngine.render(
            "Hi {name}, you bought {package}.",
            mapOf(
                TemplateVariable.NAME to "A\$AP Rocky",
                TemplateVariable.PACKAGE to "2GB \\ \$1 Weekly",
            ),
        )

        assertThat(rendered).isEqualTo("Hi A\$AP Rocky, you bought 2GB \\ \$1 Weekly.")
    }

    @Test
    fun `a value that looks like a token is not re-substituted`() {
        // Otherwise a customer could name themselves "{bundle_list}" and have
        // the agent's whole price list expanded into their own reply.
        val rendered = TemplateEngine.render(
            "Hi {name}.",
            mapOf(TemplateVariable.NAME to "{bundle_list}", TemplateVariable.BUNDLE_LIST to "Ksh 20 - 1GB"),
        )

        assertThat(rendered).isEqualTo("Hi {bundle_list}.")
    }

    // --- Validation ---------------------------------------------------------

    @Test
    fun `validation accepts each flow's own variables`() {
        assertThat(
            TemplateEngine.validate(DefaultTemplates.UNMATCHED, TemplateType.UNMATCHED).isValid,
        ).isTrue()
        assertThat(
            TemplateEngine.validate(DefaultTemplates.MATCHED, TemplateType.MATCHED).isValid,
        ).isTrue()
    }

    @Test
    fun `validation flags a typo`() {
        val result = TemplateEngine.validate("Hi {nmae}", TemplateType.MATCHED)

        assertThat(result.isValid).isFalse()
        assertThat(result.unknownTokens).containsExactly("{nmae}")
    }

    @Test
    fun `validation flags a variable the flow cannot fill`() {
        // {package} in an unmatched reply names a bundle that by definition
        // didn't match.
        val result = TemplateEngine.validate("Hi {name}, you bought {package}", TemplateType.UNMATCHED)

        assertThat(result.isValid).isFalse()
        assertThat(result.disallowedVariables).containsExactly(TemplateVariable.PACKAGE)
    }

    @Test
    fun `validation flags bundle_list in a matched confirmation`() {
        // Allowed to render, but it would append the whole price list to every
        // confirmation and bill the agent for the extra segments.
        val result = TemplateEngine.validate("Thanks!\n{bundle_list}", TemplateType.MATCHED)

        assertThat(result.disallowedVariables).containsExactly(TemplateVariable.BUNDLE_LIST)
    }

    @Test
    fun `validation reports each problem once`() {
        val result = TemplateEngine.validate("{nmae} {nmae} {name}", TemplateType.MATCHED)

        assertThat(result.unknownTokens).containsExactly("{nmae}")
    }

    // --- Defaults -----------------------------------------------------------

    @Test
    fun `shipped defaults render cleanly with realistic values`() {
        val rendered = TemplateEngine.render(
            DefaultTemplates.UNMATCHED,
            TemplateEngine.unmatchedValues(
                name = "Skycope Bonke",
                amount = KshAmount.ofShillings(35),
                phone = "254700000000",
                activeRules = priceList,
            ),
        )

        assertThat(rendered).doesNotContain("{")
        assertThat(rendered).contains("Skycope Bonke")
        assertThat(rendered).contains("Ksh 20 - 1GB Daily")
    }

    @Test
    fun `shipped matched default renders cleanly`() {
        val rendered = TemplateEngine.render(
            DefaultTemplates.MATCHED,
            TemplateEngine.matchedValues(
                name = "Skycope Bonke",
                amount = KshAmount.ofShillings(50),
                phone = "254700000000",
                matchedRule = rule(2, 50, "2GB Weekly"),
            ),
        )

        assertThat(rendered).doesNotContain("{")
        assertThat(rendered).contains("2GB Weekly")
    }
}
