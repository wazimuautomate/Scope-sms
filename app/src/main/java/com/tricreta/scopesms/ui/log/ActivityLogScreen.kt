package com.tricreta.scopesms.ui.log

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tricreta.scopesms.R
import com.tricreta.scopesms.domain.log.ActivityRecord
import com.tricreta.scopesms.domain.log.MatchType
import com.tricreta.scopesms.domain.log.NotifyStatus
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
@Composable
fun ActivityLogScreen(
    modifier: Modifier = Modifier,
    viewModel: ActivityLogViewModel = viewModel(factory = ActivityLogViewModel.Factory),
) {
    val records by viewModel.records.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val selectedIds by viewModel.selectedIds.collectAsStateWithLifecycle()
    val selectionMode = selectedIds.isNotEmpty()

    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf<ClearScope?>(null) }
    var confirmForceSend by remember { mutableStateOf(false) }

    // The result of a force-send, shown once as a toast.
    val forceSendResult by viewModel.forceSendResult.collectAsStateWithLifecycle()
    forceSendResult?.let { result ->
        LaunchedEffect(result) {
            Toast.makeText(
                context,
                context.getString(R.string.log_force_send_result, result.sent, result.failed),
                Toast.LENGTH_LONG,
            ).show()
            viewModel.clearForceSendResult()
        }
    }

    Column(modifier.fillMaxSize()) {
        // Contextual bar while rows are selected (force send / copy / delete);
        // otherwise the title row with the bulk-clear menu.
        if (selectionMode) {
            SelectionBar(
                count = selectedIds.size,
                onSelectAll = viewModel::selectAll,
                onForceSend = { confirmForceSend = true },
                onCopy = {
                    val text = viewModel.buildCopyText()
                    if (text.isNotBlank()) {
                        clipboard.setText(AnnotatedString(text))
                        Toast.makeText(
                            context,
                            context.getString(R.string.log_copied, selectedIds.size),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                    viewModel.clearSelection()
                },
                onDelete = { confirmDelete = true },
                onClose = viewModel::clearSelection,
            )
        } else {
            LogHeader(onClear = { confirmClear = it })
        }

        OutlinedTextField(
            value = filter.query,
            onValueChange = viewModel::setQuery,
            label = { Text(stringResource(R.string.log_search)) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )

        // One horizontal row that scrolls, rather than a FlowRow that wraps onto
        // several lines: the agent asked for the filters laid out along one line
        // and swiped sideways to reach the ones off-screen, which keeps the list
        // of payments higher up the screen where they can see it.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
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
            items(records, key = { it.id }) { record ->
                LogRow(
                    record = record,
                    selected = record.id in selectedIds,
                    onClick = { if (selectionMode) viewModel.toggleSelection(record.id) },
                    onLongClick = { viewModel.toggleSelection(record.id) },
                    onForceSend = { viewModel.forceSendOne(record) },
                )
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.log_delete_title)) },
            text = { Text(stringResource(R.string.log_delete_body, selectedIds.size)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteSelected()
                    confirmDelete = false
                }) {
                    Text(stringResource(R.string.log_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    confirmClear?.let { scope ->
        AlertDialog(
            onDismissRequest = { confirmClear = null },
            title = { Text(stringResource(R.string.log_clear_title)) },
            text = {
                Text(
                    stringResource(
                        when (scope) {
                            ClearScope.SENT -> R.string.log_clear_sent_body
                            ClearScope.PENDING -> R.string.log_clear_pending_body
                            ClearScope.ALL -> R.string.log_clear_all_body
                        },
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    when (scope) {
                        ClearScope.SENT -> viewModel.clearSent()
                        ClearScope.PENDING -> viewModel.clearPending()
                        ClearScope.ALL -> viewModel.clearAll()
                    }
                    confirmClear = null
                }) {
                    Text(stringResource(R.string.log_clear_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = null }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    if (confirmForceSend) {
        AlertDialog(
            onDismissRequest = { confirmForceSend = false },
            title = { Text(stringResource(R.string.log_force_send_title)) },
            text = { Text(stringResource(R.string.log_force_send_body, selectedIds.size)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.forceSendSelected()
                    confirmForceSend = false
                }) {
                    Text(stringResource(R.string.log_force_send))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmForceSend = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

/** The log's title row and the bulk-clear overflow menu (shown when not selecting). */
@Composable
private fun LogHeader(onClear: (ClearScope) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.nav_log),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        var expanded by remember { mutableStateOf(false) }
        Box {
            IconButton(onClick = { expanded = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.log_menu))
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.log_clear_sent)) },
                    onClick = { expanded = false; onClear(ClearScope.SENT) },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.log_clear_pending)) },
                    onClick = { expanded = false; onClear(ClearScope.PENDING) },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.log_clear_all)) },
                    onClick = { expanded = false; onClear(ClearScope.ALL) },
                )
            }
        }
    }
}

