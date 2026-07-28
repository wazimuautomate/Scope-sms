package com.tricreta.scopesms.ui.templates

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tricreta.scopesms.R
import com.tricreta.scopesms.domain.templates.TemplateType
import com.tricreta.scopesms.domain.templates.TemplateVariable
import com.tricreta.scopesms.domain.templates.ToneIssueCategory

/**
 * The two reply bodies, each with its own variable chips and live preview.
 *
 * Two tabs rather than one screen with a type dropdown: the messages say
 * different things to different people, and BUILD-PLAN Phase 7 asks for them to
 * be visibly separate so the agent can't edit one thinking it's the other.
 */
@Composable
fun TemplatesScreen(
    modifier: Modifier = Modifier,
    viewModel: TemplatesViewModel = viewModel(factory = TemplatesViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // Stateless body, so a Robolectric Compose test can drive the *real*
    // measure/layout pass with fabricated data (long price lists, non-default
    // bodies, invalid tokens) that the empty-default preview never exercises —
    // the data the reported Messages-tab crash was suspected to need. That test
    // (TemplatesScreenTest) renders every such case without throwing, which is the
    // proof this screen no longer force-closes that earlier rounds never had.
    TemplatesContent(
        state = state,
        modifier = modifier,
        onEdit = viewModel::edit,
        onAppendVariable = viewModel::appendVariable,
        onSave = viewModel::save,
        onDiscard = viewModel::discard,
        onReset = viewModel::resetToDefault,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TemplatesContent(
    state: TemplatesUiState,
    modifier: Modifier = Modifier,
    onEdit: (TemplateType, String) -> Unit = { _, _ -> },
    onAppendVariable: (TemplateType, TemplateVariable) -> Unit = { _, _ -> },
    onSave: (TemplateType) -> Unit = {},
    onDiscard: (TemplateType) -> Unit = {},
    onReset: (TemplateType) -> Unit = {},
) {
    var selectedTab by rememberSaveable { mutableStateOf(0) }
    // Coerce before indexing: selectedTab is restored from rememberSaveable, which
    // outlives the process and app updates. An index saved by a build with more
    // tabs — or any corrupted saved value — would otherwise throw
    // IndexOutOfBoundsException here and force-close the screen the instant it
    // opens, the exact failure this screen has been reported for. entries is never
    // empty, so lastIndex is always valid.
    val safeTab = selectedTab.coerceIn(0, TemplateType.entries.lastIndex)
    val type = TemplateType.entries[safeTab]
    val editor = state.forType(type)

    // NO nested Scaffold — this is now the EXACT shape Home and Settings use: a
    // single Column with verticalScroll on the passed modifier. Those are the
    // screens that never force-close; the nested Scaffold here (a Scaffold inside
    // the app's outer Scaffold) was Templates' one structural outlier, and it still
    // crashed on the agent's real device even though a Robolectric measure/layout
    // pass could not reproduce it. So it is dropped entirely: the TabRow is simply
    // the first item inside the one scroll. One Scaffold and one scroll in the whole
    // subtree — nothing nested for a real-device measure pass to get wrong. Two tabs
    // of short content that scroll as a unit is fine UX.
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        TabRow(selectedTabIndex = safeTab) {
            TemplateType.entries.forEachIndexed { index, entry ->
                Tab(
                    selected = safeTab == index,
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

        Column(
            modifier = Modifier
                .fillMaxWidth()
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
                onValueChange = { onEdit(type, it) },
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
                        onClick = { onAppendVariable(type, variable) },
                        label = { Text(variable.token) },
                    )
                }
            }

            ValidationMessages(editor)
            ToneWarningCard(editor)
            SegmentCounter(editor)
            PreviewCard(editor)

            // Real buttons, centered as a group. Compose greys a disabled
            // Button/OutlinedButton automatically, so the enabled flags below double
            // as the "inactive = greyed" the client asked for.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            ) {
                Button(onClick = { onSave(type) }, enabled = editor.canSave) {
                    Text(stringResource(R.string.save))
                }
                OutlinedButton(
                    onClick = { onDiscard(type) },
                    enabled = editor.hasUnsavedChanges,
                ) {
                    Text(stringResource(R.string.cancel))
                }
                OutlinedButton(
                    onClick = { onReset(type) },
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
 * Warns when the rendered message reads as promotional rather than
 * transactional — the sender ID is registered as transactional with
 * Safaricom, and a message that sounds like an advert risks being blocked at
 * the carrier, silently, with nothing in the activity log to explain why.
 *
 * Advisory only, unlike [ValidationMessages]: a bordered card in the app's
 * softer "flag, don't alarm" style (see the 2026-07-18 failure-styling
 * change — same reasoning applies here, more so, since this is a heuristic
 * guess rather than a definite error), not the filled `errorContainer` used
 * for a real validation failure. It never affects [TemplateEditorState.canSave].
 */
@Composable
private fun ToneWarningCard(editor: TemplateEditorState) {
    val result = editor.toneCheck
    if (!result.soundsPromotional) return

    Card(
        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.tertiary),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = stringResource(R.string.tpl_tone_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.tertiary,
            )
            Text(
                text = stringResource(R.string.tpl_tone_explainer),
                style = MaterialTheme.typography.bodySmall,
            )
            result.issues.forEach { issue ->
                Text(
                    text = stringResource(
                        R.string.tpl_tone_issue,
                        issue.matchedText,
                        stringResource(adviceRes(issue.category)),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

private fun adviceRes(category: ToneIssueCategory): Int = when (category) {
    ToneIssueCategory.URGENCY -> R.string.tpl_tone_urgency
    ToneIssueCategory.CALL_TO_ACTION -> R.string.tpl_tone_cta
    ToneIssueCategory.DISCOUNT_FRAMING -> R.string.tpl_tone_discount
    ToneIssueCategory.PRIZE_OR_INCENTIVE -> R.string.tpl_tone_prize
    ToneIssueCategory.SHOUTING -> R.string.tpl_tone_shouting
    ToneIssueCategory.EXCESSIVE_PUNCTUATION -> R.string.tpl_tone_punctuation
    ToneIssueCategory.EMOJI -> R.string.tpl_tone_emoji
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
