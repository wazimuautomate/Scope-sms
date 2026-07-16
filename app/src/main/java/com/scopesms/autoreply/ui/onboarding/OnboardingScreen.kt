package com.scopesms.autoreply.ui.onboarding

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.scopesms.autoreply.R
import com.scopesms.autoreply.domain.sim.SimSelection
import com.scopesms.autoreply.ui.common.requestBatteryExemption
import com.scopesms.autoreply.ui.settings.SettingsViewModel
import com.scopesms.autoreply.ui.settings.TestSendState
import com.scopesms.autoreply.ui.setup.SetupViewModel

/**
 * First-run setup: permissions → SIM → gateway → battery.
 *
 * ## Why the agent can't skip to the end
 * Each step gates the next, because an app that finishes onboarding without
 * permissions or credentials is an app that looks set up and silently does
 * nothing — which is this project's defining failure mode. The one exception is
 * the battery step: the exemption is genuinely optional (the app works, it's
 * just killable), and on some OEM builds the dialog doesn't exist at all, so
 * blocking on it would strand the agent on a dead button.
 *
 * Reuses [SetupViewModel] (Phase 1's permission/SIM logic — the thing its exit
 * criteria were proven against) and [SettingsViewModel] (gateway + test send)
 * rather than restating either. Phase 1 said its plain `SetupScreen` should be
 * replaced outright, not extended; this replaces it.
 */
@Composable
fun OnboardingScreen(
    modifier: Modifier = Modifier,
    onFinished: () -> Unit,
    setupViewModel: SetupViewModel = viewModel(factory = SetupViewModel.Factory),
    settingsViewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory),
) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    val setup by setupViewModel.uiState.collectAsStateWithLifecycle()
    val settings by settingsViewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // The agent leaves for system UI to grant things and comes back.
    LifecycleResumeEffect(setupViewModel) {
        setupViewModel.refresh()
        settingsViewModel.refresh()
        onPauseOrDispose { }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { setupViewModel.refresh() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        LinearProgressIndicator(
            progress = { (step + 1) / TOTAL_STEPS.toFloat() },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = stringResource(R.string.onb_step, step + 1, TOTAL_STEPS),
            style = MaterialTheme.typography.labelMedium,
        )

        when (step) {
            STEP_PERMISSIONS -> PermissionsStep(
                readyToIngest = setup.readyToIngest,
                onGrant = { permissionLauncher.launch(setupViewModel.permissionsToRequest()) },
                onNext = { step = STEP_SIM },
            )

            STEP_SIM -> SimStep(
                sims = setup.sims.map { sim ->
                    sim.slotIndex to listOfNotNull(
                        sim.slotLabel,
                        sim.carrierName.takeIf { it.isNotBlank() },
                        sim.phoneNumber?.takeIf { it.isNotBlank() },
                    ).joinToString(" · ")
                },
                selection = setup.simSelection,
                onSelect = setupViewModel::selectSims,
                onNext = { step = STEP_GATEWAY },
            )

            STEP_GATEWAY -> GatewayStep(
                apiKey = settings.apiKeyInput,
                senderId = settings.senderIdInput,
                configured = settings.gatewayConfigured,
                canSave = settings.canSaveGateway,
                saveFailed = settings.saveFailed,
                testSend = settings.testSend,
                onApiKeyChange = settingsViewModel::updateApiKeyInput,
                onSenderIdChange = settingsViewModel::updateSenderIdInput,
                onSave = settingsViewModel::saveGateway,
                onTest = settingsViewModel::sendTest,
                onNext = { step = STEP_BATTERY },
            )

            STEP_BATTERY -> BatteryStep(
                exempt = setup.batteryExempt == true,
                onRequest = { context.requestBatteryExemption(setupViewModel.batteryOptimization) },
                onFinish = onFinished,
            )
        }
    }
}

@Composable
private fun StepHeader(titleRes: Int, bodyRes: Int) {
    Text(
        text = stringResource(titleRes),
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
    )
    Text(text = stringResource(bodyRes), style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun PermissionsStep(readyToIngest: Boolean, onGrant: () -> Unit, onNext: () -> Unit) {
    StepHeader(R.string.onb_perm_title, R.string.onb_perm_body)

    Button(onClick = onGrant, enabled = !readyToIngest, modifier = Modifier.fillMaxWidth()) {
        Text(
            stringResource(
                if (readyToIngest) R.string.onb_perm_granted else R.string.onb_perm_grant,
            ),
        )
    }

    // Hard gate. Without RECEIVE_SMS the app cannot see a payment at all, so
    // there is nothing further to configure.
    Button(onClick = onNext, enabled = readyToIngest, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.onb_next))
    }
}

