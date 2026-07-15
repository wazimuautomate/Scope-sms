package com.scopesms.autoreply.domain.money

/**
 * A Kenyan shilling amount, held as whole cents.
 *
 * ## Why cents, and why this exists at all
 * The rules engine decides whether a customer's payment matches a bundle price
 * by testing amounts for **equality**. That single fact rules out `Double` and
 * `Float`: `20.10 + 0.20 != 20.30` in binary floating point, and a near-miss
 * here means the agent's customer is told the wrong price — or is told nothing
 * at all when they should have been. Integer cents make equality exact.
 *
 * It also rules out "just use `Int` shillings". M-Pesa states amounts to two
 * decimals (`Ksh20.00`, `Ksh1300.22`), and while Bingwa bundle prices are whole
 * shillings in practice, a customer can and will send `Ksh20.50`. Truncating
 * that to `20` would match the Ksh20 bundle and confirm a purchase the customer
 * did not make. Rounding it to `21` would be just as wrong. Cents represent what
 * actually arrived, and a 20.50 payment then correctly matches nothing.
 *
 * ## Convention for other phases — please read
 * This is the canonical money type across the app. Phase 2's parser should
 * produce a [KshAmount] (see [parse]) rather than a number, and Phase 5b/8
 * should carry it rather than re-deriving one. Room stores the raw [cents] as a
 * `Long` column; the conversion happens in `data/`, so Room never sees this
 * type and no `@TypeConverter` is needed.
 *
 * It is a `value class`: at runtime this is a plain `long` in the common case,
 * so nothing is paid for the type safety on the SMS hot path.
 */
@JvmInline
value class KshAmount(val cents: Long) : Comparable<KshAmount> {

    override fun compareTo(other: KshAmount): Int = cents.compareTo(other.cents)

    /**
     * Renders for display and for SMS bodies: `20`, `20.50`, `1300.22`.
     *
     * Trailing `.00` is dropped deliberately. Every rendered amount goes into an
     * SMS the agent pays for per segment, "Ksh 20" is how a Kenyan customer
     * reads a price anyway, and bundle prices are whole shillings almost always
     * — so the decimals would be pure noise on the common path.
     */
    fun format(): String {
        val whole = cents / 100
        val fraction = (cents % 100).toInt()
        return if (fraction == 0) whole.toString() else "%d.%02d".format(whole, fraction)
    }

    companion object {
        val ZERO = KshAmount(0)

        fun ofShillings(shillings: Long): KshAmount = KshAmount(shillings * 100)

        /**
         * Parses an amount as it appears in an M-Pesa SMS, or returns null if
         * the text isn't one.
         *
         * Accepts an optional `Ksh`/`KES` prefix, thousands separators, and zero
         * to two decimal places: `20`, `20.00`, `Ksh20.00`, `1,300.22`, `20.5`
         * (→ 20.50, since `.5` is five *tenths*).
         *
         * Returns null rather than throwing, and null rather than guessing.
         * Phase 2 feeds this whatever a regex pulled out of a real SMS, and a
         * malformed amount must degrade to "this message isn't a payment I
         * understand" — never to a wrong number that gets matched against a
         * bundle price. Three or more decimal places is not an M-Pesa amount, so
         * it is rejected too rather than silently truncated.
         */
        fun parse(raw: String): KshAmount? {
            val match = PATTERN.matchEntire(raw.trim()) ?: return null
            val (wholeRaw, fractionRaw) = match.destructured

            val whole = wholeRaw.replace(",", "").toLongOrNull() ?: return null
            // "20.5" means 20 shillings 50 cents, not 20 shillings 5 cents.
            val fraction = if (fractionRaw.isEmpty()) 0L else fractionRaw.padEnd(2, '0').toLong()

            return KshAmount(whole * 100 + fraction)
        }

        /**
         * Either properly grouped (`1,300`) or ungrouped (`1300`) — but not
         * half-grouped (`1,30`), which signals the regex upstream grabbed the
         * wrong span of text.
         */
        private val PATTERN =
            Regex("""(?:Ksh|KES)?\s*(\d{1,3}(?:,\d{3})+|\d+)(?:\.(\d{1,2}))?""", RegexOption.IGNORE_CASE)
    }
}