/** Which rows a bulk-clear targets. */
private enum class ClearScope { SENT, PENDING, ALL }

/** The contextual action bar shown while log rows are selected. */
@Composable
private fun SelectionBar(
    count: Int,
    onSelectAll: () -> Unit,
    onForceSend: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.log_selection_close))
            }
            Text(
                text = stringResource(R.string.log_selected, count),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            // Force send is the headline action, kept visible; the rest go in an
            // overflow so the bar never runs off the edge on a narrow screen.
            TextButton(onClick = onForceSend) { Text(stringResource(R.string.log_force_send)) }
            var expanded by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { expanded = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.log_menu))
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.log_select_all)) },
                        onClick = { expanded = false; onSelectAll() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.log_copy)) },
                        onClick = { expanded = false; onCopy() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.log_delete)) },
                        onClick = { expanded = false; onDelete() },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LogRow(
    record: ActivityRecord,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onForceSend: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val copiedMessage = stringResource(R.string.log_copied_field)
    fun copy(text: String) {
        clipboard.setText(AnnotatedString(text))
        Toast.makeText(context, copiedMessage, Toast.LENGTH_SHORT).show()
    }

    var menuExpanded by remember { mutableStateOf(false) }

    // A failed send is money-adjacent, but a full red card was the client's
    // complaint ("too harsh") — so failure is now a red border plus red
    // status text, not a red fill. Selection highlight still wins outright:
    // a selected row is never also drawn as failed.
    val showFailedStyle = !selected && record.notifyStatus.isFailure

    Card(
        // Long-press any row to start selecting; tap toggles once selection is on.
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors = if (selected) {
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        } else {
            CardDefaults.cardColors()
        },
        border = if (showFailedStyle) BorderStroke(1.5.dp, MaterialTheme.colorScheme.error) else null,
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.log_amount, record.amount.format()),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = record.timestamp.asTime(),
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Box {
                        IconButton(onClick = { menuExpanded = true }, modifier = Modifier.size(28.dp)) {
                            Icon(
                                Icons.Default.MoreVert,
                                contentDescription = stringResource(R.string.log_row_menu),
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.log_force_send)) },
                                // SILENT rows have no rendered body to send.
                                enabled = record.replyBody != null,
                                onClick = { menuExpanded = false; onForceSend() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.log_copy_code)) },
                                onClick = { menuExpanded = false; copy(record.transactionCode) },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.log_copy_phone)) },
                                onClick = { menuExpanded = false; copy(record.senderPhone) },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.log_copy_message)) },
                                // Null for SILENT rows — no body was ever rendered, so
                                // there is nothing to copy.
                                enabled = record.replyBody != null,
                                onClick = {
                                    menuExpanded = false
                                    record.replyBody?.let(::copy)
                                },
                            )
                        }
                    }
                }
            }

            Text(
                // The name can legitimately be absent from the SMS; say so rather
                // than rendering a blank line the agent has to interpret.
                text = record.senderName ?: stringResource(R.string.log_unknown_sender),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(text = record.senderPhone, style = MaterialTheme.typography.bodySmall)

            Row {
                Text(
                    text = "${record.matchType.label()} · ",
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(
                    text = record.notifyStatus.label(),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (showFailedStyle) MaterialTheme.colorScheme.error else Color.Unspecified,
                    fontWeight = if (showFailedStyle) FontWeight.Bold else FontWeight.Normal,
                )
            }

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
                    color = if (showFailedStyle) MaterialTheme.colorScheme.error else Color.Unspecified,
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
