package com.tricreta.scopesms.data.system

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.net.toUri

/**
 * Reads and requests exemption from Android's battery optimisations (Doze /
 * App Standby).
 *
 * ### Why this matters here more than in most apps
 * Scope SMS has no foreground service by design (CLAUDE.md constraint 6) — it
 * lives or dies by a manifest broadcast receiver waking a dead process, plus
 * (Phase 5b) a WorkManager job that has to actually run. Both are exactly what
 * aggressive battery management defers or kills, and the target market is the
 * OEM builds most notorious for it: Transsion (Tecno/Infinix/itel), Xiaomi,
 * Oppo. Without the exemption the app appears to work all day and then silently
 * stops replying once the screen has been off a while.
 *
 * The exemption is necessary but **not sufficient** on those devices — they
 * layer their own "autostart"/"protected apps" lists on top, which no API can
 * read or request. That's Phase 9's in-app guidance problem.
 */
class BatteryOptimizationManager(private val context: Context) {

    private val powerManager: PowerManager?
        get() = ContextCompat.getSystemService(context, PowerManager::class.java)

    /**
     * Whether the app is currently exempt.
     *
     * Read live on every call, never cached. The agent can revoke this in
     * system settings and OEM battery managers can revoke it unprompted; a
     * cached value would leave the UI asserting the app is protected while it
     * is being killed. See the note in `SettingsRepository`.
     *
     * Returns `true` when PowerManager is unavailable — a device with no power
     * manager is not one that's about to Doze us, and a false "you are not
     * protected" warning the agent cannot act on is worse than no warning.
     */
    fun isExempt(): Boolean {
        val manager = powerManager ?: run {
            Log.w(TAG, "PowerManager unavailable; assuming exempt.")
            return true
        }
        return manager.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Intent for the system dialog that grants the exemption in one tap.
     *
     * Requires REQUEST_IGNORE_BATTERY_OPTIMIZATIONS in the manifest, and the
     * `package:` URI — without it the system shows the full app list instead of
     * a prompt for this app.
     *
     * Not every OEM ships the dialog this resolves to, so callers must handle
     * [android.content.ActivityNotFoundException] and fall back to
     * [settingsListIntent].
     */
    fun requestExemptionIntent(): Intent =
        Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            "package:${context.packageName}".toUri(),
        )

    /**
     * Fallback: the system's battery-optimisation list, where the agent finds
     * the app themselves. More taps, but it exists on far more devices than the
     * one-tap dialog above.
     */
    fun settingsListIntent(): Intent =
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    private companion object {
        const val TAG = "BatteryOptimization"
    }
}
