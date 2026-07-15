package com.scopesms.autoreply.reliability

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.scopesms.autoreply.domain.reliability.OemAutostartGuide
import com.scopesms.autoreply.domain.reliability.OemDeepLink
import com.scopesms.autoreply.domain.reliability.OemGuidance

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
     * Try to open the OEM's autostart screen.
     *
     * @return true if a screen was opened. False means the UI should fall back
     *   to the written instructions — which it should be showing anyway.
     */
    fun open(launchContext: Context): Boolean {
        val intent = resolvedIntent ?: return false

        return try {
            launchContext.startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: Exception) {
            // Deliberately Exception, not ActivityNotFoundException.
            //
            // resolveActivity() succeeding does not mean startActivity() will:
            // HiOS/XOS ship system activities that resolve but are not exported,
            // and launching one throws SecurityException, not
            // ActivityNotFoundException. Catching only the latter would crash the
            // app on precisely the Tecno/Infinix handsets this screen exists for
            // — while the agent is trying to make the app more reliable.
            Log.w(TAG, "OEM autostart screen resolved but would not open: ${intent.component}", e)
            false
        }
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
