package com.tricreta.scopesms.domain.update

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class Sha256Test {

    private val valid = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

    @Test
    fun `valid 64-hex is accepted`() {
        assertThat(Sha256.isValidHex(valid)).isTrue()
        assertThat(Sha256.isValidHex(valid.uppercase())).isTrue()
    }

    @Test
    fun `wrong length or non-hex is rejected`() {
        assertThat(Sha256.isValidHex(null)).isFalse()
        assertThat(Sha256.isValidHex("")).isFalse()
        assertThat(Sha256.isValidHex("abc123")).isFalse()
        assertThat(Sha256.isValidHex(valid.dropLast(1))).isFalse()
        assertThat(Sha256.isValidHex(valid.dropLast(1) + "z")).isFalse()
    }

    @Test
    fun `matches is case insensitive`() {
        assertThat(Sha256.matches(valid, valid.uppercase())).isTrue()
        assertThat(Sha256.matches(valid, valid)).isTrue()
    }

    @Test
    fun `matches rejects a different digest`() {
        assertThat(Sha256.matches(valid, "f".repeat(64))).isFalse()
    }

    @Test
    fun `toHex renders bytes as lowercase hex`() {
        val bytes = byteArrayOf(0x00, 0xFF.toByte(), 0x10, 0x0a)
        assertThat(bytes.toHex()).isEqualTo("00ff100a")
    }

    @Test
    fun `toHex of empty bytes is empty`() {
        assertThat(ByteArray(0).toHex()).isEqualTo("")
    }
}
