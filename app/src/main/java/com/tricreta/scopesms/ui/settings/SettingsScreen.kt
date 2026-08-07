package com.tricreta.scopesms.ui.settings

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.tricreta.scopesms.network.GatewayProvider
import com.tricreta.scopesms.telephony.SimInfo
import com.tricreta.scopesms.ui.common.requestBatteryExemption
import com.tricreta.scopesms.ui.reliability.OemGuidanceSection
import com.tricreta.scopesms.ui.update.UpdateSection
import kotlin.math.cos
import kotlin.math.sin

/**
 * Settings: which SIM, the gateway credentials, background reliability, version.
 *
 * Grouped into five cards — Automatic Replies, SIM & Senders, SMS Gateway,
 * General, About & Reset — per the client's explicit ask to de-clutter what
 * had grown into a long wall of section titles and paragraphs. Reset in
 * particular is deliberately understated (a plain text button, not its own
 * red card): it's a rare, destructive escape hatch, not something that should
 * visually compete with the settings someone actually adjusts often.
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
        // very top, above any section, so the agent can Share it to the developer.
        // This is the only way to diagnose a crash that reproduces on their handset
        // but not off-device.
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
        }

        SectionCard(stringResource(R.string.settings_section_replies)) {
            RepliesSection(
                toggles = state.toggles,
                onUnmatchedChange = viewModel::setUnmatchedEnabled,
                onMatchedChange = viewModel::setMatchedEnabled,
                onOffWindowChange = viewModel::setOffWindowEnabled,
            )
        }

        SectionCard(stringResource(R.string.settings_section_sim_senders)) {
            SimAndSendersSection(state = state, viewModel = viewModel)
        }

        SectionCard(stringResource(R.string.settings_gateway)) {
            GatewaySection(state = state, viewModel = viewModel)
        }

        SectionCard(stringResource(R.string.settings_section_general)) {
            GeneralSection(state = state, viewModel = viewModel, context = context)
        }

        SectionCard(stringResource(R.string.settings_section_about_reset)) {
            AboutAndResetSection(state = state, onRequestReset = { confirmReset = true })
        }
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

/**
 * One settings group, visually distinct as its own card — what makes the
 * screen read as "sections" rather than one long scroll of labels.
 */
@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            content()
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
 * The three independent reply flows, moved here from Home.
 *
 * They are the agent's throttle on sender-ID ban risk (CLAUDE.md, "What this app
 * is"): confirmations are higher-volume, so the agent turns that flow off on a
 * busy day. An empty rule list overrides all three anyway, so leaving them on
 * before prices are entered sends nothing.
 */
@Composable
private fun RepliesSection(
    toggles: com.tricreta.scopesms.domain.notifications.NotificationToggles,
    onUnmatchedChange: (Boolean) -> Unit,
    onMatchedChange: (Boolean) -> Unit,
    onOffWindowChange: (Boolean) -> Unit,
) {
    ToggleRow(
        icon = Icons.AutoMirrored.Filled.List,
        title = stringResource(R.string.toggle_unmatched_title),
        subtitle = stringResource(R.string.toggle_unmatched_subtitle),
        checked = toggles.unmatchedReplyEnabled,
        onCheckedChange = onUnmatchedChange,
    )
    ToggleRow(
        icon = Icons.Filled.ShoppingCart,
        title = stringResource(R.string.toggle_matched_title),
        subtitle = stringResource(R.string.toggle_matched_subtitle),
        checked = toggles.matchedReplyEnabled,
        onCheckedChange = onMatchedChange,
    )
    ToggleRow(
        icon = Icons.Filled.DateRange,
        title = stringResource(R.string.toggle_off_window_title),
        subtitle = stringResource(R.string.toggle_off_window_subtitle),
        checked = toggles.offWindowReplyEnabled,
        onCheckedChange = onOffWindowChange,
    )
}

@Composable
private fun ToggleRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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

/** Which SIM to read, and any extra trusted M-Pesa sender IDs — one merged section. */
@Composable
private fun SimAndSendersSection(state: SettingsUiState, viewModel: SettingsViewModel) {
    SimSection(
        sims = state.sims,
        selection = state.simSelection,
        onSelect = viewModel::selectSims,
    )

    HorizontalDivider()

    Text(
        text = stringResource(R.string.settings_trusted_senders),
        style = MaterialTheme.typography.labelLarge,
    )
    TrustedSendersSection(
        senders = state.trustedSenders,
        input = state.trustedSenderInput,
        canAdd = state.canAddTrustedSender,
        onInputChange = viewModel::updateTrustedSenderInput,
        onAdd = viewModel::addTrustedSender,
        onRemove = viewModel::removeTrustedSender,
    )
}

