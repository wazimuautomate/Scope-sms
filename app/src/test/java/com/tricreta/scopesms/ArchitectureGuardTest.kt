package com.tricreta.scopesms

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Executable enforcement of the architecture rules that CLAUDE.md states in
 * prose.
 *
 * BUILD-PLAN's Phase 0 only asks for a test step that "passes trivially". A
 * tautology would satisfy that letter and protect nothing, so these guards
 * take its place. They exist because of a specific risk on this project:
 * phases are being built by separate sessions working in parallel, against a
 * plan that was rewritten mid-flight. The pre-pivot design sent replies with
 * `SmsManager` over the agent's SIM, and plenty of Android SMS tutorials do
 * the same. A session reaching for the old approach out of habit is a
 * realistic mistake, and one that would silently undo the entire point of the
 * gateway: replies would arrive from the agent's personal phone number instead
 * of the registered "SCOPE SMS" sender ID.
 *
 * These run on the JVM in milliseconds with no Android dependency, so they
 * fail the build in CI the moment that regression is pushed — long before an
 * APK reaches the agent's customers.
 *
 * If a future phase has a genuine, documented reason to break one of these,
 * the fix is to change CLAUDE.md and this test together and record why in
 * memory.md — not to quietly delete the assertion.
 */
class ArchitectureGuardTest {

    private val moduleDir = File(System.getProperty("user.dir") ?: ".")
    private val manifest = File(moduleDir, "src/main/AndroidManifest.xml")
    private val mainSources = File(moduleDir, "src/main/java")

    @Test
    fun `test fixture points at the real module`() {
        // Guards the guards. If Gradle's working directory ever changes, the
        // File lookups below would silently read nothing and every assertion
        // would pass vacuously — the exact failure mode these tests exist to
        // prevent.
        assertTrue(
            "Expected the manifest at ${manifest.absolutePath}. If this fails, the " +
                "other assertions in this class are not actually reading anything.",
            manifest.isFile,
        )
        assertTrue(
            "Expected main sources at ${mainSources.absolutePath}.",
            mainSources.isDirectory,
        )
    }

    @Test
    fun `manifest never declares SEND_SMS`() {
        // CLAUDE.md constraint 3. Outbound goes through the SCOPE SMS HTTP
        // gateway with a registered sender ID; the device never sends an SMS
        // itself. Declaring SEND_SMS would also drag the app further into
        // Play's SMS policy for no benefit.
        assertTrue(
            "AndroidManifest.xml declares SEND_SMS. This app must never send SMS from " +
                "the device — all outbound messages go through the SCOPE SMS gateway " +
                "using the agent's sender ID (CLAUDE.md constraint 3).",
            !manifest.readText().contains("android.permission.SEND_SMS"),
        )
    }

    @Test
    fun `no source file sends SMS via SmsManager`() {
        val offenders = mainSources.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains("SmsManager") }
            .map { it.relativeTo(moduleDir).path }
            .toList()

        assertTrue(
            "SmsManager is referenced in: $offenders. Sending via SmsManager is the " +
                "pre-pivot architecture — it would send replies from the agent's own " +
                "phone number rather than the registered sender ID. Use the gateway " +
                "client in network/ instead (CLAUDE.md constraint 3).",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `application id is the one the agent installs`() {
        // The applicationId is the app's permanent identity: change it and the
        // agent's next direct-install APK lands as a second app beside the
        // first rather than updating it, taking their rules and history with
        // it. Phase 11's update flow depends on this staying put.
        // Unit tests run against the debug variant, whose applicationId carries
        // a ".debug" suffix so it installs beside the real app. Strip it: the
        // guard is about the permanent BASE id the agent's release installs
        // under, which must never drift.
        assertTrue(
            "Expected base applicationId com.tricreta.scopesms but was ${BuildConfig.APPLICATION_ID}.",
            BuildConfig.APPLICATION_ID.removeSuffix(".debug") == "com.tricreta.scopesms",
        )
    }
}
