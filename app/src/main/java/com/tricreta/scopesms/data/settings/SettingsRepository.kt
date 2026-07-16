package com.tricreta.scopesms.data.settings

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.tricreta.scopesms.domain.notifications.NotificationToggles
import com.tricreta.scopesms.domain.settings.ThemePreference
import com.tricreta.scopesms.domain.sim.SimSelection
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

/**
 * Non-secret settings, persisted with DataStore.
 *
 * **Not for the gateway API key or sender ID.** Those are secrets (CLAUDE.md
 * constraint 7) and belong in encrypted storage, whose mechanism is still an
 * open decision — see memory.md before adding them here.
 */
class SettingsRepository(private val dataStore: DataStore<Preferences>) {

    /**
     * Last value read from disk, or null before the first read.
     *
     * This exists for CLAUDE.md constraint 5: the SIM filter runs on the
     * detect-and-decide path, which must survive ~10 SMS arriving in 1–3
     * seconds without blocking. DataStore keeps its own in-memory cache after
     * the first read, so the marginal read is already cheap — but it is still a
     * suspending call through a Flow, and this path deserves to be plainly,
     * unarguably synchronous rather than cheap-in-practice.
     *
     * `@Volatile` because it is written on whichever thread collects
     * [simSelection] and read on the binder thread the receiver runs on.
     */
    @Volatile
    private var cachedSimSelection: SimSelection? = null

    /**
     * Which SIM(s) to watch. Emits the current value immediately, then on every
     * change.
     */
    val simSelection: Flow<SimSelection> = dataStore.data
        .safe()
        .map { SimSelection.decode(it[KEY_SIM_SELECTION]) }
        .onEach { cachedSimSelection = it }

    /** Whether the agent has completed the setup flow. */
    val onboardingComplete: Flow<Boolean> = dataStore.data
        .safe()
        .map { it[KEY_ONBOARDING_COMPLETE] ?: false }

    /**
     * Light/dark/system choice. Emits the current value immediately, then on
     * every change, so the whole app recolours the moment the agent picks a new
     * option in Settings.
     */
    val themePreference: Flow<ThemePreference> = dataStore.data
        .safe()
        .map { ThemePreference.decode(it[KEY_THEME_PREFERENCE]) }

    /**
     * Last toggles read from disk, or null before the first read.
     *
     * Cached for the same reason as [cachedSimSelection]: the decision path reads
     * these once per incoming payment and must not block on I/O while ~10 SMS
     * land in 1–3 seconds (CLAUDE.md constraint 5).
     */
    @Volatile
    private var cachedToggles: NotificationToggles? = null

    /**
     * Which reply flows are switched on. Emits the current value immediately,
     * then on every change.
     *
     * Phase 6. Both keys are read from one `Preferences` snapshot, so the pair is
     * always internally consistent — see [NotificationToggles].
     */
    val notificationToggles: Flow<NotificationToggles> = dataStore.data
        .safe()
        .map { prefs ->
            NotificationToggles(
                unmatchedReplyEnabled = prefs[KEY_UNMATCHED_REPLY_ENABLED]
                    ?: NotificationToggles.DEFAULT.unmatchedReplyEnabled,
                matchedReplyEnabled = prefs[KEY_MATCHED_REPLY_ENABLED]
                    ?: NotificationToggles.DEFAULT.matchedReplyEnabled,
            )
        }
        .onEach { cachedToggles = it }

    /**
     * The SIM selection, for callers on the hot path.
     *
     * Returns the cached value with no I/O once anything has read
     * [simSelection] in this process (in practice: always, since
     * `ScopeSmsApplication` starts collecting it at process start, including on
     * the headless start an incoming SMS triggers). Falls back to a real read
     * only on a genuinely cold first call.
     */
    suspend fun currentSimSelection(): SimSelection =
        cachedSimSelection ?: simSelection.first()

    suspend fun setSimSelection(selection: SimSelection) {
        // Update the cache before awaiting the disk write, so an SMS landing in
        // the same millisecond as the agent's tap is filtered by the new choice
        // rather than the old one.
        cachedSimSelection = selection
        dataStore.edit { it[KEY_SIM_SELECTION] = SimSelection.encode(selection) }
    }

