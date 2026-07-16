package com.scopesms.autoreply.ui.log

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.scopesms.autoreply.R
import com.scopesms.autoreply.domain.log.ActivityRecord
import com.scopesms.autoreply.domain.log.MatchType
import com.scopesms.autoreply.domain.log.NotifyStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Every payment the app saw, and what it did about it.
 *
 * This is where an agent goes when they ask "why didn't my customer get a text?"
 * — so every row has to answer that, including the rows where the answer is
 * "because you turned that off" or "because you haven't added prices yet".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ActivityLogScreen(
    modifier: Modifier = Modifier,
    viewModel: ActivityLogViewModel = viewModel(factory = ActivityLogViewModel.Factory),
) {
    val records by viewModel.records.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()

    Column(modifier.fillMaxSize()) {
        OutlinedTextField(
            value = filter.query,
            onValueChange = viewModel::setQuery,
            label = { Text(stringResource(R.string.log_search)) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )

        FlowRow(
            modifier = Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MatchType.entries.forEach { type ->
                FilterChip(
                    selected = filter.matchType == type,
                    onClick = { viewModel.toggleMatchType(type) },
                    label = { Text(type.label()) },
                )
            }
            NotifyStatus.entries.forEach { status ->
                FilterChip(
                    selected = filter.notifyStatus == status,
                    onClick = { viewModel.toggleNotifyStatus(status) },
                    label = { Text(status.label()) },
                )
            }
        }

        if (filter.isActive) {
            TextButton(
                onClick = viewModel::clearFilters,
                modifier = Modifier.padding(horizontal = 8.dp),
            ) {
                Text(stringResource(R.string.log_clear_filters))
            }
        }

        if (records.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(
                        if (filter.isActive) R.string.log_empty_filtered else R.string.log_empty,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Column
        }

        LazyColumn(
            // weight(1f) for the same reason as TemplatesScreen: this LazyColumn
            // is nested inside the outer Column, which measures it with infinite
            // height, and a lazy list rejects that with an IllegalStateException.
            // It survived until now only because the empty-log early-return above
            // meant it was never composed — it would have crashed the first time
            // the agent had a real payment to show. Found by inspection, not luck.
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(records, key = { it.id }) { record -> LogRow(record) }
        }
    }
}

@Composable
private fun LogRow(record: ActivityRecord) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = if (record.notifyStatus.isFailure) {
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            )
        } else {
            CardDefaults.cardColors()
        },
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
                    text = record.timestamp.asTime(),
                    style = MaterialTheme.typography.labelSmall,
                )
            }

            Text(
                // The name can legitimately be absent from the SMS; say so rather
                // than rendering a blank line the agent has to interpret.
                text = record.senderName ?: stringResource(R.string.log_unknown_sender),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(text = record.senderPhone, style = MaterialTheme.typography.bodySmall)

            Text(
                text = "${record.matchType.label()} · ${record.notifyStatus.label()}",
                style = MaterialTheme.typography.labelMedium,
            )

            record.bundleDescription?.let {
                Text(text = it, style = MaterialTheme.typography.bodySmall)
            }

            // The whole reason the failure taxonomy exists — "send failed" alone
            // is not enough for the agent to fix anything.
            record.failureReason?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                )
            }

            Text(
                text = record.transactionCode,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MatchType.label(): String = stringResource(
    when (this) {
        MatchType.MATCHED -> R.string.match_matched
        MatchType.UNMATCHED -> R.string.match_unmatched
        MatchType.NO_RULES_CONFIGURED -> R.string.match_no_rules
    },
)

@Composable
private fun NotifyStatus.label(): String = stringResource(
    when (this) {
        NotifyStatus.QUEUED -> R.string.notify_queued
        NotifyStatus.SENT -> R.string.notify_sent
        NotifyStatus.SILENT -> R.string.notify_silent
        NotifyStatus.FAILED -> R.string.notify_failed
    },
)

/** Local time — the agent reads this against their own clock, not UTC. */
private fun Long.asTime(): String = TIME_FORMAT.format(
    Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()),
)

private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM, HH:mm")
