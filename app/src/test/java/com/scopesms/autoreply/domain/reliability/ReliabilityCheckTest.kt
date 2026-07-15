package com.scopesms.autoreply.domain.reliability

import com.scopesms.autoreply.domain.permissions.AppPermission
import com.scopesms.autoreply.domain.sim.SimSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exhaustive JVM tests for the boot-time health check.
 *
 * No Robolectric: [ReliabilityCheck] takes a snapshot of plain data, so every
 * branch below runs on the JVM in milliseconds. That is deliberate — CI is this
 * project's only compiler (CLAUDE.md constraint 8) and Robolectric would drag
 * the workflow onto JDK 21 (see memory.md).
 */
class ReliabilityCheckTest {

    // --- Healthy baseline ---------------------------------------------------

    @Test
    fun `a fully healthy device reports nothing`() {
        assertEquals(emptyList<ReliabilityIssue>(), ReliabilityCheck.evaluate(healthy()))
    }

    @Test
    fun `healthy on Android 13 too, where POST_NOTIFICATIONS exists`() {
        assertEquals(emptyList<ReliabilityIssue>(), ReliabilityCheck.evaluate(healthy(sdkInt = 33)))
    }

    // --- Permissions --------------------------------------------------------

    @Test
    fun `a revoked required permission is blocking`() {
        val issues = ReliabilityCheck.evaluate(healthy().withoutPermission(AppPermission.RECEIVE_SMS))

        val issue = issues.single() as ReliabilityIssue.MissingPermission
        assertEquals(AppPermission.RECEIVE_SMS, issue.permission)
        assertEquals(Severity.BLOCKING, issue.severity)
    }

    @Test
    fun `every revoked required permission is listed, not just the first`() {
        val snapshot = healthy()
            .withoutPermission(AppPermission.RECEIVE_SMS)
            .withoutPermission(AppPermission.READ_SMS)

        val reported = ReliabilityCheck.evaluate(snapshot)
            .filterIsInstance<ReliabilityIssue.MissingPermission>()
            .map { it.permission }

        assertEquals(listOf(AppPermission.RECEIVE_SMS, AppPermission.READ_SMS), reported)
    }

    @Test
    fun `optional permissions are never reported — the app works without them`() {
        // READ_PHONE_NUMBERS only prettifies the SIM picker; POST_NOTIFICATIONS
        // denied is the agent's choice to make. Nagging about either on every
        // reboot is how an agent learns to swipe these away unread.
        val snapshot = healthy(sdkInt = 33)
            .withoutPermission(AppPermission.READ_PHONE_NUMBERS)
            .withoutPermission(AppPermission.POST_NOTIFICATIONS)

        assertEquals(emptyList<ReliabilityIssue>(), ReliabilityCheck.evaluate(snapshot))
    }

    // --- SIM state ----------------------------------------------------------

    @Test
    fun `no SIM in the phone is blocking`() {
        val issues = ReliabilityCheck.evaluate(healthy().copy(activeSlots = emptySet()))
        assertEquals(listOf(ReliabilityIssue.NoSimDetected), issues)
    }

    @Test
    fun `an empty SIM list without READ_PHONE_STATE does not become a phantom SIM alarm`() {
        // The regression this guards: SimReader returns an empty list when the
        // permission is denied, because it cannot tell "denied" from "no SIM".
        // Believing it would tell an agent to reseat a perfectly good SIM while
        // the real fault — the revoked permission — is already in the list.
        val snapshot = healthy()
            .withoutPermission(AppPermission.READ_PHONE_STATE)
            .copy(activeSlots = emptySet())

        val issues = ReliabilityCheck.evaluate(snapshot)

        assertTrue(issues.none { it is ReliabilityIssue.NoSimDetected })
        assertTrue(issues.none { it is ReliabilityIssue.WatchedSlotsMissing })
        assertEquals(
            listOf(AppPermission.READ_PHONE_STATE),
            issues.filterIsInstance<ReliabilityIssue.MissingPermission>().map { it.permission },
        )
    }

    @Test
    fun `watching a slot that no longer holds a SIM is blocking`() {
        // The headline Phase 9 failure: agent moved the business SIM to the
        // other tray. SimFilter now drops every message as UNWATCHED_SIM and
        // the app looks perfectly healthy while replying to nobody.
        val snapshot = healthy().copy(
            simSelection = SimSelection.slot(1),
            activeSlots = setOf(0),
        )

        val issue = ReliabilityCheck.evaluate(snapshot).single() as ReliabilityIssue.WatchedSlotsMissing
        assertEquals(setOf(1), issue.watchedSlots)
        assertEquals(setOf(0), issue.activeSlots)
        assertEquals(Severity.BLOCKING, issue.severity)
    }

