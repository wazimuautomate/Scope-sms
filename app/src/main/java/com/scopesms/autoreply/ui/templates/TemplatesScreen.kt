package com.scopesms.autoreply.ui.templates

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.scopesms.autoreply.R
import com.scopesms.autoreply.domain.templates.TemplateType
import com.scopesms.autoreply.domain.templates.TemplateVariable

/**
 * The two reply bodies, each with its own variable chips and live preview.
 *
 * Two tabs rather than one screen with a type dropdown: the messages say
 * different things to different people, and BUILD-PLAN Phase 7 asks for them to
 * be visibly separate so the agent can't edit one thinking it's the other.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TemplatesScreen(
    modifier: Modifier = Modifier,
    viewModel: TemplatesViewModel = viewModel(factory = TemplatesViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var selectedTab by rememberSaveable { mutableStateOf(0) }
    val type = TemplateType.entries[selectedTab]
    val editor = state.forType(type)

    // The TabRow is the topBar of a nested Scaffold, and the body scrolls as the
    // Scaffold's content directly. This is deliberately the *same* shape as Home
    // and Settings — the two screens that scroll without crashing on the agent's
    // device — not the earlier `Column { TabRow; Column(weight(1f).verticalScroll) }`.
    //
    // That earlier form is the textbook `weight(1f)` fix for the "measured with an
    // infinity maximum height" crash and is correct on paper, but it kept
    // force-closing this one screen in the field while every root-level-scroll
    // screen stayed fine. So this drops `weight` entirely: a verticalScroll placed
    // as a Scaffold body gets bounded height the same way Home's does, with no
    // nested-Column measurement in the middle to get it wrong. Pinned tabs, a
    // scrolling body — same UX, a structure that is proven on the actual handset.
    Scaffold(
        modifier = modifier,
        topBar = {
            TabRow(selectedTabIndex = selectedTab) {
                TemplateType.entries.forEachIndexed { index, entry ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = {
                            Text(
                                stringResource(
                                    when (entry) {
                                        TemplateType.UNMATCHED -> R.string.tpl_tab_unmatched
                                        TemplateType.MATCHED -> R.string.tpl_tab_matched
                                    },
                                ),
                            )
                        },
                    )
                }
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(
                    when (type) {
                        TemplateType.UNMATCHED -> R.string.tpl_unmatched_explainer
                        TemplateType.MATCHED -> R.string.tpl_matched_explainer
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = editor.body,
                onValueChange = { viewModel.edit(type, it) },
                label = { Text(stringResource(R.string.tpl_body_label)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 4,
                isError = !editor.validation.isValid,
            )

            // Only this flow's variables. {package} in an unmatched reply has no
            // rule to name, and {bundle_list} in a confirmation would append the
            // whole price list to a message the customer didn't ask for — so the
            // chips can't offer them.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                editor.allowedVariables.forEach { variable ->
                    AssistChip(
                        onClick = { viewModel.appendVariable(type, variable) },
                        label = { Text(variable.token) },
                    )
                }
            }

            ValidationMessages(editor)
            SegmentCounter(editor)
            PreviewCard(editor)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { viewModel.save(type) }, enabled = editor.canSave) {
                    Text(stringResource(R.string.save))
                }
                TextButton(
                    onClick = { viewModel.discard(type) },
                    enabled = editor.hasUnsavedChanges,
                ) {
                    Text(stringResource(R.string.cancel))
                }
                TextButton(
                    onClick = { viewModel.resetToDefault(type) },
                    // Only offered when it would do something.
                    enabled = !editor.isDefault || editor.hasUnsavedChanges,
                ) {
                    Text(stringResource(R.string.tpl_reset))
                }
            }
        }
    }
}

@Composable
private fun ValidationMessages(editor: TemplateEditorState) {
    val validation = editor.validation
    if (validation.isValid) return

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            if (validation.unknownTokens.isNotEmpty()) {
                Text(
                    text = stringResource(
                        R.string.tpl_err_unknown,
                        validation.unknownTokens.joinToString(", "),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (validation.disallowedVariables.isNotEmpty()) {
                Text(
                    text = stringResource(
                        R.string.tpl_err_disallowed,
                        validation.disallowedVariables.joinToString(", ") { it.token },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/**
 * What this message costs to send.
 *
 * Every wrapped segment is money out of the agent's pocket on every reply, so
 * this is not a nicety — a template that silently spills to two segments doubles
 * their bill for the whole day.
 */
@Composable
private fun SegmentCounter(editor: TemplateEditorState) {
    val length = editor.length
    Text(
        text = stringResource(
            R.string.tpl_segments,
            length.units,
            length.segments,
            length.encoding.name,
        ),
        style = MaterialTheme.typography.labelMedium,
        color = if (length.willSplit) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
}

@Composable
private fun PreviewCard(editor: TemplateEditorState) {
    Column {
        Text(
            text = stringResource(R.string.tpl_preview),
            style = MaterialTheme.typography.titleSmall,
        )
        Card(Modifier.fillMaxWidth()) {
            Text(
                text = editor.preview,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.SansSerif,
                modifier = Modifier.padding(12.dp),
            )
        }
        Text(
            text = stringResource(R.string.tpl_preview_note),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