@Composable
private fun SimStep(
    sims: List<Pair<Int, String>>,
    selection: SimSelection,
    onSelect: (SimSelection) -> Unit,
    onNext: () -> Unit,
) {
    StepHeader(R.string.onb_sim_title, R.string.onb_sim_body)

    OnboardingRadio(
        label = stringResource(R.string.settings_sim_all),
        selected = selection is SimSelection.AllSims,
        onClick = { onSelect(SimSelection.AllSims) },
    )
    sims.forEach { (slot, label) ->
        OnboardingRadio(
            label = label,
            selected = selection is SimSelection.Slots && slot in selection.slots,
            onClick = { onSelect(SimSelection.slot(slot)) },
        )
    }

    if (sims.isEmpty()) {
        Text(
            text = stringResource(R.string.settings_sim_none),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Button(onClick = onNext, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.onb_next))
    }
}

@Composable
private fun OnboardingRadio(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label)
    }
}

@Composable
private fun GatewayStep(
    apiKey: String,
    senderId: String,
    configured: Boolean,
    canSave: Boolean,
    saveFailed: Boolean,
    testSend: TestSendState,
    onApiKeyChange: (String) -> Unit,
    onSenderIdChange: (String) -> Unit,
    onSave: () -> Unit,
    onTest: (String) -> Unit,
    onNext: () -> Unit,
) {
    var testPhone by rememberSaveable { mutableStateOf("") }

    StepHeader(R.string.onb_gateway_title, R.string.onb_gateway_body)

    OutlinedTextField(
        value = apiKey,
        onValueChange = onApiKeyChange,
        label = { Text(stringResource(R.string.settings_api_key)) },
        visualTransformation = PasswordVisualTransformation(),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = senderId,
        onValueChange = onSenderIdChange,
        label = { Text(stringResource(R.string.settings_sender_id)) },
        supportingText = { Text(stringResource(R.string.settings_sender_id_help)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    if (saveFailed) {
        Text(
            text = stringResource(R.string.settings_gateway_save_failed),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }

    Button(onClick = onSave, enabled = canSave, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.save))
    }

    if (configured) {
        // BUILD-PLAN Phase 7 asks for this here, before setup finishes: an
        // unregistered sender ID is an account problem on SCOPE's side that no
        // amount of retrying fixes, and the agent should discover it now rather
        // than from a customer who never got their prices.
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
        Button(
            onClick = { onTest(testPhone) },
            enabled = testPhone.isNotBlank() && testSend !is TestSendState.Sending,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.settings_test_send))
        }

        when (testSend) {
            is TestSendState.Success -> ResultCard(stringResource(R.string.settings_test_ok), false)
            is TestSendState.Failure -> ResultCard(testSend.reason, true)
            else -> Unit
        }
    }

    // Gated on credentials being stored, not on the test passing. The test can
    // fail for reasons the agent must fix on the SCOPE account (unregistered
    // sender ID, empty balance) and cannot resolve from this screen — trapping
    // them here would make the app unusable over something it can't control.
    Button(onClick = onNext, enabled = configured, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.onb_next))
    }
}

@Composable
private fun ResultCard(text: String, isError: Boolean) {
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
        Text(text = text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(12.dp))
    }
}

@Composable
private fun BatteryStep(exempt: Boolean, onRequest: () -> Unit, onFinish: () -> Unit) {
    StepHeader(R.string.onb_battery_title, R.string.onb_battery_body)

    if (!exempt) {
        Button(onClick = onRequest, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.settings_battery_fix))
        }
    } else {
        Text(
            text = stringResource(R.string.settings_battery_ok),
            style = MaterialTheme.typography.bodyMedium,
        )
    }

    Spacer(Modifier.padding(4.dp))
    Button(onClick = onFinish, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.onb_finish))
    }
    // Not a gate — see the class doc.
    if (!exempt) {
        TextButton(onClick = onFinish, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.onb_skip))
        }
    }
}

private const val STEP_PERMISSIONS = 0
private const val STEP_SIM = 1
private const val STEP_GATEWAY = 2
private const val STEP_BATTERY = 3
private const val TOTAL_STEPS = 4
