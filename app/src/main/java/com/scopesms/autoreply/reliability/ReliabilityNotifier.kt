package com.scopesms.autoreply.reliability

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.scopesms.autoreply.MainActivity
import com.scopesms.autoreply.R
import com.scopesms.autoreply.domain.reliability.ReliabilityIssue

/**
 * Tells the agent, out of band, that Scope SMS has stopped working properly.
 *
 * ### Why a notification and not an in-app banner
 * Every failure Phase 9 hardens against happens while the app is closed —
 * that's the whole shape of the problem. There is no foreground service
 * (CLAUDE.md constraint 6) and no screen to put a banner on. An agent whose
 * app has silently stopped ingesting has no reason to open it: from their side
 * a broken app and a slow business day look exactly alike. A notification is
 * the only channel that reaches them.
 */
class ReliabilityNotifier(context: Context) {

    private val appContext = context.applicationContext

    private val notificationManager = NotificationManagerCompat.from(appContext)

    /**
     * Post a warning for [issues], or clear a previous one if the list is empty.
     *
     * Clearing matters: an agent who fixes the problem and reboots should see
     * the warning disappear on its own. A health warning that outlives the
     * problem it describes teaches people to ignore health warnings.
     */
    fun notifyOfIssues(issues: List<ReliabilityIssue>) {
        if (issues.isEmpty()) {
            notificationManager.cancel(NOTIFICATION_ID)
            return
        }

        // On API 33+ posting without the runtime grant is silently dropped by
        // the platform. Checked rather than attempted, so the "we tried to warn
        // them and couldn't" case is visible in logcat instead of vanishing.
        if (!canPostNotifications()) {
            Log.w(TAG, "Cannot warn agent: POST_NOTIFICATIONS not granted. Issues: ${issues.map { it.title }}")
            return
        }

        // Lead with the worst — the list is sorted worst-first by
        // ReliabilityCheck, and BLOCKING means payments are being missed right
        // now, while DEGRADED only means they will be later.
        val lead = issues.first()
        val body = buildString {
            append(lead.detail)
            if (issues.size > 1) {
                append("\n\n")
                append(
                    if (issues.size == 2) {
                        "There is also 1 other problem — open Scope SMS to see it."
                    } else {
                        "There are also ${issues.size - 1} other problems — open Scope SMS to see them."
                    },
                )
            }
        }

        createChannel()

        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_warning)
            .setContentTitle(lead.title)
            .setContentText(lead.detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .build()

        try {
            notificationManager.notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // canPostNotifications() should make this unreachable, but this runs
            // on OEM builds with their own notification gatekeeping, and this
            // method is called from a BroadcastReceiver — an uncaught throw here
            // would be an ANR-adjacent crash on boot.
            Log.w(TAG, "Denied posting reliability warning despite holding the permission.", e)
        }
    }

    private fun canPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Created on demand, and safe to call repeatedly — re-creating an existing
     * channel is a no-op that cannot override the agent's own settings for it.
     *
     * [NotificationManager.IMPORTANCE_HIGH] is deliberate, and it is the one
     * choice here worth arguing about. It means a heads-up popup on boot, which
     * is intrusive. It earns that: this notification only ever fires when the
     * agent is *already* losing money — either payments are being missed now, or
     * the phone is configured such that they will be by tonight. A warning of
     * that kind sitting unnoticed in the shade is the same as no warning.
     *
     * It is also self-limiting: a healthy device posts nothing, and fixing the
     * cause stops it permanently. If an agent disagrees, the channel is theirs
     * to silence in system settings — which is exactly why this is a channel and
     * not a dialog.
     */
    private fun createChannel() {
        // Literal strings rather than R.string, following the convention Phase 1
        // set in SetupScreen. This app ships to one agent, in English, and has
        // no localisation plan; a resource indirection here would buy nothing
        // and cost a merge conflict in a file every parallel phase is editing.
        val channel = NotificationChannel(
            CHANNEL_ID,
            "App health",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Warns you if Scope SMS has stopped watching for M-Pesa payments."
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(appContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

        return PendingIntent.getActivity(
            appContext,
            /* requestCode = */ 0,
            intent,
            // IMMUTABLE is mandatory from API 31 and correct everywhere: nothing
            // receiving this intent has any business rewriting it.
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    companion object {
        private const val TAG = "ReliabilityNotifier"

        /**
         * Phase 9 owns this channel. Phase 8's send-failure alerts are a
         * *different* concern and should get their own — an agent who mutes
         * "a reply failed to send" must not thereby mute "the app has stopped
         * working". Reuse this id only for app-health warnings.
         */
        const val CHANNEL_ID = "health"

        /**
         * Fixed, so repeated checks replace the warning rather than stacking a
         * new one on every reboot.
         */
        private const val NOTIFICATION_ID = 1001
    }
}
