package com.scopesms.autoreply.telephony

/**
 * Digs the SIM identity out of an `SMS_RECEIVED` intent's extras.
 *
 * ### Why this is a list of keys and not one constant
 * There is no guaranteed extra for "which SIM did this arrive on". AOSP puts
 * the subscription ID in `"subscription"`, and `SubscriptionManager` publishes
 * `EXTRA_SUBSCRIPTION_INDEX` for the same idea, but the SMS stack predates
 * multi-SIM being a first-class concept and OEMs filled the gap themselves.
 * Transsion, Xiaomi and Oppo builds — which is most of this app's market — each
 * carry their own historical keys, and some ship both the AOSP one and a
 * private one with different values.
 *
 * So: try the well-defined keys first, fall back to the OEM ones, and if
 * nothing yields an answer, say so honestly (`null`) and let [SimFilter] decide
 * — rather than defaulting to slot 0 and confidently replying to a customer
 * because a payment landed on the agent's *personal* SIM.
 *
 * Pure by construction: the caller passes a lookup function, so this is
 * JVM-testable without a `Bundle` — which cannot be constructed off-device.
 */
object SubscriptionExtras {

    /**
     * Extras that carry a **subscription ID**, best-defined first.
     *
     * Prefer these over [SLOT_KEYS]: a subscription ID resolves to a slot
     * through `SimReader`, i.e. through the platform's own current mapping,
     * which is authoritative. A raw slot extra is whatever the OEM decided to
     * write and is only used when there's nothing better.
     */
    val SUBSCRIPTION_KEYS: List<String> = listOf(
        // SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX — the documented one.
        "android.telephony.extra.SUBSCRIPTION_INDEX",
        // What AOSP's SMS dispatcher actually puts in the intent.
        "subscription",
        // Seen on OEM builds.
        "subscription_id",
        "subId",
    )

    /**
     * Extras that carry a **slot index** directly. Fallback only.
     *
     * `"phone"` is here because older AOSP called the per-SIM stack a "phone id"
     * and some builds still do. It is last because the name is ambiguous enough
     * that a wrong hit is plausible.
     */
    val SLOT_KEYS: List<String> = listOf(
        "slot",
        "simSlotIndex",
        "slot_id",
        "simId",
        "phone",
    )

    /**
     * First value any of [keys] yields that passes [isPlausible].
     *
     * @param lookup reads an int extra, returning null when absent. Must not
     *   substitute a default — `getIntExtra(key, 0)` would make every missing
     *   extra look like a confident "slot 0".
     */
    fun firstValid(keys: List<String>, lookup: (String) -> Int?): Int? =
        keys.firstNotNullOfOrNull { key -> lookup(key)?.takeIf(::isPlausible) }

    /**
     * Rejects the platform's "no value" sentinels.
     *
     * `SubscriptionManager.INVALID_SUBSCRIPTION_ID` is `-1` and
     * `INVALID_SIM_SLOT_INDEX` is `-1` too; `DEFAULT_SUBSCRIPTION_ID` is
     * `Integer.MAX_VALUE`. Any of them arriving as a real value means "the
     * system doesn't know", and treating that as an identity would silently
     * mis-attribute the message.
     */
    fun isPlausible(value: Int): Boolean = value >= 0 && value != Int.MAX_VALUE
}
