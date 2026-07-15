package com.scopesms.autoreply.domain.parser

/**
 * A successfully parsed M-Pesa **money-received** confirmation.
 *
 * Only the "received" type is modelled. Sent-money, withdrawal, balance and
 * airtime confirmations are rejected outright by [MpesaParser] — replying to
 * one would text a customer a price list because the agent *paid* someone.
 */
data class MpesaPayment(
    /** Transaction code, e.g. `UGFMXB3GR6`. Uppercase, as M-Pesa sends it. */
    val transactionCode: String,

    /**
     * Amount in **cents** (1/100 KES). See [Money] for why this isn't a Double.
     */
    val amountCents: Long,

    /**
     * The payer's number as it appeared in the SMS, normalised to local
     * `07XXXXXXXX`/`01XXXXXXXX` form — which is what the SCOPE gateway's
     * `/sendsms` expects.
     */
    val senderPhone: String,

    /**
     * The payer's name as M-Pesa reported it, e.g. `Skycope Bonke`.
     *
     * Nullable: it is the field most likely to be missing or mangled, and it is
     * only ever interpolated into `{name}`. Phase 4's template engine must not
     * crash on null here — BUILD-PLAN Phase 4 calls that out explicitly.
     */
    val senderName: String?,

    /** Raw date as printed, e.g. `15/7/26`. Not parsed into a calendar type — see [MpesaParser]. */
    val date: String,

    /** Raw time as printed, e.g. `1:06 PM`. */
    val time: String,

    /** The agent's new till balance in cents, or null if the message omitted it. */
    val balanceCents: Long?,

    /** Transaction cost in cents, or null if omitted. */
    val transactionCostCents: Long?,
)

/**
 * Helpers for money as integer cents.
 *
 * ### Why cents, and why this matters beyond this file
 * The whole app hinges on `amount == rule.amount`. Doubles make that comparison
 * a lie: `20.00` isn't necessarily `20.00` after a parse, and a bundle priced at
 * 20 could fail to match a 20-shilling payment — which would send the customer a
 * "you paid the wrong amount" price list for a payment that was exactly right.
 * Integer cents make equality exact.
 *
 * **Phase 3 must store `PricingRule.amount` in cents too.** BUILD-PLAN's schema
 * just says `amount`; this is the concrete choice, recorded in memory.md. A rule
 * table in shillings and a parser in cents would match nothing at all.
 */
object Money {

    /**
     * Parses an M-Pesa amount string (`20.00`, `1,300.22`, `20`) to cents.
     * Returns null if it isn't a well-formed amount.
     */
    fun parseCents(raw: String): Long? {
        // M-Pesa groups thousands: "Ksh1,300.22".
        val cleaned = raw.replace(",", "").trim()
        if (cleaned.isEmpty()) return null

        val parts = cleaned.split(".")
        if (parts.size > 2) return null

        val shillings = parts[0].toLongOrNull() ?: return null
        if (shillings < 0) return null

        val cents = when (parts.size) {
            1 -> 0L
            else -> {
                // "20.5" means 50 cents, not 5. Pad before reading.
                val fraction = parts[1]
                if (fraction.isEmpty() || fraction.length > 2) return null
                fraction.padEnd(2, '0').toLongOrNull() ?: return null
            }
        }
        if (cents < 0) return null

        return shillings * 100 + cents
    }

    /** Renders cents for display: `2000` → `"20.00"`. */
    fun format(cents: Long): String = "${cents / 100}.${(cents % 100).toString().padStart(2, '0')}"
}
