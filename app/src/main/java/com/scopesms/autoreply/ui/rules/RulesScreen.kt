package com.scopesms.autoreply.ui.rules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.scopesms.autoreply.R
import com.scopesms.autoreply.domain.rules.PricingRule

/**
 * The agent's price list — the table every incoming payment is matched against.
 *
 * Amounts are whole shillings only. That is enforced in the ViewModel, stated in
 * the field's own label, and reinforced here by a number-only keyboard: a
 * Kenyan bundle costs "Ksh 50", not "Ksh 50.00", and the client asked for plain
 * integers.
 */
@Composable
fun RulesScreen(
    modifier: Modifier = Modifier,
    viewModel: RulesViewModel = viewModel(factory = RulesViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = viewModel::startAdding,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.rules_add)) },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (state.duplicateAmounts.isNotEmpty()) {
                DuplicateWarning(count = state.duplicateAmounts.size)
            }

            when {
                // Distinct from "no rules": before the cache loads we don't know
                // yet, and claiming an empty price list would be a lie that
                // flashes on every open.
                !state.loaded -> Unit

                state.rules.isEmpty() -> EmptyState()

                else -> LazyColumn(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.rules, key = { it.id }) { rule ->
                        RuleCard(
                            rule = rule,
                            isDuplicate = rule.amount in state.duplicateAmounts,
                            onEdit = { viewModel.startEditing(rule) },
                            onToggleActive = { viewModel.setActive(rule, it) },
                            onDelete = { viewModel.delete(rule) },
                        )
                    }
                }
            }
        }
    }

    state.editing?.let { draft ->
        RuleEditorDialog(
            draft = draft,
            onAmountChange = { viewModel.updateDraft(amountText = it) },
            onDescriptionChange = { viewModel.updateDraft(description = it) },
            onSave = viewModel::save,
            onDismiss = viewModel::cancelEditing,
        )
    }
}

@Composable
private fun DuplicateWarning(count: Int) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
        modifier = Modifier.fillMaxWidth().padding(16.dp),
    ) {
        Text(
            text = stringResource(R.string.rules_duplicate_warning, count),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
private fun EmptyState() {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(R.string.rules_empty_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.rules_empty_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RuleCard(
    rule: PricingRule,
    isDuplicate: Boolean,
    onEdit: () -> Unit,
    onToggleActive: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(start = 16.dp, top = 8.dp, end = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    // format() renders a whole-shilling rule with no decimals.
                    text = stringResource(R.string.rules_price, rule.amount.format()),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(text = rule.bundleDescription, style = MaterialTheme.typography.bodyMedium)
                if (isDuplicate) {
                    Text(
                        text = stringResource(R.string.rules_duplicate_badge),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (!rule.isActive) {
                    Text(
                        text = stringResource(R.string.rules_paused),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Switch(checked = rule.isActive, onCheckedChange = onToggleActive)
            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.rules_edit))
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.rules_delete))
            }
        }
    }
}

@Composable
private fun RuleEditorDialog(
    draft: RuleDraft,
    onAmountChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    val errorText = draft.error?.let {
        stringResource(
            when (it) {
                RuleInputError.AMOUNT_INVALID -> R.string.rules_err_amount
                RuleInputError.AMOUNT_NOT_WHOLE -> R.string.rules_err_whole
                RuleInputError.AMOUNT_ZERO -> R.string.rules_err_zero
                RuleInputError.DESCRIPTION_BLANK -> R.string.rules_err_description
                RuleInputError.AMOUNT_DUPLICATE -> R.string.rules_err_duplicate
            },
        )
    }
    val amountHasError = draft.error != null && draft.error != RuleInputError.DESCRIPTION_BLANK

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(if (draft.isNew) R.string.rules_new else R.string.rules_edit),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = draft.amountText,
                    onValueChange = onAmountChange,
                    label = { Text(stringResource(R.string.rules_amount_label)) },
                    supportingText = { Text(stringResource(R.string.rules_amount_help)) },
                    // Number keypad, and the ViewModel rejects a decimal anyway —
                    // some OEM keyboards show a "." on this type regardless.
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = amountHasError,
                    singleLine = true,
                )
                OutlinedTextField(
                    value = draft.description,
                    onValueChange = onDescriptionChange,
                    label = { Text(stringResource(R.string.rules_description_label)) },
                    supportingText = { Text(stringResource(R.string.rules_description_help)) },
                    isError = draft.error == RuleInputError.DESCRIPTION_BLANK,
                    singleLine = true,
                )
                errorText?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onSave) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
