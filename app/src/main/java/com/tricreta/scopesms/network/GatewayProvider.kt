package com.tricreta.scopesms.network

/**
 * Which SMS gateway account a message goes through.
 *
 * The client is adding HostPinnacle as a second, independently-selectable
 * gateway alongside the original BlazeTech ("SCOPE SMS API") integration —
 * BlazeTech is live in production and must keep working exactly as it does
 * today (CLAUDE.md constraint 9), so this is additive, not a replacement.
 *
 * Each provider has its own API key + sender ID
 * ([com.tricreta.scopesms.data.settings.GatewayCredentialsStore]), its own
 * [SmsGateway] client, and jobs remember which one they were created under
 * ([com.tricreta.scopesms.queue.OutboundJob.provider]) — see that field's doc
 * for why "the job's own captured provider", not "whichever is active now",
 * is the correct read at send time.
 */
enum class GatewayProvider {
    BLAZETECH,
    HOSTPINNACLE,
    ;

    companion object {
        /**
         * BlazeTech, unconditionally. Load-bearing: every install that existed
         * before this feature shipped must keep behaving exactly as before —
         * sending through BlazeTech — until the agent deliberately switches in
         * Settings. Both [fromName]'s fallback and
         * [com.tricreta.scopesms.data.settings.SettingsRepository]'s
         * `activeGatewayProvider` default route through this constant.
         */
        val DEFAULT = BLAZETECH

        /**
         * Decodes a stored/queued provider name, defaulting to [DEFAULT] for
         * `null` or anything unrecognised (a value from a future app version
         * rolled back onto this one, or plain corruption) rather than throwing.
         *
         * `null` is not an edge case here — it is what every
         * [com.tricreta.scopesms.queue.OutboundJob] queued before this release
         * has stored, and it must read as BlazeTech, the only gateway that
         * existed then.
         */
        fun fromName(name: String?): GatewayProvider = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
