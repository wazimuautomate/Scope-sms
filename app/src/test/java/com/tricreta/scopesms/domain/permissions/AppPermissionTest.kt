package com.tricreta.scopesms.domain.permissions

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The SDK gating here is the kind of thing that otherwise only shows up on a
 * physical Android 11 handset — which, with no local build (CLAUDE.md
 * constraint 8), means it shows up at the agent's shop counter.
 */
class AppPermissionTest {

    @Test
    fun `permission ids match the platform constants`() {
        // AppPermission duplicates these strings to stay JVM-pure. That's only
        // safe if the copies are pinned to the originals — a typo in one would
        // otherwise produce a permission that is silently never granted, and an
        // app that silently never receives SMS.
        //
        // Safe to read android.Manifest here: these are compile-time String
        // constants, inlined by the compiler, so no stubbed android.jar method
        // is ever called.
        assertEquals(Manifest.permission.RECEIVE_SMS, AppPermission.RECEIVE_SMS.id)
        assertEquals(Manifest.permission.READ_SMS, AppPermission.READ_SMS.id)
        assertEquals(Manifest.permission.READ_PHONE_STATE, AppPermission.READ_PHONE_STATE.id)
        assertEquals(Manifest.permission.READ_PHONE_NUMBERS, AppPermission.READ_PHONE_NUMBERS.id)
        assertEquals(Manifest.permission.POST_NOTIFICATIONS, AppPermission.POST_NOTIFICATIONS.id)
    }

    @Test
    fun `post notifications is not requested below API 33`() {
        // Requesting a permission the platform doesn't know about returns an
        // immediate denial, which would leave the setup screen showing a
        // permanent "missing permission" warning the agent cannot clear — on
        // exactly the low-end Android 11/12 devices this app targets.
        val requestable = AppPermission.requestable(sdkInt = 30)

        assertFalse(AppPermission.POST_NOTIFICATIONS in requestable)
    }

    @Test
    fun `post notifications is requested from API 33`() {
        assertTrue(AppPermission.POST_NOTIFICATIONS in AppPermission.requestable(sdkInt = 33))
        assertTrue(AppPermission.POST_NOTIFICATIONS in AppPermission.requestable(sdkInt = 36))
    }

    @Test
    fun `sms and phone permissions are requested on every supported version`() {
        // The floor is Android 11 (CLAUDE.md constraint 1). Nothing that the app
        // fundamentally needs may be gated above it.
        val onOldest = AppPermission.requestable(sdkInt = AppPermission.MIN_SDK)

        assertTrue(AppPermission.RECEIVE_SMS in onOldest)
        assertTrue(AppPermission.READ_SMS in onOldest)
        assertTrue(AppPermission.READ_PHONE_STATE in onOldest)
    }

    @Test
    fun `required set excludes the optional permissions`() {
        // These two gate nothing: the app ingests and replies without either.
        // If they leaked into `required`, a denied notification prompt would
        // block setup on a device that works fine.
        val required = AppPermission.required(sdkInt = 36)

        assertFalse(AppPermission.READ_PHONE_NUMBERS in required)
        assertFalse(AppPermission.POST_NOTIFICATIONS in required)
    }

    @Test
    fun `required set is exactly the ingestion permissions`() {
        assertEquals(
            listOf(
                AppPermission.RECEIVE_SMS,
                AppPermission.READ_SMS,
                AppPermission.READ_PHONE_STATE,
            ),
            AppPermission.required(sdkInt = 36),
        )
    }

    @Test
    fun `no install-time permission is ever requested at runtime`() {
        // INTERNET, ACCESS_NETWORK_STATE and REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
        // are granted at install with no dialog. Passing one to
        // requestPermissions() is a no-op, so treating its absence from the
        // result as a denial builds a screen that can never be satisfied.
        val installTime = setOf(
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        )

        val requested = AppPermission.requestable(sdkInt = 36).map { it.id }

        assertTrue(
            "Install-time permissions must not be in the runtime request list.",
            requested.none { it in installTime },
        )
    }

    @Test
    fun `every permission has a rationale the agent can act on`() {
        // These strings are shown before the system dialog. An empty one means a
        // bare "Allow Scope SMS to send and view SMS messages?" with no reason —
        // the surest way to get a permanent denial on the app's core permission.
        AppPermission.entries.forEach { permission ->
            assertTrue(
                "${permission.name} has no rationale.",
                permission.rationale.isNotBlank(),
            )
        }
    }
}
