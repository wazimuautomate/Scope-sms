package com.scopesms.autoreply.network

/**
 * The agent's gateway credentials, held only for the duration of a send.
 *
 * These are secrets (CLAUDE.md constraint 7): never hardcoded, never committed,
 * never logged in plaintext.
 */
data class GatewayCredentials(
    val apiKey: String,
    val senderId: String,
) {
    /** Guards against the key reaching logcat via a stack trace or a stray log call. */
    override fun toString(): String = "GatewayCredentials(senderId=$senderId, apiKey=***)"
}

/**
 * Where [ScopeSmsGateway] gets the credentials at call time.
 *
 * This is a **port, deliberately left unimplemented in Phase 5.** Storage is
 * still an open decision in `memory.md` (open decision 1): the obvious choice,
 * `androidx.security:security-crypto`, is fully deprecated and has known
 * keyset-corruption crashes on exactly this app's target OEMs
 * (Tecno/Infinix/itel/Xiaomi) — a corrupted keyset means the agent's
 * credentials are unrecoverable and replies stop going out.
 *
 * Phase 5 does not need that decision made to be correct or testable, so it
 * doesn't force it. BUILD-PLAN assigns the Settings UI that captures these to
 * Phase 6/7; whichever phase implements this interface owns the storage choice
 * and records it in `memory.md`.
 *
 * **Whoever implements it:** a decrypt failure must prompt re-entry in
 * Settings. It must not crash, and it must not silently stop sending — an agent
 * whose replies quietly stopped is the worst outcome this app has.
 */
interface GatewayCredentialsProvider {

    /**
     * Returns the stored credentials, or `null` if the agent hasn't completed
     * gateway setup yet — a normal state before onboarding finishes, not an
     * error. The queue treats it as terminal (there is nothing to retry
     * against) and surfaces it rather than looping.
     */
    suspend fun credentials(): GatewayCredentials?
}
