package com.scopesms.autoreply.domain.update

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Phase 11's version comparison.
 *
 * Small, but it is the whole update feature: wrong one way and the agent is
 * nagged forever by an update they already installed; wrong the other and they
 * never hear about a fix. Both are silent failures, so they get tests.
 */
class UpdateCheckTest {

    private val url = "https://github.com/wazimuautomate/Scope-sms/releases/tag/v1.1.0"

    // --- parsing -----------------------------------------------------------

    @Test
    fun `parses a plain version`() {
        assertThat(AppVersion.parse("1.2.3")).isEqualTo(AppVersion(1, 2, 3))
    }

    @Test
    fun `parses the v prefix the git tag carries`() {
        // The one place two conventions meet: tags are "v1.0.0", BuildConfig's
        // VERSION_NAME is "1.0.0".
        assertThat(AppVersion.parse("v1.0.0")).isEqualTo(AppVersion(1, 0, 0))
        assertThat(AppVersion.parse("V1.0.0")).isEqualTo(AppVersion(1, 0, 0))
    }

    @Test
    fun `tolerates surrounding whitespace`() {
        assertThat(AppVersion.parse("  v2.0.1  ")).isEqualTo(AppVersion(2, 0, 1))
    }

    @Test
    fun `a pre-release suffix is ignored, not rejected`() {
        assertThat(AppVersion.parse("1.0.0-rc1")).isEqualTo(AppVersion(1, 0, 0))
        assertThat(AppVersion.parse("1.0.0+build7")).isEqualTo(AppVersion(1, 0, 0))
    }

    @Test
    fun `rubbish is null, never version zero`() {
        // The distinction that matters: null means "offer no update". A silent
        // fallback to 0.0.0 would make every malformed tag look ancient and
        // suppress real updates forever.
        assertThat(AppVersion.parse(null)).isNull()
        assertThat(AppVersion.parse("")).isNull()
        assertThat(AppVersion.parse("latest")).isNull()
        assertThat(AppVersion.parse("1.2")).isNull()
        assertThat(AppVersion.parse("1.2.3.4")).isNull()
        assertThat(AppVersion.parse("v")).isNull()
    }

    // --- ordering ----------------------------------------------------------

    @Test
    fun `ordering is major then minor then patch`() {
        assertThat(AppVersion(2, 0, 0)).isGreaterThan(AppVersion(1, 9, 9))
        assertThat(AppVersion(1, 2, 0)).isGreaterThan(AppVersion(1, 1, 9))
        assertThat(AppVersion(1, 1, 2)).isGreaterThan(AppVersion(1, 1, 1))
    }

    @Test
    fun `minor versions are numbers, not decimals`() {
        // The classic: "1.10.0" is newer than "1.9.0", though 1.10 < 1.9 as text
        // and as a decimal. String comparison would get this exactly backwards
        // and strand the agent on 1.9.0 forever.
        assertThat(AppVersion.parse("1.10.0")!!).isGreaterThan(AppVersion.parse("1.9.0")!!)
    }

    // --- the decision ------------------------------------------------------

    @Test
    fun `a newer release is offered`() {
        val status = UpdateCheck.evaluate("1.0.0", "v1.1.0", url, notes = "Fixes")
        assertThat(status).isInstanceOf(UpdateStatus.Available::class.java)

        val available = status as UpdateStatus.Available
        assertThat(available.version).isEqualTo(AppVersion(1, 1, 0))
        assertThat(available.url).isEqualTo(url)
        assertThat(available.notes).isEqualTo("Fixes")
    }

    @Test
    fun `the same version is up to date`() {
        assertThat(UpdateCheck.evaluate("1.0.0", "v1.0.0", url)).isEqualTo(UpdateStatus.UpToDate)
    }

    @Test
    fun `an older release is up to date, not a downgrade prompt`() {
        // A deleted-and-recreated release, or a sideloaded build newer than the
        // published one. Prompting here would offer an install that fails on the
        // version code, and the prompt would never go away.
        assertThat(UpdateCheck.evaluate("2.0.0", "v1.0.0", url)).isEqualTo(UpdateStatus.UpToDate)
    }

    @Test
    fun `an unparseable tag offers nothing`() {
        assertThat(UpdateCheck.evaluate("1.0.0", "nightly", url)).isEqualTo(UpdateStatus.Unknown)
        assertThat(UpdateCheck.evaluate("1.0.0", null, url)).isEqualTo(UpdateStatus.Unknown)
    }

    @Test
    fun `a release with no url offers nothing`() {
        // There would be nowhere to send the agent, so "an update exists" is
        // useless information.
        assertThat(UpdateCheck.evaluate("1.0.0", "v2.0.0", releaseUrl = null))
            .isEqualTo(UpdateStatus.Unknown)
        assertThat(UpdateCheck.evaluate("1.0.0", "v2.0.0", releaseUrl = "  "))
            .isEqualTo(UpdateStatus.Unknown)
    }

    @Test
    fun `an unreadable installed version offers nothing`() {
        assertThat(UpdateCheck.evaluate("not-a-version", "v2.0.0", url))
            .isEqualTo(UpdateStatus.Unknown)
    }

    @Test
    fun `the shipped version name parses`() {
        // Guards the release process, not the parser: versionName in
        // build.gradle.kts is compared against a GitHub tag at runtime, so a
        // version like "1.0.0-phase0" (which this project shipped until Phase 11)
        // would silently disable update checks on the agent's phone.
        assertThat(AppVersion.parse(com.scopesms.autoreply.BuildConfig.VERSION_NAME)).isNotNull()
    }
}
