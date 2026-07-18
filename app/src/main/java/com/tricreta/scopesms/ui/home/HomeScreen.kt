package com.tricreta.scopesms.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tricreta.scopesms.R
import com.tricreta.scopesms.domain.log.ActivityRecord
import com.tricreta.scopesms.domain.log.DashboardStats
import com.tricreta.scopesms.domain.log.NotifyStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Home — is the app working, what did it do today, and the two toggles.
 *
 * BUILD-PLAN Phase 7 is explicit that the toggles live here and are visible at a
 * glance, "not buried in Settings". They are the agent's throttle on sender-ID
 * ban risk, and they get flipped in response to a busy day, so they belong on
 * the first screen rather than three taps away.
 */
@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    onAddRules: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenLog: () -> Unit = {},
    viewModel: HomeViewModel = viewModel(factory = HomeViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val recentReplies by viewModel.recentReplies.collectAsStateWithLifecycle()

    // Permissions and the battery exemption are changed in system UI, so the
    // agent leaves and comes back. Without this they return to a screen still
    // insisting the permission they just granted is missing.
    LifecycleResumeEffect(viewModel) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        StatusBanner(isMonitoring = state.isMonitoring)

        state.warnings.forEach { warning ->
            WarningCard(
                warning = warning,
                onFix = when (warning) {
                    SetupWarning.NO_RULES -> onAddRules
                    else -> onOpenSettings
                },
            )
        }

        Text(
            text = stringResource(R.string.home_today),
            style = MaterialTheme.typography.titleMedium,
        )
        StatTiles(stats = state.stats, onOpenLog = onOpenLog)

        // The two reply toggles moved to Settings at the client's request. This
        // space now shows the three most recent replies — the thing the agent
        // actually wants to glance at from Home: did the last few customers get
        // answered? Tapping any of them, or "See all", opens the full log.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.home_recent),
                style = MaterialTheme.typography.titleMedium,
            )
            if (recentReplies.isNotEmpty()) {
                TextButton(onClick = onOpenLog) {
                    Text(stringResource(R.string.home_recent_see_all))
                }
            }
        }

        if (recentReplies.isEmpty()) {
            Text(
                text = stringResource(R.string.home_recent_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            recentReplies.forEach { record ->
                RecentReplyCard(record = record, onClick = onOpenLog)
            }
        }

        Spacer(Modifier.height(8.dp))
    }
}

/** One compact row in Home's "Latest replies" list. */
@Composable
private fun RecentReplyCard(record: ActivityRecord, onClick: () -> Unit) {
    // Red fill was the client's complaint ("too harsh") — a failed send is now
    // a red border plus red status text, matching the Activity log's LogRow.
    val failed = record.notifyStatus.isFailure

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        border = if (failed) BorderStroke(1.5.dp, MaterialTheme.colorScheme.error) else null,
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.log_amount, record.amount.format()),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = record.timestamp.asShortTime(),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Text(
                text = record.senderName ?: record.senderPhone,
                style = MaterialTheme.typography.bodyMedium,
            )
            // Which bundle they bought, e.g. "250MB 24HRS" — alongside price,
            // name, time and status, per the client's ask.
            record.bundleDescription?.let {
                Text(text = it, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                text = stringResource(record.notifyStatus.labelRes()),
                style = MaterialTheme.typography.labelMedium,
                color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun NotifyStatus.labelRes(): Int = when (this) {
    NotifyStatus.QUEUED -> R.string.notify_queued
    NotifyStatus.SENT -> R.string.notify_sent
    NotifyStatus.SILENT -> R.string.notify_silent
    NotifyStatus.FAILED -> R.string.notify_failed
}

/** Local time — the agent reads this against their own clock. */
private fun Long.asShortTime(): String = HOME_TIME_FORMAT.format(
    Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()),
)

private val HOME_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM, HH:mm")

@Composable
private fun StatusBanner(isMonitoring: Boolean) {
    val colors = if (isMonitoring) {
        CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    } else {
        CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Card(colors = colors, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = if (isMonitoring) Icons.Default.CheckCircle else Icons.Default.Info,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
            )
            Column {
                Text(
                    text = stringResource(
                        if (isMonitoring) R.string.home_status_on else R.string.home_status_off,
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(
                        if (isMonitoring) {
                            R.string.home_status_on_detail
                        } else {
                            R.string.home_status_off_detail
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun WarningCard(warning: SetupWarning, onFix: () -> Unit) {
    val (titleRes, bodyRes) = when (warning) {
        SetupWarning.MISSING_PERMISSIONS ->
            R.string.warn_permissions_title to R.string.warn_permissions_body
        SetupWarning.NO_RULES -> R.string.warn_no_rules_title to R.string.warn_no_rules_body
        SetupWarning.NO_GATEWAY -> R.string.warn_no_gateway_title to R.string.warn_no_gateway_body
        SetupWarning.BOTH_TOGGLES_OFF ->
            R.string.warn_toggles_off_title to R.string.warn_toggles_off_body
        SetupWarning.NOT_BATTERY_EXEMPT ->
            R.string.warn_battery_title to R.string.warn_battery_body
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Default.Warning, contentDescription = null)
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(titleRes),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = stringResource(bodyRes),
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = onFix, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                    Text(stringResource(R.string.warn_fix))
                }
            }
        }
    }
}

@Composable
private fun StatTiles(stats: DashboardStats, onOpenLog: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        StatTile(
            value = stats.processed,
            label = stringResource(R.string.stat_processed),
            modifier = Modifier.weight(1f),
            onClick = onOpenLog,
        )
        StatTile(
            value = stats.matchedNotified,
            label = stringResource(R.string.stat_matched),
            modifier = Modifier.weight(1f),
            onClick = onOpenLog,
        )
    }
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        StatTile(
            value = stats.unmatchedReplied,
            label = stringResource(R.string.stat_unmatched),
            modifier = Modifier.weight(1f),
            onClick = onOpenLog,
        )
        StatTile(
            value = stats.failed,
            label = stringResource(R.string.stat_failed),
            modifier = Modifier.weight(1f),
            // BUILD-PLAN Phase 8: a failed send is money-adjacent and must be
            // "visually distinct/urgent if non-zero". Zero stays quiet — a
            // permanently red tile is one the agent learns to stop seeing.
            urgent = stats.hasFailures,
            onClick = onOpenLog,
        )
    }
}

@Composable
private fun StatTile(
    value: Int,
    label: String,
    modifier: Modifier = Modifier,
    urgent: Boolean = false,
    onClick: () -> Unit = {},
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        colors = if (urgent) {
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            )
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = value.toString(),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(text = label, style = MaterialTheme.typography.bodySmall)
        }
    }
}

