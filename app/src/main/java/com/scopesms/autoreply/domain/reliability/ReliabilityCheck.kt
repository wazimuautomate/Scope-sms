package com.scopesms.autoreply.domain.reliability

import com.scopesms.autoreply.domain.permissions.AppPermission
import com.scopesms.autoreply.domain.sim.SimSelection

/**
 * Everything [ReliabilityCheck] needs, read once and frozen.
 *
 * A snapshot rather than a set of live handles, for the same reason `SimFilter`
 * takes one: it makes the entire decision testable on the JVM with no
 * Robolectric (see memory.md — Robolectric would also force CI onto JDK 21).
 * Gathering it is `ReliabilityInspector`'s job; deciding what it means is this
 * file's, and the two are kept apart so the second can be tested exhaustively.
 */
data class ReliabilitySnapshot(
    /** `Build.VERSION.SDK_INT`. Passed in so the 33+ rules are testable. */
    val sdkInt: Int,

    /** Permission ids currently granted — `AppPermission.id` values. */
    val grantedPermissionIds: Set<String>,

    /** The agent's stored choice of which SIM(s) to watch. */
    val simSelection: SimSelection,

    /** Slots that currently hold a SIM. Empty is meaningless without READ_PHONE_STATE. */
    val activeSlots: Set<Int>,

    /** Whether the app currently holds the battery-optimisation exemption. */
    val batteryExempt: Boolean,
)

/**
 * Answers one question: **is this app actually able to do its job right now?**
 *
 * ### Why this exists, and why on boot
 * Scope SMS fails silently by nature. There is no foreground service (CLAUDE.md
 * constraint 6) and no screen open when it matters — the app is a
 * `BroadcastReceiver` that either wakes or doesn't. Every failure mode in
 * [ReliabilityIssue] looks *identical* to "a quiet day with no customers", from
 * the agent's side. They find out days later, from a customer who never got
 * their bundle. This is the check that turns that into a notification.
 *
 * ### What BUILD-PLAN Phase 9 asked for vs. what this does
 * The plan says the boot receiver should "re-verify battery-exemption status
 * and that saved SIM subscription IDs are still valid after reboot (dual-SIM
 * devices can reorder subscription IDs on some OEMs)". Both halves turned out
 * to be already-solved by Phase 1's design, so this checks the equivalent real
 * conditions instead:
 *
 * - **Subscription IDs are never persisted.** `SimSelection` stores the agent's
 *   choice by *physical slot* precisely because subscription IDs reorder — it
 *   cites this exact Phase 9 line as its reason. There is nothing to re-validate:
 *   the reorder cannot corrupt the setting. The equivalent failure that *can*
 *   still happen is the SIM moving trays or leaving the phone, which is
 *   [ReliabilityIssue.WatchedSlotsMissing].
 * - **Exemption status is never persisted either.** `BatteryOptimizationManager`
 *   reads it live from `PowerManager` on every call and deliberately refuses to
 *   cache it, so there is no stored copy to go stale. "Re-verify" therefore
 *   means *notice and tell the agent*, which is what this feeds.
 *
 * See memory.md — this is recorded as a deviation from the plan, per workflow
 * rule 7.
 */
object ReliabilityCheck {

    /**
     * Problems with [snapshot], worst first. Empty means healthy.
     *
     * Ordering is load-bearing: callers show the first issue most prominently,
     * and a notification that leads with "battery saver might stop this later"
     * while the app is *already* missing every payment would be actively
     * misleading.
     */
    fun evaluate(snapshot: ReliabilitySnapshot): List<ReliabilityIssue> = buildList {
        addAll(missingPermissions(snapshot))
        addAll(simIssues(snapshot))

        if (!snapshot.batteryExempt) {
            add(ReliabilityIssue.BatteryExemptionMissing)
        }
    }.sortedBy { it.severity }

    private fun missingPermissions(snapshot: ReliabilitySnapshot): List<ReliabilityIssue> =
        AppPermission.required(snapshot.sdkInt)
            .filterNot { it.id in snapshot.grantedPermissionIds }
            .map { ReliabilityIssue.MissingPermission(it) }

    /**
     * SIM problems — but only when we can actually see the SIMs.
     *
     * The guard is the important part. Without READ_PHONE_STATE,
     * `SimReader.activeSims()` returns an empty list by design — it cannot
     * distinguish "denied" from "no SIM", and says so. Reading that empty list
     * as fact would raise [ReliabilityIssue.NoSimDetected] ("check that your SIM
     * is seated properly") at an agent whose SIM is seated perfectly well and
     * whose actual problem — the revoked permission — is already sitting in the
     * list above, correctly diagnosed.
     *
     * One real fault must produce one true alarm. Two alarms, one of which
     * sends the agent to reseat a working SIM, is how a warning system teaches
     * people to ignore it.
     */
    private fun simIssues(snapshot: ReliabilitySnapshot): List<ReliabilityIssue> {
        if (AppPermission.READ_PHONE_STATE.id !in snapshot.grantedPermissionIds) {
            return emptyList()
        }

        if (snapshot.activeSlots.isEmpty()) {
            return listOf(ReliabilityIssue.NoSimDetected)
        }

        // AllSims watches whatever is present, so it cannot point at a missing
        // slot. Only an explicit slot choice can go stale.
        val watched = (snapshot.simSelection as? SimSelection.Slots)?.slots
            ?: return emptyList()

        // Empty means "watch nothing" — a different fault from "watch a slot
        // that's empty", and not one this function should claim.
        //
        // Guarding it is not defensive padding: `none {}` on an empty set is
        // vacuously *true*, so without this the check below fires and reports
        // WatchedSlotsMissing with an empty slot set, rendering the sentence
        // "You told Scope SMS to watch , but those slots are empty."
        //
        // SimSelection.decode() maps an empty stored value to DEFAULT, so this
        // state cannot come off disk and is near-unreachable in practice —
        // which is exactly why it earns a line of code rather than a shrug:
        // an unreachable branch that emits a broken sentence is a bug waiting
        // for someone to make it reachable. SimFilter already fails this state
        // safely as NO_SIM_SELECTED.
        if (watched.isEmpty()) return emptyList()

        // A partially-present choice is left alone on purpose. If the agent
        // watches SIM 1 and SIM 2 and pulls SIM 2, slot 0 still ingests
        // normally — that is a working app, and pulling a SIM is usually
        // deliberate. Only a choice with *nothing* behind it is a real outage:
        // that is when SimFilter drops every single message.
        return if (watched.none { it in snapshot.activeSlots }) {
            listOf(
                ReliabilityIssue.WatchedSlotsMissing(
                    watchedSlots = watched,
                    activeSlots = snapshot.activeSlots,
                ),
            )
        } else {
            emptyList()
        }
    }
}
