package com.tricreta.scopesms.ui.rules

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.tricreta.scopesms.R
import com.tricreta.scopesms.domain.rules.BundleCategory
import com.tricreta.scopesms.domain.rules.PricingRule
import com.tricreta.scopesms.domain.rules.PurchaseLimit

/** The display label for a bundle category. */
private fun categoryLabelRes(category: BundleCategory): Int = when (category) {
    BundleCategory.DATA -> R.string.category_data
    BundleCategory.MINUTES -> R.string.category_minutes
    BundleCategory.SMS -> R.string.category_sms
}

/** The display label for a purchase limit. */
private fun purchaseLimitLabelRes(limit: PurchaseLimit): Int = when (limit) {
    PurchaseLimit.ONCE_PER_DAY -> R.string.rules_purchase_limit_once
    PurchaseLimit.MULTIPLE_PER_DAY -> R.string.rules_purchase_limit_multiple
}

/**
 * The agent's price list — the table every incoming payment is matched against.
 *
 * Amounts are whole shillings only. That is enforced in the ViewModel, stated in
 * the field's own label, and reinforced here by a number-only keyboard: a
 * Kenyan bundle costs "Ksh 50", not "Ksh 50.00", and the client asked for plain
 * integers.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RulesScreen(
    modifier: Modifier = Modifier,
    viewModel: RulesViewModel = viewModel(factory = RulesViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }

    // SAF, not FileProvider: no manifest wiring, no permissions, and the agent
    // saves/opens through the system picker they already know.
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val json = viewModel.buildExport(System.currentTimeMillis())
        if (uri == null || json == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use {
                        it.write(json.toByteArray())
                    }
                }.isSuccess
            }
            message = if (ok) {
                context.getString(R.string.rules_export_ok)
            } else {
                context.getString(R.string.rules_export_failed)
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                }.getOrNull()
            }
            if (text == null) {
                message = context.getString(R.string.rules_import_unreadable)
                return@launch
            }
            viewModel.applyImport(text) { summary ->
                message = when {
                    summary == null -> context.getString(R.string.rules_import_unreadable)
                    summary.added == 0 ->
                        context.getString(R.string.rules_import_none, summary.skippedDuplicates)
                    else -> context.getString(R.string.rules_import_ok, summary.added, summary.skippedDuplicates)
                }
            }
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.nav_rules)) },
                actions = {
                    // Only offer Share when there's something to share.
                    if (state.rules.isNotEmpty()) {
                        IconButton(onClick = {
                            exportLauncher.launch(context.getString(R.string.rules_export_filename))
                        }) {
                            Icon(Icons.Default.Share, contentDescription = stringResource(R.string.rules_share))
                        }
                    }
                    // A labelled button, not an icon: there is no unambiguous
                    // "import" glyph in material-icons-core, and Add would read as
                    // the same action as the FAB.
                    TextButton(onClick = {
                        // Any JSON — SAF filters loosely; the codec validates.
                        importLauncher.launch(arrayOf("application/json", "text/*", "*/*"))
                    }) {
                        Text(stringResource(R.string.rules_import))
                    }
                },
            )
        },
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

            // The scrolling area gets weight(1f) so it is measured with a finite
            // height. A LazyColumn placed straight into a Column is handed an
            // infinite max height and throws "measured with an infinity maximum
            // height" — the same defect that crashed the Templates tab. It hadn't
            // bitten here only because an empty price list never composes the
            // list; it would have the moment the agent added a bundle.
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    // Distinct from "no rules": before the cache loads we don't
                    // know yet, and claiming an empty list would flash on open.
                    !state.loaded -> Unit

                    state.rules.isEmpty() -> EmptyState()

                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
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
    }

    message?.let { text ->
        AlertDialog(
            onDismissRequest = { message = null },
            confirmButton = { TextButton(onClick = { message = null }) { Text(stringResource(R.string.dismiss)) } },
            text = { Text(text) },
        )
    }

    state.editing?.let { draft ->
        RuleEditorDialog(
            draft = draft,
            onAmountChange = { viewModel.updateDraft(amountText = it) },
            onDescriptionChange = { viewModel.updateDraft(description = it) },
            onCategoryChange = { viewModel.updateDraft(category = it) },
            onPurchaseLimitChange = { viewModel.updateDraft(purchaseLimit = it) },
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
                Text(
                    text = stringResource(categoryLabelRes(rule.category)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                // Only called out when it restricts the customer — the common
                // case (buyable any number of times) needs no badge, the same
                // way `rules_paused` only appears when a rule is paused.
                if (rule.purchaseLimit == PurchaseLimit.ONCE_PER_DAY) {
                    Text(
                        text = stringResource(R.string.rules_purchase_limit_badge),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RuleEditorDialog(
    draft: RuleDraft,
    onAmountChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit,
    onCategoryChange: (BundleCategory) -> Unit,
    onPurchaseLimitChange: (PurchaseLimit) -> Unit,
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

                // Which kind of bundle this is. Drives the per-category price-list
                // variables ({data_offers} etc.) on the unmatched reply.
                Text(
                    text = stringResource(R.string.rules_category_label),
                    style = MaterialTheme.typography.labelMedium,
                )
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    BundleCategory.entries.forEachIndexed { index, category ->
                        SegmentedButton(
                            selected = draft.category == category,
                            onClick = { onCategoryChange(category) },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = BundleCategory.entries.size,
                            ),
                        ) {
                            Text(stringResource(categoryLabelRes(category)))
                        }
                    }
                }

                // How often one customer can buy this bundle in a day —
                // Safaricom caps some offers to once/day per number, others
                // are unrestricted. Surfaced so `{purchase_limit}` has
                // something real to say in the purchase confirmation.
                Text(
                    text = stringResource(R.string.rules_purchase_limit_label),
                    style = MaterialTheme.typography.labelMedium,
                )
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    PurchaseLimit.entries.forEachIndexed { index, limit ->
                        SegmentedButton(
                            selected = draft.purchaseLimit == limit,
                            onClick = { onPurchaseLimitChange(limit) },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = PurchaseLimit.entries.size,
                            ),
                        ) {
                            Text(stringResource(purchaseLimitLabelRes(limit)))
                        }
                    }
                }

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
