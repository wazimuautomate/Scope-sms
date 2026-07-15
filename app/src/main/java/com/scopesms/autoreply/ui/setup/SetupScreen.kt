package com.scopesms.autoreply.ui.setup

import android.content.ActivityNotFoundException
import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.scopesms.autoreply.domain.sim.SimSelection
import com.scopesms.autoreply.telephony.SimInfo
import com.scopesms.autoreply.ui.theme.ScopeSmsTheme

/**
 * Phase 1's setup screen: grant permissions, pick the business SIM, get the
 * battery exemption.
 *
 * **Deliberately plain.** Phase 7 owns the designed onboarding and Settings
 * screens per BUILD-PLAN, and this is not an attempt at them — no motion, no
 * design language, no attempt at the Stitch layouts. It exists because Phase 1's
 * exit criteria ("lists both SIMs, persists the chosen filter across restarts
 * and reboot") can only be proven on a real device, and that needs something
 * installable to tap. Phase 7 should replace it outright.
 */
@Composable
fun SetupScreen(
    modifier: Modifier = Modifier,
    viewModel: SetupViewModel = viewModel(factory = SetupViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        // Ignore the result map and re-read from the system instead. The map
        // reports only what was asked in this round, while the screen shows the
        // truth for all of them — and re-reading also picks up the SIM list,
        // which only becomes readable once READ_PHONE_STATE lands.
        viewModel.refresh()
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Scope SMS setup", style = MaterialTheme.typography.headlineSmall)
        }

        item {
            SectionCard(title = "1. Permissions") {
                state.permissions.forEach { status ->
                    Text(
                        text = buildString {
                            append(if (status.granted) "✓ " else "✗ ")
                            append(status.permission.id.substringAfterLast('.'))
                            if (status.permission.isOptional) append(" (optional)")
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = status.permission.rationale,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!state.readyToIngest) {
                    Button(
                        onClick = { permissionLauncher.launch(viewModel.permissionsToRequest()) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Grant permissions")
                    }
                } else {
                    Text(
                        text = "All required permissions granted.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        item {
            SectionCard(title = "2. Which SIM receives M-Pesa?") {
                SimPicker(
                    sims = state.sims,
                    selection = state.simSelection,
                    onSelect = viewModel::selectSims,
                )
            }
        }

        item {
            SectionCard(title = "3. Background reliability") {
                Text(
                    text = if (state.batteryExempt == true) {
                        "✓ Battery optimisation is off for Scope SMS."
                    } else {
                        "✗ Android may stop Scope SMS in the background, so replies " +
                            "would stop going out. Turn battery optimisation off."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (state.batteryExempt != true) {
                    Button(
                        onClick = { context.requestBatteryExemption(viewModel) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Turn off battery optimisation")
                    }
                }
            }
        }

        item {
            TextButton(onClick = viewModel::refresh) { Text("Refresh status") }
        }
    }
}

/**
 * The SIM picker.
 *
 * Labels lead with the slot ("SIM 1"), because that is the durable identity the
 * choice is stored against and the thing the agent can physically point at.
 * Carrier and number are supporting detail — and both can be blank, which is
 * why neither is load-bearing here.
 */
@Composable
private fun SimPicker(
    sims: List<SimInfo>,
    selection: SimSelection,
    onSelect: (SimSelection) -> Unit,
) {
    if (sims.isEmpty()) {
        Text(
            text = "No SIMs detected. Grant the phone permission above, then tap " +
                "Refresh status.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    Column(Modifier.selectableGroup()) {
        sims.forEach { sim ->
            val selected = selection is SimSelection.Slots && sim.slotIndex in selection.slots
            SimOption(
                label = sim.pickerLabel(),
                selected = selected,
                onClick = { onSelect(SimSelection.slot(sim.slotIndex)) },
            )
        }
        SimOption(
            label = "Both SIMs",
            selected = selection is SimSelection.AllSims,
            onClick = { onSelect(SimSelection.AllSims) },
        )
    }
}

@Composable
private fun SimOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // selectable() on the Row, with the RadioButton's own onClick null:
            // the whole row is one target, and the radio isn't announced as a
            // second, separate control to a screen reader.
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(text = label, modifier = Modifier.padding(start = 12.dp))
    }
}

private fun SimInfo.pickerLabel(): String = buildString {
    append(slotLabel)
    val carrier = displayName ?: carrierName.takeIf { it.isNotBlank() }
    if (carrier != null) append(" · $carrier")
    // Only ever additive — commonly null (see SimInfo.phoneNumber).
    phoneNumber?.let { append(" · $it") }
}

/**
 * Asks for the battery exemption, falling back to the system list.
 *
 * The one-tap dialog doesn't exist on every OEM build, so
 * [ActivityNotFoundException] is an expected outcome, not an edge case — and
 * the fallback can be missing too, hence the second catch. Failing silently
 * here would leave the agent tapping a dead button.
 */
private fun Context.requestBatteryExemption(viewModel: SetupViewModel) {
    val manager = viewModel.batteryOptimization
    try {
        startActivity(manager.requestExemptionIntent())
    } catch (e: ActivityNotFoundException) {
        Log.i(TAG, "No battery-exemption dialog on this device; opening the settings list.", e)
        try {
            startActivity(manager.settingsListIntent())
        } catch (e2: ActivityNotFoundException) {
            Log.w(TAG, "No battery-optimisation settings screen either.", e2)
            Toast.makeText(
                this,
                "Open Settings › Apps › Scope SMS › Battery and allow background activity.",
                Toast.LENGTH_LONG,
            ).show()
        }
    }
}

private const val TAG = "SetupScreen"

@Preview(name = "Light", showBackground = true)
@Composable
private fun SimPickerPreview() {
    ScopeSmsTheme {
        Column(Modifier.padding(16.dp)) {
            SimPicker(
                sims = listOf(
                    SimInfo(1, 0, "Safaricom", "0712345678", "Safaricom"),
                    SimInfo(2, 1, "Airtel", null, null),
                ),
                selection = SimSelection.slot(0),
                onSelect = {},
            )
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}
