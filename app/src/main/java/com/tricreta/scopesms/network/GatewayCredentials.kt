package com.tricreta.scopesms.network

/**
 * The agent's gateway credentials, held only for the duration of a send.
 *
 * These are secrets (CLAUDE.md constraint 7): never hardcoded, never committed,
 * never logged in plaintext.
 *
 * @param apiKey [BlazeTechGateway]'s API key. For [HostPinnacleGateway] this
 *   field holds the account **password** instead — verified live (2026-08-07)
 *   that HostPinnacle's apikey-header auth mode does not authenticate this
 *   client's account at all, while its userid+password mode does; see
 *   [userId]. Reusing this field rather than adding a separate `password`
 *   field keeps [GatewayCredentialsStore]'s per-provider storage to one shape
 *   ("the provider's one secret, plus a sender ID, plus an optional login
 *   name") instead of a field that's meaningful for one provider and dead for
 *   the other.
 * @param userId HostPinnacle's `userid` — paired with [apiKey] (the
 *   password) in the request body, per their documented userid+password auth
 *   mode. Always `null` for [BlazeTechGateway], which has no such concept.
 */
data class GatewayCredentials(
    val apiKey: String,
    val senderId: String,
    val userId: String? = null,
) {
    /** Guards against the key/password reaching logcat via a stack trace or a stray log call. */
    override fun toString(): String = "GatewayCredentials(senderId=$senderId, userId=$userId, apiKey=***)"
}

/**
 * Where a gateway client ([BlazeTechGateway], [HostPinnacleGateway]) gets its
 * credentials at call time.
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
