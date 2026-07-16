package com.scopesms.autoreply.ui.common

import android.content.ActivityNotFoundException
import android.content.Context
import android.util.Log
import android.widget.Toast
import com.scopesms.autoreply.data.system.BatteryOptimizationManager

/**
 * Asks for the battery exemption, falling back to the system list.
 *
 * Lifted out of Phase 1's `SetupScreen` so Settings and onboarding share one
 * copy — the fallback chain below is the kind of thing that gets half-remembered
 * when it's duplicated.
 *
 * The one-tap dialog doesn't exist on every OEM build, so
 * [ActivityNotFoundException] is an expected outcome, not an edge case — and the
 * fallback can be missing too, hence the second catch. Failing silently here
 * would leave the agent tapping a dead button on exactly the setting that keeps
 * the app alive in the background.
 */
fun Context.requestBatteryExemption(manager: BatteryOptimizationManager) {
    try {
        startActivity(manager.requestExemptionIntent())
    } catch (e: ActivityNotFoundException) {
        Log.i(TAG, "No battery-exemption dialog on this device; opening the settings list.", e)
        try {
            startActivity(manager.settingsListIntent())
        } catch (e2: ActivityNotFoundException) {
            Log.w(TAG, "No battery-optimisation settings screen either.", e2)
            Toast.makeText(
                this,
                "Open Settings › Apps › Scope SMS › Battery and allow background activity.",
                Toast.LENGTH_LONG,
            ).show()
        }
    }
}

private const val TAG = "ScopeSms/Battery"
