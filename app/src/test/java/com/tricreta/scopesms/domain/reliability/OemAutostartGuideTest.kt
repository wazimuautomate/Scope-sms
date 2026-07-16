package com.tricreta.scopesms.domain.reliability

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for OEM detection and guidance.
 *
 * `Build.BRAND`/`Build.MANUFACTURER` are passed in rather than read, which is
 * what lets every handset below be "tested" from CI. That is the only kind of
 * coverage available here: the real devices exist in Kenya, not on a GitHub
 * runner.
 *
 * What these tests can and cannot prove is worth being clear about. They prove
 * the *routing* — a Tecno gets Transsion instructions, an unknown phone gets
 * generic ones, nothing crashes. They cannot prove a single component name is
 * correct; only the agent's real phone can (see memory.md). Hence the tests
 * below assert hard on routing and on the instructions, and only structurally on
 * the deep links.
 */
class OemAutostartGuideTest {

    // --- Detection ----------------------------------------------------------

    @Test
    fun `Transsion's brands are all recognised`() {
        // The market this app is built for. Missing one of these means an agent
        // gets generic advice for the strictest phones on sale.
        assertEquals(OemFamily.TRANSSION, OemAutostartGuide.detect("TECNO", "TECNO MOBILE LIMITED"))
        assertEquals(OemFamily.TRANSSION, OemAutostartGuide.detect("Infinix", "InfinixMobility"))
        assertEquals(OemFamily.TRANSSION, OemAutostartGuide.detect("itel", "itel"))
    }

    @Test
    fun `brand alone is enough when manufacturer is unhelpful`() {
        // Real Transsion firmware reports MANUFACTURER inconsistently — some
        // builds put the useful string only in BRAND. Matching the pair is what
        // makes this work; matching MANUFACTURER alone would drop this device
        // into GENERIC.
        assertEquals(OemFamily.TRANSSION, OemAutostartGuide.detect("Infinix", "Unknown"))
    }

    @Test
    fun `manufacturer alone is enough when brand is unhelpful`() {
        assertEquals(OemFamily.TRANSSION, OemAutostartGuide.detect("generic", "TECNO"))
    }

    @Test
    fun `detection is case-insensitive`() {
        assertEquals(OemFamily.XIAOMI, OemAutostartGuide.detect("xiaomi", "xiaomi"))
        assertEquals(OemFamily.XIAOMI, OemAutostartGuide.detect("XIAOMI", "XIAOMI"))
        assertEquals(OemFamily.XIAOMI, OemAutostartGuide.detect("Redmi", "Xiaomi"))
    }

    @Test
    fun `sub-brands route to their parent skin`() {
        assertEquals(OemFamily.XIAOMI, OemAutostartGuide.detect("POCO", "Xiaomi"))
        assertEquals(OemFamily.COLOROS, OemAutostartGuide.detect("realme", "realme"))
        assertEquals(OemFamily.COLOROS, OemAutostartGuide.detect("OnePlus", "OnePlus"))
        assertEquals(OemFamily.VIVO, OemAutostartGuide.detect("iQOO", "vivo"))
        assertEquals(OemFamily.HUAWEI, OemAutostartGuide.detect("HONOR", "HUAWEI"))
    }

    @Test
    fun `an unknown phone gets generic advice rather than nothing`() {
        assertEquals(OemFamily.GENERIC, OemAutostartGuide.detect("Google", "Google"))
        assertEquals(OemFamily.GENERIC, OemAutostartGuide.detect("", ""))
    }

    // --- Guidance completeness ----------------------------------------------

    @Test
    fun `every family has usable instructions`() {
        // The instructions are the actual deliverable — BUILD-PLAN Phase 9 says
        // no code fix solves this, only user settings and clear instructions. A
        // family with an empty step list is a screen that tells the agent
        // nothing.
        OemFamily.entries.forEach { family ->
            val guidance = OemAutostartGuide.guidanceFor(family)
            assertEquals(family, guidance.family)
            assertTrue("$family has no steps", guidance.steps.isNotEmpty())
            assertTrue("$family has a blank step", guidance.steps.none { it.isBlank() })
        }
    }

    @Test
    fun `guidance never depends on a deep link existing`() {
        // GENERIC has no deep links at all and must still be fully useful. This
        // is the invariant the whole design rests on: on most devices no link
        // will resolve, and the screen must work anyway.
        val generic = OemAutostartGuide.guidanceFor(OemFamily.GENERIC)
        assertTrue(generic.deepLinks.isEmpty())
        assertTrue(generic.steps.isNotEmpty())
    }

