package com.tricreta.scopesms.domain.update

/**
 * SHA-256 hex helpers, pure so the update verifier's checks are JVM-testable —
 * a wrong hex compare would either accept a tampered APK or reject a good one,
 * and both are worth a test that needs no device.
 */
object Sha256 {

    private val HEX_64 = Regex("[0-9a-fA-F]{64}")

    /** True only for a well-formed 64-character SHA-256 hex string. */
    fun isValidHex(value: String?): Boolean = value != null && HEX_64.matches(value)

    /** Case-insensitive compare of two hex digests. */
    fun matches(expected: String, actual: String): Boolean = expected.equals(actual, ignoreCase = true)
}

private val HEX_DIGITS = "0123456789abcdef".toCharArray()

/** Lowercase hex of these bytes — used for the downloaded APK's digest and certs. */
fun ByteArray.toHex(): String {
    val out = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xFF
        out.append(HEX_DIGITS[v ushr 4])
        out.append(HEX_DIGITS[v and 0x0F])
    }
    return out.toString()
}
