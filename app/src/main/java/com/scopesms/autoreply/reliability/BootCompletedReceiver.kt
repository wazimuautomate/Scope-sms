package com.scopesms.autoreply.reliability

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.scopesms.autoreply.di.AppContainer
import kotlinx.coroutines.launch

/**
 * Checks the app's health after a reboot and warns the agent if it is broken.
 *
 * ### What this is *not* for
 * It is **not** needed to make SMS ingestion resume. A manifest-registered
 * receiver keeps receiving broadcasts across a reboot on its own, as long as the
 * app isn't in the "stopped" state — which it isn't, because the agent has
 * launched it. Wiring `BOOT_COMPLETED` up to "restart ingestion" would be
 * cargo-cult: there is nothing to restart.
 *
 * What a reboot *does* do is change the things ingestion silently depends on.
 * The SIM tray is re-read, subscription IDs are reissued, and OEM battery
 * managers on the Transsion/Xiaomi builds this app targets are at their most
 * aggressive right after boot — they routinely revoke exemptions and re-apply
 * their own restrictions then. Reboot is therefore the single best moment to ask
 * "does this app still work?", which is why BUILD-PLAN Phase 9 puts the check
 * here.
 *
 * See `ReliabilityCheck` for why the two specific things the plan names —
 * re-validating stored subscription IDs and stored exemption status — turned out
 * to be unnecessary (Phase 1 persists neither), and what is checked instead.
 */
class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        // Exported receivers can be sent anything by anyone, and QUICKBOOT_POWERON
        // is not a protected broadcast — any app can send it. The cost of a
        // forged one is only a spurious health check, but validating what we
        // were handed is cheap.
        val action = intent.action
        if (action == null || action !in HANDLED_ACTIONS) {
            Log.w(TAG, "Ignoring unexpected action: $action")
            return
        }

        // Health checks touch DataStore and SubscriptionManager — too slow for
        // onReceive's main-thread budget. goAsync() keeps the process alive
        // while the coroutine runs (same pattern CLAUDE.md constraint 6
        // prescribes for the Phase 2 SMS receiver).
        val pending = goAsync()
        val container = AppContainer.from(context)

        container.applicationScope.launch {
            try {
                // Re-post the "watching" reassurance — the notification does not
                // survive a reboot, and this is the first chance to bring it back.
                container.watchingNotification.show()
                container.reliabilityNotifier.notifyOfIssues(container.reliabilityInspector.check())
            } catch (e: Exception) {
                // A crash in a boot receiver is a crash dialog on the agent's
                // screen every time they turn the phone on, caused by the code
                // whose entire job is reassuring them the app is fine. Whatever
                // went wrong, failing to run a health check is never worth more
                // than the check itself.
                Log.e(TAG, "Reliability check failed on boot.", e)
            } finally {
                // In `finally` because not calling it leaks the wake lock the
                // system granted us and eventually gets the process killed
                // mid-check.
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "BootCompletedReceiver"

        val HANDLED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            // Not a platform constant, and not in AOSP: a legacy broadcast some
            // OEM builds send *instead of* BOOT_COMPLETED when resuming from
            // their "fast boot"/quick-power-on modes. Costs one string to
            // handle; missing it on a device that does this means the health
            // check never runs there at all. Duplicate delivery is harmless —
            // the notification has a fixed id, so a second check replaces the
            // first rather than stacking.
            "android.intent.action.QUICKBOOT_POWERON",
        )
    }
}