    @Test
    fun `that warning names the slots in the agent's numbering, not the platform's`() {
        // Slots are 0-based; agents count from 1. Getting this backwards sends
        // them to the wrong tray.
        val snapshot = healthy().copy(
            simSelection = SimSelection.slot(1),
            activeSlots = setOf(0),
        )

        val detail = ReliabilityCheck.evaluate(snapshot).single().detail

        assertTrue("should tell them to look at SIM 2", detail.contains("SIM 2"))
        assertTrue("should say SIM 1 is being ignored", detail.contains("SIM 1"))
    }

    @Test
    fun `a partially present selection is left alone`() {
        // Watching both, only slot 0 populated: slot 0 still ingests normally.
        // Pulling a SIM is usually deliberate and this is a working app.
        val snapshot = healthy().copy(
            simSelection = SimSelection.Slots(setOf(0, 1)),
            activeSlots = setOf(0),
        )

        assertEquals(emptyList<ReliabilityIssue>(), ReliabilityCheck.evaluate(snapshot))
    }

    @Test
    fun `AllSims can never point at a missing slot`() {
        val snapshot = healthy().copy(
            simSelection = SimSelection.AllSims,
            activeSlots = setOf(1),
        )

        assertEquals(emptyList<ReliabilityIssue>(), ReliabilityCheck.evaluate(snapshot))
    }

    @Test
    fun `an empty stored selection is not reported as a missing SIM`() {
        // SimSelection.Slots(emptySet()) is representable from a corrupt file.
        // SimFilter already fails it safely as NO_SIM_SELECTED. Reporting it as
        // "your SIM isn't there" would be a lie about a real, different bug.
        val snapshot = healthy().copy(
            simSelection = SimSelection.Slots(emptySet()),
            activeSlots = setOf(0),
        )

        assertTrue(ReliabilityCheck.evaluate(snapshot).none { it is ReliabilityIssue.WatchedSlotsMissing })
    }

    // --- Battery ------------------------------------------------------------

    @Test
    fun `losing the battery exemption is degraded, not blocking`() {
        val issues = ReliabilityCheck.evaluate(healthy().copy(batteryExempt = false))

        assertEquals(listOf(ReliabilityIssue.BatteryExemptionMissing), issues)
        assertEquals(Severity.DEGRADED, issues.single().severity)
    }

    // --- Ordering -----------------------------------------------------------

    @Test
    fun `what is already broken outranks what is going to break`() {
        // Callers lead with issues.first(). A notification that opens with
        // "battery saver may stop this later" while every payment is already
        // being missed is worse than no notification.
        val snapshot = healthy()
            .withoutPermission(AppPermission.RECEIVE_SMS)
            .copy(batteryExempt = false)

        val issues = ReliabilityCheck.evaluate(snapshot)

        assertEquals(2, issues.size)
        assertEquals(Severity.BLOCKING, issues.first().severity)
        assertEquals(Severity.DEGRADED, issues.last().severity)
    }

    @Test
    fun `severity ordinals order worst-first, which is what sortedBy relies on`() {
        // Pins the enum order: reordering the declarations would silently invert
        // every ordering guarantee above.
        assertTrue(Severity.BLOCKING < Severity.DEGRADED)
    }

    @Test
    fun `a thoroughly broken device reports everything at once`() {
        val snapshot = ReliabilitySnapshot(
            sdkInt = 30,
            grantedPermissionIds = emptySet(),
            simSelection = SimSelection.slot(0),
            activeSlots = emptySet(),
            batteryExempt = false,
        )

        val issues = ReliabilityCheck.evaluate(snapshot)

        // All three required permissions, plus the battery warning. No SIM
        // issue — READ_PHONE_STATE is denied, so the SIM list is unreadable
        // rather than empty.
        assertEquals(3, issues.filterIsInstance<ReliabilityIssue.MissingPermission>().size)
        assertTrue(issues.contains(ReliabilityIssue.BatteryExemptionMissing))
        assertFalse(issues.contains(ReliabilityIssue.NoSimDetected))
        assertEquals(4, issues.size)
    }

    // --- Fixtures -----------------------------------------------------------

    /** A device where everything works: the state every test above perturbs by one field. */
    private fun healthy(sdkInt: Int = 30) = ReliabilitySnapshot(
        sdkInt = sdkInt,
        grantedPermissionIds = AppPermission.requestable(sdkInt).map { it.id }.toSet(),
        simSelection = SimSelection.slot(0),
        activeSlots = setOf(0, 1),
        batteryExempt = true,
    )

    private fun ReliabilitySnapshot.withoutPermission(permission: AppPermission) =
        copy(grantedPermissionIds = grantedPermissionIds - permission.id)
}
