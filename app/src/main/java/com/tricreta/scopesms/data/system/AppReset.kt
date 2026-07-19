package com.tricreta.scopesms.data.system

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.WorkManager
import java.security.KeyStore

/**
 * Wipes the app back to a first-install state, in one action, without the agent
 * having to find "Clear data" in Android Settings or uninstall/reinstall.
 *
 * This is the "things got messy, start over" button. It is deliberately total:
 * prices, message templates, the activity log, the outbound queue, SIM choice,
 * theme, the two reply toggles, the onboarding flag, and the encrypted gateway
 * credentials all go. On the next launch the app runs onboarding as if freshly
 * installed.
 *
 * The heavy lifting is [ActivityManager.clearApplicationUserData], which erases
 * the entire data directory (Room DBs, both DataStores, files, caches, and
 * WorkManager's own database) and then kills the process — the only reliable way
 * to also drop the in-memory singletons (`AppDatabase.instance`, the caches).
 * Two things it does NOT do, handled here first:
 *  - the Android Keystore key survives a data wipe, so it is deleted explicitly;
 *  - the process won't relaunch itself, so a one-shot alarm is armed to reopen it.
 */
class AppReset(private val context: Context) {

    /**
     * Deletes everything and restarts. **Does not return on success** — the
     * process is terminated by the data wipe.
     */
    fun wipeAndRestart() {
        // 1) The Keystore entry that encrypts the gateway credentials is NOT part
        //    of the app data directory, so clearApplicationUserData leaves it.
        //    Delete it so the reset is truly total.
        runCatching {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(KEY_ALIAS)
        }.onFailure { Log.w(TAG, "Could not delete the Keystore key during reset.", it) }

        // 2) Cancel any scheduled drains. clearApplicationUserData wipes
        //    WorkManager's DB too, but cancelling first avoids a racing wakeup.
        runCatching { WorkManager.getInstance(context).cancelAllWork() }
            .onFailure { Log.w(TAG, "Could not cancel work during reset.", it) }

        // 3) Arm a relaunch just after the wipe kills us, so the app reopens on
        //    its own into fresh onboarding. Best-effort: if the OEM drops the
        //    alarm with the app data, the agent simply taps the icon again.
        armRestart()

        // 4) Erase all app data and terminate the process. On next launch the app
        //    is as if freshly installed.
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val cleared = runCatching { activityManager.clearApplicationUserData() }.getOrDefault(false)
        if (!cleared) {
            // Extremely rare (some restricted profiles). At least drop the process
            // so the in-memory singletons rebuild on next launch.
            Log.w(TAG, "clearApplicationUserData returned false; exiting the process.")
            Runtime.getRuntime().exit(0)
        }
    }

    private fun armRestart() {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK) }
            ?: return
        val pending = PendingIntent.getActivity(
            context,
            RESTART_REQUEST_CODE,
            launch,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE,
        )
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        runCatching {
            alarm.set(AlarmManager.RTC, System.currentTimeMillis() + RESTART_DELAY_MS, pending)
        }.onFailure { Log.w(TAG, "Could not arm the post-reset relaunch.", it) }
    }

    private companion object {
        const val TAG = "ScopeSms/Reset"

        // Must match KeystoreCrypto in GatewayCredentialsStore. Duplicated so a
        // reset needs no dependency on the credentials store's internals.
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "scope_sms_gateway_credentials"

        const val RESTART_REQUEST_CODE = 44_910
        const val RESTART_DELAY_MS = 400L
    }
}
