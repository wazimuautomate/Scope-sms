package com.tricreta.scopesms.domain.settings

/**
 * How the agent wants the app coloured.
 *
 * [SYSTEM] is the default and the one most agents will never change: the app
 * follows the phone's own light/dark setting, so it matches everything else on
 * their device. [LIGHT] and [DARK] pin it regardless of the system — some agents
 * work in bright sun and want dark forced off, or the reverse.
 */
enum class ThemePreference {
    /** Follow the phone's light/dark setting. The default. */
    SYSTEM,

    /** Always light, whatever the phone is set to. */
    LIGHT,

    /** Always dark, whatever the phone is set to. */
    DARK,
    ;

    companion object {
        val DEFAULT = SYSTEM

        /** Decodes a stored name, falling back to [DEFAULT] for anything unknown. */
        fun decode(stored: String?): ThemePreference =
            entries.firstOrNull { it.name == stored } ?: DEFAULT
    }
}
