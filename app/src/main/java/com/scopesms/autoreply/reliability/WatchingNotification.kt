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

/**
 * A quiet, ongoing "Scope SMS is watching" notification.
 *
 * ## Why this exists
 * The agent asked to be able to *see* that the app is running. Everything about
 * this app is invisible by design — no foreground service, no screen open when a
 * payment lands — so from their side "working fine" and "silently dead" look
 * identical. This is the reassurance: a persistent line in the shade that says
 * the app is on watch, tappable to open it.
 *
 * ## Why NOT a foreground service
 * CLAUDE.md constraint 6 bars a persistent foreground service for the detection
 * path, and it is right to: ingestion runs from a manifest receiver that needs
 * no service to be alive. This is only a *notification*, posted with
 * `notify()` — it keeps nothing running and holds no wakelock. It is an
 * indicator, not a lifeline. If the OS or the agent dismisses it, ingestion is
 * unaffected; it is re-posted on the next app open, boot, or processed payment.
 *
 * ## Low importance, on purpose
 * The opposite call from [ReliabilityNotifier]. That one shouts because it only
 * fires when money is being lost. This one must never buzz, pop, or make a
 * sound — it is wallpaper the agent can glance at, and an ongoing notification
 * that interrupted would be a daily annoyance they'd silence, taking the
 * reassurance with it.
 */
class WatchingNotification(context: Context) {

    private val appContext = context.applicationContext
    private val notificationManager = NotificationManagerCompat.from(appContext)

    /**
     * Show (or refresh) the ongoing notification.
     *
     * Safe to call repeatedly and from anywhere — app start, boot, after a
     * payment. Re-posting the same id just refreshes it in place.
     */
    fun show() {
        if (!canPostNotifications()) {
            // API 33+ without the grant: the platform drops it silently. Not an
            // error worth shouting about — the app still works, the agent just
            // doesn't get the reassurance line until they grant notifications.
            Log.d(TAG, "Not showing watching notification: POST_NOTIFICATIONS not granted.")
            return
        }

        createChannel()

        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_watching)
            .setContentTitle("Scope SMS is watching")
            .setContentText("Ready to reply to M-Pesa payments.")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            // Ongoing + not auto-cancel: it's a status, not a message. It should
            // sit at the bottom of the shade and not swipe away on a stray tap.
            .setOngoing(true)
            .setAutoCancel(false)
            .setShowWhen(false)
            .setContentIntent(openAppIntent())
            .build()

        try {
            notificationManager.notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // OEM notification gatekeeping can deny this despite the grant; this
            // is called from BootCompletedReceiver, so an uncaught throw would be
            // a crash on boot.
            Log.w(TAG, "Denied posting the watching notification.", e)
        }
    }

    /** Remove it — e.g. if the app is later given an explicit off switch. */
    fun hide() {
        notificationManager.cancel(NOTIFICATION_ID)
    }

    private fun canPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Watching status",
            // MIN, not LOW: no sound, no vibration, and collapsed by default so
            // it never competes with the agent's real notifications.
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            description = "A quiet reminder that Scope SMS is on watch for payments."
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(appContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

        return PendingIntent.getActivity(
            appContext,
            /* requestCode = */ 1,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    companion object {
        private const val TAG = "WatchingNotification"

        /** Its own channel, separate from health warnings — different job, different mute switch. */
        const val CHANNEL_ID = "watching"

        /** Distinct from ReliabilityNotifier's 1001 so the two never overwrite each other. */
        private const val NOTIFICATION_ID = 1002
    }
}
