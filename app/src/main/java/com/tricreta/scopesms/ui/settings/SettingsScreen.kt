package com.tricreta.scopesms.ui.settings

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tricreta.scopesms.R
import com.tricreta.scopesms.diagnostics.CrashReporter
import com.tricreta.scopesms.domain.settings.ThemePreference
import com.tricreta.scopesms.domain.sim.SimSelection
import com.tricreta.scopesms.telephony.SimInfo
import com.tricreta.scopesms.ui.common.requestBatteryExemption
import com.tricreta.scopesms.ui.reliability.OemGuidanceSection
import com.tricreta.scopesms.ui.update.UpdateSection

/**
 * Settings: which SIM, the gateway credentials, background reliability, version.
 */
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirmReset by rememberSaveable { mutableStateOf(false) }

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
        // If the app force-closed, its stack trace is waiting here — surfaced at the
        // very top so the agent can Share it to the developer. This is the only way
        // to diagnose a crash that reproduces on their handset but not off-device.
        var crashReport by rememberSaveable { mutableStateOf(CrashReporter.lastReport(context)) }
        crashReport?.let { report ->
            CrashReportCard(
                onShare = {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "Scope SMS crash report")
                        putExtra(Intent.EXTRA_TEXT, report)
                    }
                    context.startActivity(Intent.createChooser(intent, null))
                },
                onDismiss = {
                    CrashReporter.clear(context)
                    crashReport = null
                },
            )
            HorizontalDivider()
        }

        SectionTitle(stringResource(R.string.settings_replies))
        RepliesSection(
            toggles = state.toggles,
            onUnmatchedChange = viewModel::setUnmatchedEnabled,
            onMatchedChange = viewModel::setMatchedEnabled,
        )

        HorizontalDivider()
        SectionTitle(stringResource(R.string.settings_sim))
        SimSection(
            sims = state.sims,
            selection = state.simSelection,
            onSelect = viewModel::selectSims,
        )

        HorizontalDivider()
        SectionTitle(stringResource(R.string.settings_trusted_senders))
        TrustedSendersSection(
            senders = state.trustedSenders,
            input = state.trustedSenderInput,
            canAdd = state.canAddTrustedSender,
            onInputChange = viewModel::updateTrustedSenderInput,
            onAdd = viewModel::addTrustedSender,
            onRemove = viewModel::removeTrustedSender,
        )

        HorizontalDivider()
        SectionTitle(stringResource(R.string.settings_gateway))
        GatewaySection(state = state, viewModel = viewModel)

        HorizontalDivider()
        SectionTitle(stringResource(R.string.settings_appearance))
        ThemeSection(
            selected = state.themePreference,
            onSelect = viewModel::setThemePreference,
        )

        HorizontalDivider()
        SectionTitle(stringResource(R.string.settings_reliability))
        BatterySection(
            exempt = state.batteryExempt,
            onRequest = { context.requestBatteryExemption(viewModel.batteryOptimization) },
        )

        state.oemGuidance?.let { guidance ->
            OemGuidanceSection(
                guidance = guidance,
                canOpenSettings = viewModel.oemSettingsLauncher.hasDeepLink(),
                onOpenSettings = { viewModel.oemSettingsLauncher.open(context) },
            )
        }

        HorizontalDivider()
        SectionTitle(stringResource(R.string.settings_about))
        Text(
            text = stringResource(R.string.settings_version, state.versionName, state.versionCode),
            style = MaterialTheme.typography.bodyMedium,
        )
        // The in-app updater — its own ViewModel so the download is cancellable
        // and it owns the install/permission launchers. See ui/update/.
        UpdateSection(modifier = Modifier.fillMaxWidth())

        HorizontalDivider()
        SectionTitle(stringResource(R.string.settings_reset))
        ResetSection(onReset = { confirmReset = true })
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(R.string.settings_reset_title)) },
            text = { Text(stringResource(R.string.settings_reset_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    viewModel.resetApp()
                }) {
                    Text(stringResource(R.string.settings_reset_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text = text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
}

/**
 * The "reset app" action — a red card that explains the wipe and a button that
 * opens the confirmation. Styled like [CrashReportCard] on purpose: it is
 * destructive and should read that way.
 */
@Composable
private fun ResetSection(onReset: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.settings_reset_explain),
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(
                onClick = onReset,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
                modifier = Modifier.align(Alignment.End),
            ) {
                Text(stringResource(R.string.settings_reset))
            }
        }
    }
}

