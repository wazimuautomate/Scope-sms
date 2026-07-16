package com.tricreta.scopesms.domain.update

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The whole update decision, on the JVM. Getting the versionCode comparison
 * wrong in one direction nags the agent forever with an update they already
 * have; wrong in the other and they never hear about a fix.
 */
class UpdateResolverTest {

    // SHA-256 of the empty input — a real, well-formed 64-hex digest.
    private val validSha = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    private val url = "https://github.com/wazimuautomate/Scope-sms/releases/download/v1.0.1/scope-sms-release-v1.0.1.apk"

    private fun resolve(
        installed: Long = 1,
        code: Long? = 2,
        name: String? = "1.0.1",
        apkUrl: String? = url,
        sha: String? = validSha,
        notes: String? = "Fixes",
        required: Boolean? = false,
        minSupported: Long? = 1,
    ) = UpdateResolver.resolve(installed, code, name, apkUrl, sha, notes, required, minSupported)

    @Test
    fun `newer versionCode is available`() {
        val result = resolve(installed = 1, code = 2)
        assertThat(result).isInstanceOf(UpdateResolution.Available::class.java)
        val available = result as UpdateResolution.Available
        assertThat(available.target.versionCode).isEqualTo(2)
        assertThat(available.target.versionName).isEqualTo("1.0.1")
        assertThat(available.forced).isFalse()
    }

    @Test
    fun `equal versionCode is up to date`() {
        assertThat(resolve(installed = 5, code = 5)).isEqualTo(UpdateResolution.UpToDate)
    }

    @Test
    fun `older manifest versionCode is up to date, never a downgrade`() {
        assertThat(resolve(installed = 5, code = 3)).isEqualTo(UpdateResolution.UpToDate)
    }

    @Test
    fun `required flag forces the update`() {
        val result = resolve(installed = 1, code = 2, required = true) as UpdateResolution.Available
        assertThat(result.forced).isTrue()
    }

    @Test
    fun `installed below the minimum supported forces the update`() {
        val result = resolve(installed = 1, code = 3, required = false, minSupported = 2) as UpdateResolution.Available
        assertThat(result.forced).isTrue()
    }

    @Test
    fun `at or above the minimum is not forced`() {
        val result = resolve(installed = 2, code = 3, required = false, minSupported = 2) as UpdateResolution.Available
        assertThat(result.forced).isFalse()
    }

    @Test
    fun `first install from nothing sees the update`() {
        assertThat(resolve(installed = 0, code = 1)).isInstanceOf(UpdateResolution.Available::class.java)
    }

    @Test
    fun `null versionCode is unknown`() {
        assertThat(resolve(code = null)).isEqualTo(UpdateResolution.Unknown)
    }

    @Test
    fun `blank apk url is unknown, never up to date`() {
        assertThat(resolve(apkUrl = "  ")).isEqualTo(UpdateResolution.Unknown)
    }

    @Test
    fun `malformed sha256 is unknown`() {
        assertThat(resolve(sha = "not-a-hash")).isEqualTo(UpdateResolution.Unknown)
        assertThat(resolve(sha = "abc123")).isEqualTo(UpdateResolution.Unknown)
        assertThat(resolve(sha = null)).isEqualTo(UpdateResolution.Unknown)
    }

    @Test
    fun `blank version name is unknown`() {
        assertThat(resolve(name = "")).isEqualTo(UpdateResolution.Unknown)
    }

    @Test
    fun `uppercase sha is accepted and normalised to lowercase`() {
        val result = resolve(installed = 1, code = 2, sha = validSha.uppercase()) as UpdateResolution.Available
        assertThat(result.target.sha256).isEqualTo(validSha)
    }

    @Test
    fun `blank release notes become null`() {
        val result = resolve(installed = 1, code = 2, notes = "   ") as UpdateResolution.Available
        assertThat(result.target.releaseNotes).isNull()
    }
}