    @Test
    fun `the OEMs with unfixable traps say so`() {
        // Huawei's PowerGenie and Samsung's Sleeping apps cannot be fixed by the
        // battery exemption or any deep link. An agent whose replies keep dying
        // on those phones needs to be told that, not left retrying steps that
        // cannot work.
        assertNotNull(OemAutostartGuide.guidanceFor(OemFamily.HUAWEI).caveat)
        assertNotNull(OemAutostartGuide.guidanceFor(OemFamily.SAMSUNG).caveat)
        assertNotNull(OemAutostartGuide.guidanceFor(OemFamily.TRANSSION).caveat)
    }

    // --- Deep-link hygiene --------------------------------------------------

    @Test
    fun `Transsion tries the most-attested component first`() {
        // Ordering is by strength of evidence. The Phone Master autostart
        // activity is the one ~12 independent libraries agree on; if it stops
        // being tried first, the fallbacks (which are weaker guesses) start
        // shadowing the best candidate.
        val first = OemAutostartGuide.guidanceFor(OemFamily.TRANSSION).deepLinks.first()

        assertEquals(
            OemDeepLink.Component("com.transsion.phonemaster", "com.cyin.himgr.autostart.AutoStartActivity"),
            first,
        )
    }

    @Test
    fun `Transsion covers itel's separate app, not just Phone Master`() {
        // itel hosts autostart in com.transsion.phonemanager — a different
        // package from Tecno/Infinix's Phone Master. Missing it means every itel
        // device silently falls back to instructions-only.
        val packages = OemAutostartGuide.guidanceFor(OemFamily.TRANSSION).deepLinks
            .filterIsInstance<OemDeepLink.Component>()
            .map { it.packageName }

        assertTrue("itel's HiManager package is missing", packages.contains("com.transsion.phonemanager"))
    }

    // --- Manifest guard -----------------------------------------------------
    //
    // Same shape as ArchitectureGuardTest: read the real manifest from disk and
    // fail the build on a rule prose cannot enforce. Phase 0 set this precedent
    // deliberately, for this project's specific risk — parallel sessions that
    // never read each other's code.

    private val moduleDir = File(System.getProperty("user.dir") ?: ".")
    private val manifest = File(moduleDir, "src/main/AndroidManifest.xml")

    @Test
    fun `test fixture points at the real manifest`() {
        // Guards the guard. Without this, a change to Gradle's working directory
        // would make the assertion below read an empty string and pass
        // vacuously — which is worse than not having it, because it would look
        // like coverage.
        assertTrue(
            "Expected the manifest at ${manifest.absolutePath}. If this fails, the " +
                "<queries> assertion below is not reading anything.",
            manifest.isFile,
        )
    }

    @Test
    fun `every deep-link package is declared in the manifest queries`() {
        // The trap here is invisible and total. Android 11+ package visibility
        // means resolveActivity() returns null for any package not named in
        // <queries> — so a component missing from the manifest can never
        // resolve, on any device, and the failure is indistinguishable from
        // "this phone doesn't have that screen". minSdk is 30, so this hits
        // every single user of this app.
        //
        // It is exactly the kind of mistake that ships: the Kotlin compiles, the
        // tests pass, the app runs, and the feature is simply dead. This test is
        // the only thing standing between that and the agent. (It has already
        // earned its place once — it is how the missing com.transsion.phonemanager
        // entry, itel's whole autostart path, was caught.)
        val manifestText = manifest.readText()

        val deepLinkPackages = OemFamily.entries
            .flatMap { OemAutostartGuide.guidanceFor(it).deepLinks }
            .filterIsInstance<OemDeepLink.Component>()
            .map { it.packageName }
            .toSet()

        assertFalse("Fixture broken: no component deep links found at all.", deepLinkPackages.isEmpty())

        val undeclared = deepLinkPackages.filterNot { pkg ->
            manifestText.contains("<package android:name=\"$pkg\" />")
        }

        assertTrue(
            "These packages are used as autostart deep links but are missing from " +
                "<queries> in AndroidManifest.xml, so resolveActivity() will return null " +
                "for them on every device and the shortcut will silently never work: " +
                "$undeclared",
            undeclared.isEmpty(),
        )
    }
}