@Composable
private fun SimSection(
    sims: List<SimInfo>,
    selection: SimSelection,
    onSelect: (SimSelection) -> Unit,
) {
    Text(
        text = stringResource(R.string.settings_sim),
        style = MaterialTheme.typography.labelLarge,
    )

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
 * Points a new agent at where the currently-selected gateway account actually
 * comes from. BlazeTech keeps its existing direct API-keys link; HostPinnacle
 * links to their portal, where the agent's userid+password login lives (not
 * an API key — see [com.tricreta.scopesms.network.GatewayCredentials.userId]'s
 * doc for why this app uses that auth mode instead of the header-based one
 * HostPinnacle also offers). Without this, the credential fields have nothing
 * to type into them and no indication of where the values come from.
 */
@Composable
private fun ApiKeySignupHint(provider: GatewayProvider) {
    val url = when (provider) {
        GatewayProvider.BLAZETECH -> "https://sms.blazetechscope.com/apikeys"
        GatewayProvider.HOSTPINNACLE -> "https://smsportal.hostpinnacle.co.ke/"
    }
    val prefixRes = when (provider) {
        GatewayProvider.BLAZETECH -> R.string.settings_gateway_get_key
        GatewayProvider.HOSTPINNACLE -> R.string.settings_gateway_get_account
    }
    val prefix = stringResource(prefixRes)
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

/**
 * Which SMS gateway is active — BlazeTech (the original, still live in
 * production) or HostPinnacle. Each has its own credentials + sender ID,
 * entered and saved independently ([GatewaySection] below); switching here
 * never loses or overwrites the other provider's saved credentials
 * (CLAUDE.md, "SMS Gateway Integration").
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GatewayProviderDropdown(
    selected: GatewayProvider,
    onSelect: (GatewayProvider) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val options = listOf(
        GatewayProvider.BLAZETECH to stringResource(R.string.settings_gateway_provider_blazetech),
        GatewayProvider.HOSTPINNACLE to stringResource(R.string.settings_gateway_provider_hostpinnacle),
    )
    val selectedLabel = options.first { it.first == selected }.second

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
            readOnly = true,
            value = selectedLabel,
            onValueChange = {},
            label = { Text(stringResource(R.string.settings_gateway_provider_label)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { (provider, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        expanded = false
                        onSelect(provider)
                    },
                )
            }
        }
    }
}

@Composable
private fun GatewaySection(state: SettingsUiState, viewModel: SettingsViewModel) {
    var testPhone by rememberSaveable { mutableStateOf("") }

    GatewayProviderDropdown(
        selected = state.activeGatewayProvider,
        onSelect = viewModel::selectGatewayProvider,
    )

    ApiKeySignupHint(state.activeGatewayProvider)
    Text(
        text = stringResource(R.string.settings_gateway_balance),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (state.gatewayConfigured) {
        val savedText = if (state.showUsernameField && state.hostPinnacleUserId.isNotBlank()) {
            stringResource(
                R.string.settings_gateway_saved_with_user,
                state.hostPinnacleUserId,
                state.maskedApiKey,
                state.senderId,
            )
        } else {
            stringResource(R.string.settings_gateway_saved, state.maskedApiKey, state.senderId)
        }
        Text(text = savedText, style = MaterialTheme.typography.bodyMedium)
    }

    // HostPinnacle authenticates with userid+password, not an API key — see
    // GatewayCredentials.userId's doc. BlazeTech has no username concept.
    if (state.showUsernameField) {
        OutlinedTextField(
            value = state.usernameInput,
            onValueChange = viewModel::updateUsernameInput,
            label = { Text(stringResource(R.string.settings_username)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    OutlinedTextField(
        value = state.apiKeyInput,
        onValueChange = viewModel::updateApiKeyInput,
        label = {
            Text(
                stringResource(
                    if (state.showUsernameField) R.string.settings_password else R.string.settings_api_key,
                ),
            )
        },
        // The key/password is a secret and Settings is the screen most likely
        // to be screenshotted or shoulder-surfed.
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

    HorizontalDivider()

    // The test send is the only way to find out that a sender ID isn't
    // registered before a real customer is waiting on the reply.
    Text(
        text = stringResource(R.string.settings_test_title),
        style = MaterialTheme.typography.labelLarge,
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

/** Theme (icon picker) + background reliability — the "keep it running right" basics. */
@Composable
private fun GeneralSection(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    context: Context,
) {
    Text(text = stringResource(R.string.settings_appearance), style = MaterialTheme.typography.labelLarge)
    ThemeSection(selected = state.themePreference, onSelect = viewModel::setThemePreference)

    HorizontalDivider()

    Text(text = stringResource(R.string.settings_reliability), style = MaterialTheme.typography.labelLarge)
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
}

/**
 * System / Light / Dark as three icon segments instead of a labelled radio
 * list — the client's explicit ask ("just toggle to moon or sun or system").
 * Each segment still carries a one-word caption so the icon alone doesn't
 * have to carry all the meaning.
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (preference, labelRes) ->
            SegmentedButton(
                selected = selected == preference,
                onClick = { onSelect(preference) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                // Suppresses the default checkmark-on-selected icon slot — our
                // own icon below already communicates the selection.
                icon = {},
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ThemeIcon(preference)
                    Text(stringResource(labelRes))
                }
            }
        }
    }
}

@Composable
private fun ThemeIcon(preference: ThemePreference) {
    when (preference) {
        ThemePreference.SYSTEM -> Icon(
            imageVector = Icons.Filled.Settings,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        ThemePreference.LIGHT -> SunIcon(modifier = Modifier.size(18.dp))
        ThemePreference.DARK -> MoonIcon(modifier = Modifier.size(18.dp))
    }
}

/**
 * A minimal sun glyph (filled circle + 8 rays) drawn with [Canvas] primitives
 * rather than a bundled icon: `material-icons-core` (this app's deliberate,
 * documented choice — see `gradle/libs.versions.toml` — to avoid the ~10MB
 * `-extended` artifact) has no sun/moon icon at all. A hand-drawn path would
 * risk a malformed-path crash with no easy way to verify off-device; circles
 * and lines can't be malformed.
 */
@Composable
private fun SunIcon(modifier: Modifier = Modifier, tint: Color = MaterialTheme.colorScheme.onSurface) {
    Canvas(modifier = modifier) {
        val ringRadius = size.minDimension * 0.22f
        drawCircle(color = tint, radius = ringRadius, center = center)
        val rayInner = ringRadius + size.minDimension * 0.08f
        val rayOuter = rayInner + size.minDimension * 0.16f
        for (i in 0 until 8) {
            val angle = Math.toRadians((i * 45).toDouble())
            val dx = cos(angle).toFloat()
            val dy = sin(angle).toFloat()
            drawLine(
                color = tint,
                start = Offset(center.x + dx * rayInner, center.y + dy * rayInner),
                end = Offset(center.x + dx * rayOuter, center.y + dy * rayOuter),
                strokeWidth = size.minDimension * 0.07f,
                cap = StrokeCap.Round,
            )
        }
    }
}

/**
 * A plain filled disc for "dark" — deliberately not a hand-drawn crescent
 * (same crash-risk reasoning as [SunIcon]). Paired with the always-visible
 * "Dark" caption and the rayed sun right next to it, a plain dot reads
 * clearly as "not-sun" without needing a literal crescent outline.
 */
@Composable
private fun MoonIcon(modifier: Modifier = Modifier, tint: Color = MaterialTheme.colorScheme.onSurface) {
    Canvas(modifier = modifier) {
        drawCircle(color = tint, radius = size.minDimension * 0.32f, center = center)
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

/**
 * Version/update info, plus the reset action.
 *
 * Reset is a plain, muted text button at the bottom — not its own red card.
 * It's a rare, destructive, "things got messy" escape hatch (still behind the
 * same scary confirmation dialog), and giving it card-level visual weight
 * made it read as if it were a normal, everyday setting.
 */
@Composable
private fun AboutAndResetSection(state: SettingsUiState, onRequestReset: () -> Unit) {
    Text(
        text = stringResource(R.string.settings_version, state.versionName, state.versionCode),
        style = MaterialTheme.typography.bodyMedium,
    )
    // The in-app updater — its own ViewModel so the download is cancellable
    // and it owns the install/permission launchers. See ui/update/.
    UpdateSection(modifier = Modifier.fillMaxWidth())

    HorizontalDivider()

    TextButton(onClick = onRequestReset) {
        Text(
            text = stringResource(R.string.settings_reset),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