/**
 * Shown only when a previous run left a crash report. Lets the agent send us the
 * exact stack trace from their own phone — the one thing an off-device test can't
 * give us for a device-specific force-close.
 */
@Composable
private fun CrashReportCard(onShare: () -> Unit, onDismiss: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(R.string.settings_crash_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(R.string.settings_crash_body),
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onShare) {
                    Text(stringResource(R.string.settings_crash_share))
                }
                OutlinedButton(onClick = onDismiss) {
                    Text(stringResource(R.string.dismiss))
                }
            }
        }
    }
}

/**
 * The two independent reply flows, moved here from Home.
 *
 * They are the agent's throttle on sender-ID ban risk (CLAUDE.md, "What this app
 * is"): confirmations are higher-volume, so the agent turns that flow off on a
 * busy day. An empty rule list overrides both anyway, so leaving them on before
 * prices are entered sends nothing.
 */
@Composable
private fun RepliesSection(
    toggles: com.tricreta.scopesms.domain.notifications.NotificationToggles,
    onUnmatchedChange: (Boolean) -> Unit,
    onMatchedChange: (Boolean) -> Unit,
) {
    ToggleRow(
        title = stringResource(R.string.toggle_unmatched_title),
        subtitle = stringResource(R.string.toggle_unmatched_subtitle),
        checked = toggles.unmatchedReplyEnabled,
        onCheckedChange = onUnmatchedChange,
    )
    ToggleRow(
        title = stringResource(R.string.toggle_matched_title),
        subtitle = stringResource(R.string.toggle_matched_subtitle),
        checked = toggles.matchedReplyEnabled,
        onCheckedChange = onMatchedChange,
    )
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

/** Light / dark / follow-the-phone. Defaults to system. */
@Composable
private fun ThemeSection(
    selected: ThemePreference,
    onSelect: (ThemePreference) -> Unit,
) {
    val options = listOf(
        ThemePreference.SYSTEM to R.string.settings_theme_system,
        ThemePreference.LIGHT to R.string.settings_theme_light,
        ThemePreference.DARK to R.string.settings_theme_dark,
    )
    options.forEach { (preference, labelRes) ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .selectable(selected = selected == preference, onClick = { onSelect(preference) })
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected == preference, onClick = { onSelect(preference) })
            Text(text = stringResource(labelRes), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun SimSection(
    sims: List<SimInfo>,
    selection: SimSelection,
    onSelect: (SimSelection) -> Unit,
) {
    if (sims.isEmpty()) {
        Text(
            text = stringResource(R.string.settings_sim_none),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    Text(
        text = stringResource(R.string.settings_sim_help),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    SimOption(
        label = stringResource(R.string.settings_sim_all),
        selected = selection is SimSelection.AllSims,
        onClick = { onSelect(SimSelection.AllSims) },
    )

    sims.forEach { sim ->
        SimOption(
            // Carrier alone disambiguates nothing when both SIMs are Safaricom,
            // which is common; the number is often blank on Kenyan SIMs. Show
            // whatever is available and always lead with the slot, which is the
            // thing the agent can physically point at.
            label = listOfNotNull(
                sim.slotLabel,
                sim.carrierName.takeIf { it.isNotBlank() },
                sim.phoneNumber?.takeIf { it.isNotBlank() },
            ).joinToString(" · "),
            selected = selection is SimSelection.Slots && sim.slotIndex in selection.slots,
            onClick = { onSelect(SimSelection.slot(sim.slotIndex)) },
        )
    }
}

/**
 * Extra sender addresses the agent trusts as M-Pesa confirmations, beyond the
 * official shortcode — e.g. their own registered sender ID (`SKYSCOPE_`) when
 * it resells a service that texts the same till-confirmation format.
 *
 * Empty by default: this only ever *adds* trust, never removes the official
 * shortcode check, so a fresh install and every install from before this
 * setting existed behave exactly as before.
 */
@Composable
private fun TrustedSendersSection(
    senders: Set<String>,
    input: String,
    canAdd: Boolean,
    onInputChange: (String) -> Unit,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit,
) {
    Text(
        text = stringResource(R.string.settings_trusted_senders_help),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    senders.forEach { sender ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = sender, style = MaterialTheme.typography.bodyLarge)
            TextButton(onClick = { onRemove(sender) }) {
                Text(stringResource(R.string.settings_trusted_senders_remove))
            }
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = input,
            onValueChange = onInputChange,
            label = { Text(stringResource(R.string.settings_trusted_senders_label)) },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        Button(onClick = onAdd, enabled = canAdd) {
            Text(stringResource(R.string.settings_trusted_senders_add))
        }
    }
}

@Composable
private fun SimOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * Points a new agent at where BlazeTech SMS actually issues an API key.
 * Without this, "API key" is a field with nothing to type into it and no
 * indication of where one comes from.
 */
@Composable
private fun ApiKeySignupHint() {
    val url = "https://sms.blazetechscope.com/apikeys"
    val prefix = stringResource(R.string.settings_gateway_get_key)
    val text = buildAnnotatedString {
        append(prefix)
        append(" ")
        withLink(
            LinkAnnotation.Url(
                url = url,
                styles = TextLinkStyles(
                    style = SpanStyle(
                        color = MaterialTheme.colorScheme.primary,
                        textDecoration = TextDecoration.Underline,
                    ),
                ),
            ),
        ) {
            append(url)
        }
    }
    Text(text = text, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun GatewaySection(state: SettingsUiState, viewModel: SettingsViewModel) {
    var testPhone by rememberSaveable { mutableStateOf("") }

    Text(
        text = stringResource(R.string.settings_gateway_help),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    ApiKeySignupHint()
    Text(
        text = stringResource(R.string.settings_gateway_balance),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (state.gatewayConfigured) {
        Text(
            text = stringResource(R.string.settings_gateway_saved, state.maskedApiKey, state.senderId),
            style = MaterialTheme.typography.bodyMedium,
        )
    }

    OutlinedTextField(
        value = state.apiKeyInput,
        onValueChange = viewModel::updateApiKeyInput,
        label = { Text(stringResource(R.string.settings_api_key)) },
        // The key is a secret and Settings is the screen most likely to be
        // screenshotted or shoulder-surfed.
        visualTransformation = PasswordVisualTransformation(),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    OutlinedTextField(
        value = state.senderIdInput,
        onValueChange = viewModel::updateSenderIdInput,
        label = { Text(stringResource(R.string.settings_sender_id)) },
        supportingText = { Text(stringResource(R.string.settings_sender_id_help)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    if (state.saveFailed) {
        Text(
            text = stringResource(R.string.settings_gateway_save_failed),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = viewModel::saveGateway, enabled = state.canSaveGateway) {
            Text(stringResource(R.string.save))
        }
        if (state.gatewayConfigured) {
            OutlinedButton(onClick = viewModel::clearGateway) {
                Text(stringResource(R.string.settings_gateway_clear))
            }
        }
    }

    // The test send is the only way to find out that a sender ID isn't
    // registered before a real customer is waiting on the reply.
    Text(
        text = stringResource(R.string.settings_test_title),
        style = MaterialTheme.typography.titleSmall,
    )
    OutlinedTextField(
        value = testPhone,
        onValueChange = { testPhone = it },
        label = { Text(stringResource(R.string.settings_test_phone)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    val canSend = state.canTestSend && testPhone.isNotBlank()
    Button(
        onClick = { viewModel.sendTest(testPhone) },
        enabled = canSend,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.settings_test_send))
    }

    if (state.testSend is TestSendState.Sending) {
        CircularProgressIndicator(modifier = Modifier.padding(4.dp))
    }

    TestResult(state.testSend, onDismiss = viewModel::dismissTestResult)
}

@Composable
private fun TestResult(state: TestSendState, onDismiss: () -> Unit) {
    val (text, isError) = when (state) {
        is TestSendState.Success -> stringResource(R.string.settings_test_ok) to false
        is TestSendState.Failure -> state.reason to true
        else -> return
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isError) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.primaryContainer
            },
            contentColor = if (isError) {
                MaterialTheme.colorScheme.onErrorContainer
            } else {
                MaterialTheme.colorScheme.onPrimaryContainer
            },
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(text = text, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dismiss)) }
        }
    }
}

@Composable
private fun BatterySection(exempt: Boolean, onRequest: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (exempt) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.errorContainer
            },
            contentColor = if (exempt) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onErrorContainer
            },
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                text = stringResource(
                    if (exempt) R.string.settings_battery_ok else R.string.settings_battery_bad,
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (!exempt) {
                TextButton(onClick = onRequest) {
                    Text(stringResource(R.string.settings_battery_fix))
                }
            }
        }
    }
}
