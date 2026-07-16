package com.tricreta.scopesms.domain.update

/** The result of comparing a downloaded APK's signing cert to the installed app's. */
enum class SignatureVerdict { MATCH, MISMATCH, CANT_VERIFY }

/**
 * Decides, purely, whether a downloaded APK is signed by the same key as the
 * running app — kept out of the Android layer so the decision is JVM-testable.
 *
 * [SignatureVerdict.CANT_VERIFY] (the platform couldn't read the archive's certs
 * on this ROM, which some low-end OEM builds get wrong even for a valid APK) is a
 * soft outcome: the system installer enforces signatures itself at install time,
 * so this check is early, friendly defense-in-depth, not the only gate. A
 * *readable* [SignatureVerdict.MISMATCH], however, is blocked here with a clear
 * message instead of the cryptic system-installer error the agent would hit.
 */
object SignatureMatch {

    fun verdict(installed: Set<String>, archive: Set<String>?): SignatureVerdict {
        if (archive.isNullOrEmpty() || installed.isEmpty()) return SignatureVerdict.CANT_VERIFY
        return if (archive.any { it in installed }) SignatureVerdict.MATCH else SignatureVerdict.MISMATCH
    }
}
