package com.tricreta.scopesms.ui.reliability

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.tricreta.scopesms.domain.reliability.OemAutostartGuide
import com.tricreta.scopesms.domain.reliability.OemFamily
import com.tricreta.scopesms.domain.reliability.OemGuidance
import com.tricreta.scopesms.domain.reliability.ReliabilityIssue
import com.tricreta.scopesms.domain.reliability.Severity
import com.tricreta.scopesms.ui.theme.ScopeSmsTheme

/**
 * Phase 9's in-app guidance, as stateless composables.
 *
 * ### Why these are stateless, and why there's no screen here
 * Phase 7 owns the app's screens, navigation and Settings layout, and it is
 * being built in a parallel session against a UI spec this session cannot see
 * (`01-UI-DESIGN-PROMPT.md` is missing from the repo — see memory.md). Shipping
 * a whole Settings screen from here would collide with that work and lose.
 *
 * So Phase 9 ships the *content* and lets Phase 7 place it: pure functions of
 * their arguments, no ViewModel, no navigation, no `Context`.
 *
 * **Phase 7 — how to wire this up.** Both composables are drop-in; nothing else
 * in Phase 9 needs touching:
 * ```
 * val launcher = AppContainer.from(context).oemSettingsLauncher
 * OemGuidanceSection(
 *     guidance = launcher.guidance,
 *     canOpenSettings = launcher.hasDeepLink(),
 *     onOpenSettings = { launcher.open(activity) },   // needs an Activity context
 * )
 * ```
 * For the health card, collect `container.reliabilityInspector.check()` (it
 * suspends — it reads DataStore) and render one [ReliabilityIssueCard] per
 * issue, in order. The list is already sorted worst-first; don't re-sort it.
 */

/**
 * The "keep Scope SMS running" instructions for this phone.
 *
 * @param guidance from `OemSettingsLauncher.guidance`.
 * @param canOpenSettings `OemSettingsLauncher.hasDeepLink()`. Drives whether the
 *   shortcut button is offered at all — on most phones no shortcut resolves, and
 *   a button that does nothing is worse than no button.
 * @param onOpenSettings invoke `OemSettingsLauncher.open(activityContext)`.
 */
@Composable
fun OemGuidanceSection(
    guidance: OemGuidance,
    canOpenSettings: Boolean,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Collapsed by default, at the agent's request. These instructions matter
    // once — while setting the phone up — and then get in the way of the settings
    // the agent actually returns to. A tappable header keeps them one tap away
    // without letting them dominate the screen every time.
    var expanded by rememberSaveable { mutableStateOf(false) }

    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Keep Scope SMS running",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = if (expanded) {
                        Icons.Default.KeyboardArrowUp
                    } else {
                        Icons.Default.KeyboardArrowDown
                    },
                    contentDescription = if (expanded) "Collapse" else "Expand",
                )
            }

            if (expanded) {
                Text(
                    // Deliberately explains *why* before *what*. This screen asks
                    // the agent to go digging through a vendor settings app, and
                    // an instruction with no reason attached is one they'll
                    // abandon halfway. The reason is also the honest one: we cannot
                    // do this for them, no matter how the app is written.
                    text = "This phone can stop Scope SMS in the background, which means " +
                        "customer payments arrive with no reply. Android's battery setting " +
                        "is not enough on its own — this phone has its own list, and only " +
                        "you can add Scope SMS to it.",
                    style = MaterialTheme.typography.bodyMedium,
                )

                guidance.steps.forEachIndexed { index, step ->
                    Row(verticalAlignment = Alignment.Top) {
                        Text(
                            text = "${index + 1}.",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.width(24.dp),
                        )
                        Text(text = step, style = MaterialTheme.typography.bodyMedium)
                    }
                }

                guidance.caveat?.let { caveat ->
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                        ),
                    ) {
                        Text(
                            text = caveat,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }

                // Even when the vendor's own screen can't be reached,
                // [OemSettingsLauncher.open] falls back to this app's system
                // settings page, so the button always lands somewhere useful. A
                // dead button was a reported bug; `canOpenSettings` only picks the
                // label.
                Button(onClick = onOpenSettings) {
                    Text(
                        text = if (canOpenSettings) {
                            guidance.settingsAppName?.let { "Open $it" } ?: "Open settings"
                        } else {
                            "Open app settings"
                        },
                    )
                }
            }
        }
    }
}

/**
 * A single health problem, for Settings or the dashboard.
 *
 * Colour tracks [Severity] rather than being decorative: BLOCKING means the
 * agent is missing payments right now and should look like it.
 */
@Composable
fun ReliabilityIssueCard(
    issue: ReliabilityIssue,
    modifier: Modifier = Modifier,
) {
    val blocking = issue.severity == Severity.BLOCKING

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (blocking) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.secondaryContainer
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = issue.title,
                style = MaterialTheme.typography.titleSmall,
                color = if (blocking) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onSecondaryContainer
                },
            )
            Text(
                text = issue.detail,
                style = MaterialTheme.typography.bodySmall,
                color = if (blocking) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onSecondaryContainer
                },
            )
        }
    }
}

// --- Previews ---------------------------------------------------------------
//
// The only way to look at this screen without a device. CI cannot render them
// and there is no local Android Studio (CLAUDE.md constraint 8), so they exist
// for whoever picks Phase 7 up with an IDE — and they at least prove the
// composables compile against real data.

@Preview(showBackground = true)
@Composable
private fun PreviewTranssionGuidance() {
    ScopeSmsTheme {
        OemGuidanceSection(
            guidance = OemAutostartGuide.guidanceFor(OemFamily.TRANSSION),
            canOpenSettings = true,
            onOpenSettings = {},
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewGenericGuidanceWithoutShortcut() {
    ScopeSmsTheme {
        OemGuidanceSection(
            guidance = OemAutostartGuide.guidanceFor(OemFamily.GENERIC),
            canOpenSettings = false,
            onOpenSettings = {},
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun PreviewBlockingIssue() {
    ScopeSmsTheme {
        ReliabilityIssueCard(
            issue = ReliabilityIssue.WatchedSlotsMissing(watchedSlots = setOf(1), activeSlots = setOf(0)),
            modifier = Modifier.padding(16.dp),
        )
    }
}
