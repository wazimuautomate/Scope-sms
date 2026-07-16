package com.scopesms.autoreply.domain.parser

import com.scopesms.autoreply.domain.money.KshAmount

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
     * What the customer actually paid.
     *
     * [KshAmount] is the app's canonical money type — integer-backed, so the
     * `payment.amount == rule.amount` comparison the whole app hinges on is
     * exact. See that class for why this is not a Double and not a bare Int.
     */
    val amount: KshAmount,

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

    /** The agent's new till balance, or null if the message omitted it. */
    val balance: KshAmount?,

    /** Transaction cost, or null if omitted. */
    val transactionCost: KshAmount?,
)
