package com.tricreta.scopesms.network

/**
 * Normalises a Kenyan MSISDN to the gateway's documented local format.
 *
 * Why bother, when the docs say `254…` is "accepted and converted"? Because the
 * numbers arrive from M-Pesa in exactly that international form
 * (`"...received from 254700000000 Skycope Bonke..."`), and the format the docs
 * lead with — `07XXXXXXXX` / `01XXXXXXXX` — is the one they actually specify.
 * Converting here means we send the documented shape and don't depend on an
 * undocumented conversion staying correct. It also gives us one place to reject
 * a number that the parser mis-read, rather than paying a round trip to find out.
 *
 * Pure Kotlin, no Android dependency, so it tests on the JVM in CI.
 */
internal object PhoneNumbers {

    /** Safaricom/Airtel/Telkom mobile prefixes are 7x and 1x under +254. */
    private val LOCAL_FORMAT = Regex("""^0[17]\d{8}$""")

    /** Same prefixes, international shape: `254` + the local number minus its trunk zero. */
    private val INTERNATIONAL_FORMAT = Regex("""^254[17]\d{8}$""")

    /**
     * Returns the number as `07XXXXXXXX`/`01XXXXXXXX`, or `null` if it isn't a
     * plausible Kenyan mobile number.
     *
     * `null` becomes [SendFailure.InvalidPhone] — a terminal failure, since a
     * number that the parser got wrong won't fix itself on retry.
     */
    fun toLocalFormat(raw: String): String? {
        // M-Pesa is consistent, but agents also type numbers into the UI by
        // hand, where "+254 712 345 678" and "0712-345-678" are both normal.
        val digits = raw.filter { it.isDigit() }

        val local = when {
            // 254712345678 → 0712345678
            digits.length == 12 && digits.startsWith("254") -> "0" + digits.substring(3)
            // 0712345678 — already local
            digits.length == 10 && digits.startsWith("0") -> digits
            // 712345678 — local, missing its trunk zero
            digits.length == 9 && (digits.startsWith("7") || digits.startsWith("1")) -> "0$digits"
            else -> return null
        }

        return local.takeIf { LOCAL_FORMAT.matches(it) }
    }

    /**
     * Returns the number as `254XXXXXXXXX` (country code, no `+`, no trunk
     * zero), or `null` if it isn't a plausible Kenyan mobile number.
     *
     * HostPinnacle's `mobile` field wants exactly this shape — the opposite of
     * BlazeTech's documented local format, hence a second function rather than
     * converting one to the other at the call site: both are equally "the
     * gateway's documented shape" and both deserve their own guard against a
     * number the parser mis-read (same reasoning as [toLocalFormat]).
     *
     * Same acceptance matrix as [toLocalFormat] — `07.../01...`, `+254.../254...`,
     * a bare 9-digit number missing its trunk zero — just projected onto the
     * international shape instead of the local one.
     */
    fun toInternationalFormat(raw: String): String? {
        val digits = raw.filter { it.isDigit() }

        val international = when {
            // 254712345678 — already international
            digits.length == 12 && digits.startsWith("254") -> digits
            // 0712345678 → 254712345678
            digits.length == 10 && digits.startsWith("0") -> "254" + digits.substring(1)
            // 712345678 — missing both its trunk zero and country code
            digits.length == 9 && (digits.startsWith("7") || digits.startsWith("1")) -> "254$digits"
            else -> return null
        }

        return international.takeIf { INTERNATIONAL_FORMAT.matches(it) }
    }
}
