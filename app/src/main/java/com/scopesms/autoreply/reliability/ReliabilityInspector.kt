package com.scopesms.autoreply.reliability

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.scopesms.autoreply.data.settings.SettingsRepository
import com.scopesms.autoreply.data.system.BatteryOptimizationManager
import com.scopesms.autoreply.domain.permissions.AppPermission
import com.scopesms.autoreply.domain.reliability.ReliabilityCheck
import com.scopesms.autoreply.domain.reliability.ReliabilityIssue
import com.scopesms.autoreply.domain.reliability.ReliabilitySnapshot
import com.scopesms.autoreply.telephony.SimReader

/**
 * Reads live device state and asks [ReliabilityCheck] what it means.
 *
 * The split is the point: this class is the only part that touches Android, and
 * it holds no logic — it gathers five values and hands them over. Every actual
 * decision lives in `domain/reliability/`, where it is tested on the JVM
 * (`ReliabilityCheckTest`) rather than only on a physical handset.
 *
 * Everything is read fresh on each call. There is deliberately no caching here:
 * the states this looks at are exactly the ones that change behind the app's
 * back — Android auto-revokes permissions, OEM battery managers withdraw
 * exemptions, and SIMs get moved between trays. See the notes in
 * `BatteryOptimizationManager` and `SettingsRepository`, which both refuse to
 * cache for the same reason.
 */
class ReliabilityInspector(
    context: Context,
    private val settings: SettingsRepository,
    private val simReader: SimReader,
    private val batteryOptimization: BatteryOptimizationManager,
) {

    private val appContext = context.applicationContext

    /** Current device state, frozen. */
    suspend fun snapshot(): ReliabilitySnapshot {
        val sdkInt = Build.VERSION.SDK_INT
        return ReliabilitySnapshot(
            sdkInt = sdkInt,
            grantedPermissionIds = AppPermission.requestable(sdkInt)
                .filter { isGranted(it.id) }
                .map { it.id }
                .toSet(),
            simSelection = settings.currentSimSelection(),
            // Empty here can mean "no SIM" *or* "READ_PHONE_STATE denied" —
            // AndroidSimReader cannot tell them apart and says so. That
            // ambiguity is resolved in ReliabilityCheck, which suppresses SIM
            // findings when the permission is missing.
            activeSlots = simReader.activeSims().map { it.slotIndex }.toSet(),
            batteryExempt = batteryOptimization.isExempt(),
        )
    }

    /** Problems with this device right now, worst first. Empty means healthy. */
    suspend fun check(): List<ReliabilityIssue> = ReliabilityCheck.evaluate(snapshot())

    private fun isGranted(permissionId: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, permissionId) == PackageManager.PERMISSION_GRANTED
}
