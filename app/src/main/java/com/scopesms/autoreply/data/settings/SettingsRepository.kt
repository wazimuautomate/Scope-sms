package com.scopesms.autoreply.data.settings

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.scopesms.autoreply.domain.sim.SimSelection
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
