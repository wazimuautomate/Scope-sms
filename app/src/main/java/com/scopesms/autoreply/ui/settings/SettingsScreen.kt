package com.scopesms.autoreply.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.scopesms.autoreply.R
import com.scopesms.autoreply.domain.sim.SimSelection
import com.scopesms.autoreply.domain.update.UpdateStatus
import com.scopesms.autoreply.telephony.SimInfo
import com.scopesms.autoreply.ui.common.requestBatteryExemption
import com.scopesms.autoreply.ui.reliability.OemGuidanceSection

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
        SectionTitle(stringResource(R.string.settings_sim))
        SimSection(
            sims = state.sims,
            selection = state.simSelection,
            onSelect = viewModel::selectSims,
        )

        HorizontalDivider()
        SectionTitle(stringResource(R.string.settings_gateway))
        GatewaySection(state = state, viewModel = viewModel)

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
        UpdateSection(state = state, onCheck = viewModel::checkForUpdate)
    }
}

/**
 * Phase 11's update check.
 *
 * On demand, and it opens a browser rather than installing anything —
 * BUILD-PLAN Phase 11: *"prompt with a download link if newer — no
 * auto-install."* An app that could silently replace itself on the agent's
 * phone is a bigger thing than this needs to be.
 */
@Composable
private fun UpdateSection(state: SettingsUiState, onCheck: () -> Unit) {
    val uriHandler = LocalUriHandler.current

    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(onClick = onCheck, enabled = !state.checkingForUpdate) {
            Text(stringResource(R.string.settings_update_check))
        }
        if (state.checkingForUpdate) {
            CircularProgressIndicator(modifier = Modifier.padding(4.dp))
        }
    }

    when (val update = state.update) {
        null -> Unit

        is UpdateStatus.Available -> Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    text = stringResource(R.string.settings_update_available, update.version.toString()),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                )
                update.notes?.takeIf { it.isNotBlank() }?.let {
                    Text(text = it.trim(), style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { uriHandler.openUri(update.url) }) {
                    Text(stringResource(R.string.settings_update_open))
                }
            }
        }

        UpdateStatus.UpToDate -> Text(
            text = stringResource(R.string.settings_update_current),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Offline, rate-limited, no releases yet. Says so plainly rather than
        // claiming the app is up to date, which would be a guess.
        UpdateStatus.Unknown -> Text(
            text = stringResource(R.string.settings_update_unknown),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text = text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
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

@Composable
private fun GatewaySection(state: SettingsUiState, viewModel: SettingsViewModel) {
    var testPhone by rememberSaveable { mutableStateOf("") }

    Text(
        text = stringResource(R.string.settings_gateway_help),
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
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(
            onClick = { viewModel.sendTest(testPhone) },
            enabled = state.canTestSend && testPhone.isNotBlank(),
        ) {
            Text(stringResource(R.string.settings_test_send))
        }
        if (state.testSend is TestSendState.Sending) {
            CircularProgressIndicator(modifier = Modifier.padding(4.dp))
        }
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
