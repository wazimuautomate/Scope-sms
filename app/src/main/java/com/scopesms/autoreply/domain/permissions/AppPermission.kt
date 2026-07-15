package com.scopesms.autoreply.domain.permissions

/**
 * Every permission this app asks for, why it asks, and when.
 *
 * Pure Kotlin by design — no `android.Manifest` import, no `Build.VERSION`
 * read. The SDK level is passed in instead, which is what makes the gating
 * rules (`POST_NOTIFICATIONS` only on 33+) testable on the JVM rather than
 * only discoverable on a physical Android 13 handset. CI is the only place
 * this project builds (CLAUDE.md constraint 8), so logic that can only be
 * checked on-device is logic that effectively isn't checked.
 *
 * The permission strings are duplicated from `android.Manifest.permission`
 * rather than referenced. They are frozen platform constants — they cannot
 * change without breaking every app ever shipped — and
 * `AppPermissionTest.permission ids match the platform constants` pins them
 * against the real values so the duplication can't silently drift.
 */
enum class AppPermission(
    /** The manifest permission string, e.g. `android.permission.RECEIVE_SMS`. */
    val id: String,
    /**
     * Lowest SDK level at which this must be requested at runtime.
     * [MIN_SDK] means "on every version this app supports".
     */
    val minSdkInclusive: Int,
    /** Short, plain-language reason shown to the agent before the system dialog. */
    val rationale: String,
) {
    /**
     * The one that makes the app work at all. Without it the system never
     * delivers SMS_RECEIVED to the Phase 2 receiver and nothing else in the
     * app ever runs.
     */
    RECEIVE_SMS(
        id = "android.permission.RECEIVE_SMS",
        minSdkInclusive = MIN_SDK,
        rationale = "So Scope SMS can see M-Pesa payment messages as they arrive.",
    ),

    /**
     * Same permission group as [RECEIVE_SMS], so in practice the agent grants
     * both in one tap.
     */
    READ_SMS(
        id = "android.permission.READ_SMS",
        minSdkInclusive = MIN_SDK,
        rationale = "So Scope SMS can read the payment details out of the message.",
    ),

    /**
     * Gates `SubscriptionManager.getActiveSubscriptionInfoList()`. Denied, the
     * SIM picker is empty and the agent cannot tell us which SIM is the
     * business one — so we cannot keep their personal payments from triggering
     * customer replies (CLAUDE.md constraint 4).
     */
    READ_PHONE_STATE(
        id = "android.permission.READ_PHONE_STATE",
        minSdkInclusive = MIN_SDK,
        rationale = "So Scope SMS can tell your SIM cards apart and only watch the business one.",
    ),

    /**
     * Optional — the app is fully functional without it.
     *
     * From API 33, `SubscriptionInfo.getNumber()` requires this specifically;
     * READ_PHONE_STATE alone returns blank. It buys one thing: showing each
     * SIM's own number in the picker. That matters when both SIMs are
     * Safaricom, where "Safaricom / Safaricom" tells the agent nothing and
     * picking the wrong one sends replies from the wrong place. Denied, the
     * picker falls back to slot + carrier, which is still usable.
     *
     * @see isOptional
     */
    READ_PHONE_NUMBERS(
        id = "android.permission.READ_PHONE_NUMBERS",
        minSdkInclusive = MIN_SDK,
        rationale = "So Scope SMS can show each SIM's phone number, making it easier to pick the right one.",
    ),

    /**
     * API 33+ only. On 30–32 notifications need no grant, and requesting a
     * permission the platform doesn't know about gets an immediate silent
     * denial that would leave the UI showing a permanent "missing permission"
     * warning on exactly the low-end Android 11/12 handsets this app targets.
     */
    POST_NOTIFICATIONS(
        id = "android.permission.POST_NOTIFICATIONS",
        minSdkInclusive = SDK_TIRAMISU,
        rationale = "So Scope SMS can alert you if a reply fails to send.",
    ),
    ;

    /** True when the app still works correctly if the agent denies this. */
    val isOptional: Boolean
        get() = this == READ_PHONE_NUMBERS || this == POST_NOTIFICATIONS

    companion object {
        /** CLAUDE.md constraint 1: Android 11. Mirrors `minSdk` in build.gradle.kts. */
        const val MIN_SDK: Int = 30

        /** API 33, Android 13 — where POST_NOTIFICATIONS became a runtime grant. */
        const val SDK_TIRAMISU: Int = 33

        /**
         * The runtime permissions to request on a device running [sdkInt],
         * in the order they should be asked for.
         *
         * `INTERNET` and `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` are absent on
         * purpose: both are install-time permissions granted at install with no
         * dialog. Passing them to `requestPermissions()` is a silent no-op, and
         * treating their absence from the grant result as a denial is a
         * classic way to build a permission screen that can never be satisfied.
         */
        fun requestable(sdkInt: Int): List<AppPermission> =
            entries.filter { sdkInt >= it.minSdkInclusive }

        /**
         * The subset without which the app cannot do its job. The UI blocks on
         * these; [isOptional] ones only degrade the experience.
         */
        fun required(sdkInt: Int): List<AppPermission> =
            requestable(sdkInt).filterNot { it.isOptional }
    }
}
