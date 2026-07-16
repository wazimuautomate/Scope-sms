package com.scopesms.autoreply.ui.home

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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.scopesms.autoreply.R
import com.scopesms.autoreply.domain.log.DashboardStats

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

        Text(
            text = stringResource(R.string.home_replies),
            style = MaterialTheme.typography.titleMedium,
        )
        ToggleRow(
            title = stringResource(R.string.toggle_unmatched_title),
            subtitle = stringResource(R.string.toggle_unmatched_subtitle),
            checked = state.toggles.unmatchedReplyEnabled,
            onCheckedChange = viewModel::setUnmatchedEnabled,
        )
        ToggleRow(
            title = stringResource(R.string.toggle_matched_title),
            subtitle = stringResource(R.string.toggle_matched_subtitle),
            checked = state.toggles.matchedReplyEnabled,
            onCheckedChange = viewModel::setMatchedEnabled,
        )

        Spacer(Modifier.height(8.dp))
    }
}

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

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}
