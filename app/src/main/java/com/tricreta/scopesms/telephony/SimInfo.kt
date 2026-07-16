package com.tricreta.scopesms.telephony

/**
 * One active SIM, flattened to the fields this app actually uses.
 *
 * A deliberately plain data class rather than passing `SubscriptionInfo`
 * around: `SubscriptionInfo` is a final platform class that cannot be
 * constructed in a JVM unit test, so anything typed against it drags
 * Robolectric — and JDK 21 (see memory.md) — into the test suite. Mapping to
 * this at the platform boundary keeps everything downstream testable on the
 * JVM.
 */
data class SimInfo(
    /**
     * Platform subscription ID. Use it to match against the SMS intent's
     * subscription extra and nothing else — it is **not stable** across SIM
     * re-seating or reboot on some OEMs, which is why the agent's choice is
     * persisted by [slotIndex] instead. See `SimSelection`.
     */
    val subscriptionId: Int,

    /** 0-based physical slot. Slot 0 is what the UI calls "SIM 1". */
    val slotIndex: Int,

    /** e.g. "Safaricom". May be blank on some OEM builds. */
    val carrierName: String,

    /**
     * The SIM's own MSISDN, or `null` when unavailable — which is the common
     * case, not the exception. Kenyan SIMs frequently have no number written to
     * the SIM at all, and from API 33 reading it needs READ_PHONE_NUMBERS,
     * which the agent may deny. Treat as a display nicety; never key logic on
     * it.
     */
    val phoneNumber: String?,

    /**
     * The label the platform or agent gave this SIM, if any — often the
     * carrier name, sometimes a custom name the agent set in system settings.
     */
    val displayName: String?,
) {
    /** 1-based slot, for humans. Slot 0 → "SIM 1". */
    val slotLabel: String get() = "SIM ${slotIndex + 1}"
}
