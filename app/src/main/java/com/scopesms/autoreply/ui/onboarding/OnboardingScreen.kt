package com.scopesms.autoreply.ui.onboarding

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.scopesms.autoreply.R
import com.scopesms.autoreply.domain.sim.SimSelection
import com.scopesms.autoreply.ui.common.requestBatteryExemption
import com.scopesms.autoreply.ui.setup.SetupViewModel

/**
 * First-run setup: permissions → SIM → keep-running.
 *
 * ## Three fixes over the first cut, all reported from a real device
 * 1. **Everything is inside a [Surface].** Without one, `LocalContentColor`
 *    defaults to black regardless of theme, so on a dark-mode phone every label
 *    was black text on a black window — invisible. `Surface(color = background)`
 *    supplies the matching `onBackground` content colour automatically, which is
 *    the whole reason it exists.
 * 2. **The gateway step is gone.** Entering the API key and sender ID belongs in
 *    Settings, not in first-run — a new agent shouldn't be blocked on SCOPE
 *    account details to finish setup. The sender ID also now defaults to
 *    `SKYSCOPE_`, so most agents only ever paste a key.
 * 3. **Animated and centred.** Each step slides in, the icon pulses, and the
 *    content is centred rather than hugging the top-left.
 *
 * Reuses [SetupViewModel] — Phase 1's permission/SIM logic, which its exit
 * criteria were proven against.
 */
@Composable
fun OnboardingScreen(
    modifier: Modifier = Modifier,
    onFinished: () -> Unit,
    viewModel: SetupViewModel = viewModel(factory = SetupViewModel.Factory),
) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // The agent leaves for system UI to grant things and comes back.
    LifecycleResumeEffect(viewModel) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { viewModel.refresh() }

    // The Surface is load-bearing, not decoration — see the class doc.
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            StepDots(current = step, total = TOTAL_STEPS)
            Spacer(Modifier.height(8.dp))

            AnimatedContent(
                targetState = step,
                transitionSpec = {
                    val forward = targetState > initialState
                    val dir = if (forward) 1 else -1
                    (slideInHorizontally { it * dir } + fadeIn()) togetherWith
                        (slideOutHorizontally { -it * dir } + fadeOut())
                },
                label = "onboarding-step",
            ) { current ->
                when (current) {
                    STEP_PERMISSIONS -> PermissionsStep(
                        readyToIngest = state.readyToIngest,
                        onGrant = { permissionLauncher.launch(viewModel.permissionsToRequest()) },
                        onNext = { step = STEP_SIM },
                    )

                    STEP_SIM -> SimStep(
                        sims = state.sims.map { sim ->
                            sim.slotIndex to listOfNotNull(
                                sim.slotLabel,
                                sim.carrierName.takeIf { it.isNotBlank() },
                                sim.phoneNumber?.takeIf { it.isNotBlank() },
                            ).joinToString(" · ")
                        },
                        selection = state.simSelection,
                        onSelect = viewModel::selectSims,
                        onBack = { step = STEP_PERMISSIONS },
                        onNext = { step = STEP_BATTERY },
                    )

                    STEP_BATTERY -> BatteryStep(
                        exempt = state.batteryExempt == true,
                        onRequest = { context.requestBatteryExemption(viewModel.batteryOptimization) },
                        onBack = { step = STEP_SIM },
                        onFinish = onFinished,
                    )
                }
            }
        }
    }
}

@Composable
private fun StepDots(current: Int, total: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        for (i in 0 until total) {
            val active = i <= current
            Box(
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .height(6.dp)
                    .width(if (active) 28.dp else 16.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(
                        if (active) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                        },
                    ),
            )
        }
    }
}

/** The pulsing hero icon each step opens with. */
@Composable
private fun PulseIcon(icon: ImageVector) {
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 0.9f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Reverse),
        label = "pulse-scale",
    )
    Box(
        modifier = Modifier
            .padding(vertical = 16.dp)
            .size(120.dp)
            .scale(pulse)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(56.dp),
        )
    }
}

@Composable
private fun StepScaffold(
    icon: ImageVector,
    titleRes: Int,
    bodyRes: Int,
    content: ColumnScopeContent,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PulseIcon(icon)
        Text(
            text = stringResource(titleRes),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(bodyRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        content()
    }
}

/** Trivial alias so [StepScaffold] can take a trailing content lambda. */
private typealias ColumnScopeContent = @Composable () -> Unit

@Composable
private fun PermissionsStep(readyToIngest: Boolean, onGrant: () -> Unit, onNext: () -> Unit) {
    StepScaffold(Icons.Default.Lock, R.string.onb_perm_title, R.string.onb_perm_body) {
        PrimaryButton(
            text = stringResource(
                if (readyToIngest) R.string.onb_perm_granted else R.string.onb_perm_grant,
            ),
            onClick = onGrant,
            enabled = !readyToIngest,
        )
        Spacer(Modifier.height(12.dp))
        // Hard gate: without RECEIVE_SMS the app can't see a payment at all.
        PrimaryButton(
            text = stringResource(R.string.onb_next),
            onClick = onNext,
            enabled = readyToIngest,
        )
    }
}

@Composable
private fun SimStep(
    sims: List<Pair<Int, String>>,
    selection: SimSelection,
    onSelect: (SimSelection) -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit,
) {
    StepScaffold(Icons.Default.Phone, R.string.onb_sim_title, R.string.onb_sim_body) {
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
        Spacer(Modifier.height(16.dp))
        BackNextRow(onBack = onBack, onNext = onNext)
    }
}

@Composable
private fun BatteryStep(
    exempt: Boolean,
    onRequest: () -> Unit,
    onBack: () -> Unit,
    onFinish: () -> Unit,
) {
    StepScaffold(
        icon = if (exempt) Icons.Default.CheckCircle else Icons.Default.Notifications,
        titleRes = R.string.onb_battery_title,
        bodyRes = R.string.onb_battery_body,
    ) {
        if (!exempt) {
            PrimaryButton(stringResource(R.string.settings_battery_fix), onRequest)
        } else {
            Text(
                text = stringResource(R.string.settings_battery_ok),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(16.dp))
        PrimaryButton(stringResource(R.string.onb_finish), onFinish)
        // Not a gate: the exemption is genuinely optional, and on some OEM builds
        // the dialog doesn't exist at all.
        if (!exempt) {
            TextButton(onClick = onFinish, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.onb_skip))
            }
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.onb_back))
        }
    }
}

@Composable
private fun PrimaryButton(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(14.dp),
    ) {
        Text(text, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun BackNextRow(onBack: () -> Unit, onNext: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onBack, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.onb_back))
        }
        Button(
            onClick = onNext,
            modifier = Modifier.weight(2f).height(52.dp),
            shape = RoundedCornerShape(14.dp),
        ) {
            Text(stringResource(R.string.onb_next), fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun OnboardingRadio(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

private const val STEP_PERMISSIONS = 0
private const val STEP_SIM = 1
private const val STEP_BATTERY = 2
private const val TOTAL_STEPS = 3