    suspend fun setOnboardingComplete(complete: Boolean) {
        dataStore.edit { it[KEY_ONBOARDING_COMPLETE] = complete }
    }

    suspend fun setThemePreference(preference: ThemePreference) {
        dataStore.edit { it[KEY_THEME_PREFERENCE] = preference.name }
    }

    /**
     * The toggles, for callers on the hot path. No I/O once anything in this
     * process has read [notificationToggles] — see [currentSimSelection].
     */
    suspend fun currentNotificationToggles(): NotificationToggles =
        cachedToggles ?: notificationToggles.first()

    suspend fun setUnmatchedReplyEnabled(enabled: Boolean) {
        // Cache first, then persist — as with setSimSelection, so a payment
        // landing in the same millisecond as the agent's tap is judged by the
        // choice they just made rather than the one they just revoked. Turning a
        // flow OFF is the direction that matters: the agent tapping it off wants
        // it off *now*, not after a disk write settles.
        cachedToggles = currentNotificationToggles().copy(unmatchedReplyEnabled = enabled)
        dataStore.edit { it[KEY_UNMATCHED_REPLY_ENABLED] = enabled }
    }

    suspend fun setMatchedReplyEnabled(enabled: Boolean) {
        cachedToggles = currentNotificationToggles().copy(matchedReplyEnabled = enabled)
        dataStore.edit { it[KEY_MATCHED_REPLY_ENABLED] = enabled }
    }

    /**
     * Survive a corrupt or unreadable preferences file by falling back to
     * defaults.
     *
     * DataStore surfaces disk failures into the Flow, and an uncaught one here
     * would propagate into the SMS receiver — turning a damaged settings file
     * into total ingestion failure. Defaults mean a wrong SIM filter at worst,
     * which the agent can see and correct in Settings; a thrown exception means
     * an app that has stopped working with no way to tell why. Only [IOException]
     * is swallowed — anything else is a real bug and should be loud.
     */
    private fun Flow<Preferences>.safe(): Flow<Preferences> = catch { cause ->
        if (cause is IOException) {
            Log.w(TAG, "Settings unreadable; falling back to defaults.", cause)
            emit(emptyPreferences())
        } else {
            throw cause
        }
    }

    companion object {
        private const val TAG = "SettingsRepository"

        private val KEY_SIM_SELECTION = stringPreferencesKey("sim_selection")
        private val KEY_ONBOARDING_COMPLETE = booleanPreferencesKey("onboarding_complete")

        // Absent means "never chosen" and decodes to ThemePreference.DEFAULT
        // (SYSTEM) — a missing key must read as "follow the phone", not as a
        // forced light or dark.
        private val KEY_THEME_PREFERENCE = stringPreferencesKey("theme_preference")

        // Phase 6. Absent means "never set" and falls back to
        // NotificationToggles.DEFAULT — which is why these are read with `?:`
        // against the default rather than `?: false`. A missing key must not read
        // as "the agent switched this off".
        private val KEY_UNMATCHED_REPLY_ENABLED = booleanPreferencesKey("unmatched_reply_enabled")
        private val KEY_MATCHED_REPLY_ENABLED = booleanPreferencesKey("matched_reply_enabled")

        // Battery-optimisation exemption is deliberately NOT stored here, even
        // though CLAUDE.md's architecture section lists it among the settings.
        //
        // It is live system state: the agent can revoke it in Android settings
        // at any moment, and an OEM battery manager can revoke it *for* them
        // with no signal to us. A persisted copy would go stale silently, and
        // the Settings screen would then show a confident green "protected"
        // badge for an app the system is actively killing — the exact failure
        // this indicator exists to make visible. It is read live from
        // PowerManager instead: see BatteryOptimizationManager.

        private val Context.dataStoreDelegate by preferencesDataStore(name = "scope_sms_settings")

        /**
         * DataStore requires exactly one instance per file per process — a
         * second one racing the first corrupts the file. The delegate above
         * enforces that; this is the only way to get at it.
         */
        fun create(context: Context): SettingsRepository =
            SettingsRepository(context.applicationContext.dataStoreDelegate)
    }
}
