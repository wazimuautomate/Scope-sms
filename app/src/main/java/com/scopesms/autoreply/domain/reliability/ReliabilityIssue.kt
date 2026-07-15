package com.scopesms.autoreply.domain.reliability

import com.scopesms.autoreply.domain.permissions.AppPermission

/**
 * How badly a [ReliabilityIssue] hurts.
 *
 * Ordered worst-first so `sortedBy { it.severity }` puts the thing that has
 * already broken above the thing that is merely going to.
 */
enum class Severity {
    /** Ingestion is not working *right now*. Payments are being missed as we speak. */
    BLOCKING,

    /** Working at this instant, but the platform is expected to break it soon. */
    DEGRADED,
}

/**
 * Something wrong with the app's ability to keep receiving and replying to
 * M-Pesa messages, phrased so the agent can act on it.
 *
 * ### Why these carry their own copy
 * Pure Kotlin, plain strings, no `Context` and no string resources — same
 * reasoning as `AppPermission.rationale`, which set this precedent in Phase 1.
 * These have to render in three places (the boot notification, the Settings
 * health card, the OEM guidance screen), one of which is a `BroadcastReceiver`
 * that may run with no UI alive at all. Keeping the words next to the condition
 * that produces them means the whole set is JVM-testable, and CI is the only
 * place this project builds (CLAUDE.md constraint 8).
 *
 * Every string here is written for a Bingwa agent, not a developer: it names
 * what stopped working and what to tap, never an API or a permission constant.
 */
sealed interface ReliabilityIssue {

    val severity: Severity

    /** One line, notification-title length. */
    val title: String

    /** A sentence or two: what broke, what it costs, what to do. */
    val detail: String

    /**
     * A required runtime permission was revoked after setup.
     *
     * Not hypothetical on this app's minSdk: Android 11 (API 30) is exactly
     * where **permission auto-reset for unused apps** landed, and Android 12
     * extended it further. An agent who goes on holiday for a few months can
     * come back to an app that looks installed and configured and silently
     * holds no SMS permission. The system's own notification for this is easy
     * to miss.
     */
    data class MissingPermission(val permission: AppPermission) : ReliabilityIssue {
        override val severity = Severity.BLOCKING
        override val title = "Scope SMS lost a permission it needs"
        override val detail =
            "Android has withdrawn a permission Scope SMS needs to work: " +
                "${permission.rationale.replaceFirstChar { it.lowercase() }} " +
                "Open Scope SMS and grant it again — until then, customer payments " +
                "are not being seen."
    }

    /**
     * No SIM is readable at all.
     *
     * Only ever raised when READ_PHONE_STATE is *granted* — see
     * [ReliabilityCheck], which suppresses every SIM issue without it, because
     * a denied permission makes the SIM list look empty for a reason that has
     * nothing to do with the SIM tray.
     */
    data object NoSimDetected : ReliabilityIssue {
        override val severity = Severity.BLOCKING
        override val title = "No SIM card detected"
        override val detail =
            "Scope SMS cannot see any SIM card in this phone, so it cannot watch " +
                "for M-Pesa messages. Check that your SIM is seated properly."
    }

    /**
     * The agent picked slot(s) to watch, and none of them currently hold a SIM.
     *
     * This is the failure this whole boot check exists for. `SimFilter` will
     * drop **every** incoming message as `UNWATCHED_SIM` — correctly, per its
     * own rules — and the app will look completely healthy while doing it: no
     * crash, no error, no log the agent would ever see. It just quietly stops
     * replying to customers. The cause is usually mundane: the agent moved the
     * business SIM to the other tray, or pulled it to use another handset.
     */
    data class WatchedSlotsMissing(
        val watchedSlots: Set<Int>,
        val activeSlots: Set<Int>,
    ) : ReliabilityIssue {
        override val severity = Severity.BLOCKING

        override val title = "Scope SMS is watching a SIM that isn't there"

        override val detail = buildString {
            append("You told Scope SMS to watch ")
            append(watchedSlots.sorted().joinToString(" and ") { "SIM ${it + 1}" })
            append(", but ")
            append(
                if (watchedSlots.size == 1) "that slot is empty" else "those slots are empty",
            )
            append(" now. ")
            if (activeSlots.isNotEmpty()) {
                append("The SIM in ")
                append(activeSlots.sorted().joinToString(" and ") { "SIM ${it + 1}" })
                append(" is being ignored. ")
            }
            append("No customer payments are being replied to. Open Settings and pick the right SIM.")
        }
    }

    /**
     * Battery-optimisation exemption is not held.
     *
     * [Severity.DEGRADED] rather than blocking, and the distinction is real:
     * with no exemption the app works fine while the phone is awake, then stops
     * once the screen has been off a while. That "works when I check it, misses
     * payments overnight" shape is the single hardest bug for the agent to
     * report and the one CLAUDE.md constraint 6 gives us no foreground service
     * to paper over.
     */
    data object BatteryExemptionMissing : ReliabilityIssue {
        override val severity = Severity.DEGRADED
        override val title = "Battery saver may stop Scope SMS"
        override val detail =
            "Scope SMS is not exempt from battery optimisation. It will keep working " +
                "while you are using the phone, but Android may stop it from seeing " +
                "payments once the screen has been off for a while. Open Settings to fix this."
    }
}
