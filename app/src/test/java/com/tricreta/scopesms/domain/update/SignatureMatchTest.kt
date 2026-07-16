package com.tricreta.scopesms.domain.update

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SignatureMatchTest {

    private val certA = "aa11"
    private val certB = "bb22"

    @Test
    fun `same cert matches`() {
        assertThat(SignatureMatch.verdict(installed = setOf(certA), archive = setOf(certA)))
            .isEqualTo(SignatureVerdict.MATCH)
    }

    @Test
    fun `overlapping certs match (key rotation history)`() {
        assertThat(SignatureMatch.verdict(installed = setOf(certA, certB), archive = setOf(certB)))
            .isEqualTo(SignatureVerdict.MATCH)
    }

    @Test
    fun `disjoint certs mismatch`() {
        assertThat(SignatureMatch.verdict(installed = setOf(certA), archive = setOf(certB)))
            .isEqualTo(SignatureVerdict.MISMATCH)
    }

    @Test
    fun `null archive certs cannot be verified`() {
        assertThat(SignatureMatch.verdict(installed = setOf(certA), archive = null))
            .isEqualTo(SignatureVerdict.CANT_VERIFY)
    }

    @Test
    fun `empty sets cannot be verified`() {
        assertThat(SignatureMatch.verdict(installed = emptySet(), archive = setOf(certA)))
            .isEqualTo(SignatureVerdict.CANT_VERIFY)
        assertThat(SignatureMatch.verdict(installed = setOf(certA), archive = emptySet()))
            .isEqualTo(SignatureVerdict.CANT_VERIFY)
    }
}
