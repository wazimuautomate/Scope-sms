package com.tricreta.scopesms.reliability

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.tricreta.scopesms.domain.reliability.OemAutostartGuide
import com.tricreta.scopesms.domain.reliability.OemDeepLink
import com.tricreta.scopesms.domain.reliability.OemGuidance

/**
 * Finds and opens this phone's autostart screen, if it has one we can reach.
 *
 * ### The contract is the instructions, not the link
 * Read `OemAutostartGuide` first — every component name it lists is an
 * undocumented vendor internal that can be renamed or removed by any OTA, and
 * the Transsion ones (the market that matters most here) are explicitly
 * unconfirmed. This class is therefore built to fail: it probes each candidate,
 * discards the ones that aren't there, and reports honestly when none work so
 * the UI can fall back to the written steps. A deep link that lands is a
 * convenience; the steps are what actually gets the agent's phone configured.
 */
class OemSettingsLauncher(context: Context) {

    private val appContext = context.applicationContext

    /** Instructions for this phone. Always available, deep link or not. */
    val guidance: OemGuidance = OemAutostartGuide.guidanceFor(Build.BRAND, Build.MANUFACTURER)

    /**
     * The first candidate that actually resolves on this device, or null.
     *
     * Computed once. The set of installed system apps cannot change without a
     * reboot or an OTA, either of which restarts the process anyway.
     *
     * A null here is **not** an error — it is the expected result on most
     * phones. It only means "show the written steps without an Open button".
     */
    private val resolvedIntent: Intent? by lazy { firstResolvable() }

    /** Whether there is a shortcut worth showing a button for. */
    fun hasDeepLink(): Boolean = resolvedIntent != null

    /**
     * Open something useful — the OEM autostart screen if we can reach it, and
     * otherwise this app's own system settings page, which always exists and is
     * where the battery/background controls live on every OEM.
     *
     * This is the fix for the reported bug where the button did nothing. Two
     * things made it dead on a real device:
     *  - **Android 11+ package visibility.** Without a `<queries>` entry,
     *    `resolveActivity` returns null for a vendor component, so `hasDeepLink()`
     *    was false and — worse — even when a button showed, the deep link
     *    couldn't be probed. The manifest now declares the autostart components
     *    under `<queries>`; where that still isn't enough, the fallback covers it.
     *  - **Silent failure.** The old method returned false and left the agent
     *    tapping a button that visibly did nothing. Now there is always a screen
     *    to land on, so the tap always does *something*.
     *
     * @return true if any screen opened. False only if even the universal
     *   app-details page is unreachable, which effectively never happens.
     */
    fun open(launchContext: Context): Boolean {
        resolvedIntent?.let { intent ->
            try {
                launchContext.startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (e: Exception) {
                // Deliberately Exception, not ActivityNotFoundException.
                // resolveActivity() succeeding does not mean startActivity() will:
                // HiOS/XOS ship system activities that resolve but are not
                // exported, and launching one throws SecurityException. Fall
                // through to the app-details page rather than giving up.
                Log.w(TAG, "OEM autostart screen would not open: ${intent.component}", e)
            }
        }
        return openAppDetails(launchContext)
    }

    /**
     * This app's entry in system Settings. Universal, and on most OEMs it is one
     * tap from the battery/autostart toggles the agent needs — a good landing
     * spot when the direct vendor screen isn't reachable.
     */
    private fun openAppDetails(launchContext: Context): Boolean = try {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", appContext.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        launchContext.startActivity(intent)
        true
    } catch (e: Exception) {
        Log.w(TAG, "Could not open app details settings.", e)
        false
    }

    private fun firstResolvable(): Intent? {
        for (link in guidance.deepLinks) {
            val intent = link.toIntent()
            if (intent.resolveActivity(appContext.packageManager) != null) {
                Log.i(TAG, "OEM autostart screen found: $link")
                return intent
            }
        }
        // Normal on stock Android and on any device whose vendor renamed things.
        // Logged at info, not warn: this is a supported outcome, not a fault.
        Log.i(TAG, "No OEM autostart screen resolved for ${guidance.family}; instructions only.")
        return null
    }

    private fun OemDeepLink.toIntent(): Intent = when (this) {
        is OemDeepLink.Component ->
            Intent().setComponent(ComponentName(packageName, className))

        is OemDeepLink.Action ->
            // CATEGORY_DEFAULT is required for an implicit action to match a
            // vendor activity's intent filter; without it these silently fail to
            // resolve.
            Intent(action).addCategory(Intent.CATEGORY_DEFAULT)
    }

    private companion object {
        const val TAG = "OemSettingsLauncher"
    }
}
