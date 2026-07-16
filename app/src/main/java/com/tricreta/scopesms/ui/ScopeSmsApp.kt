package com.tricreta.scopesms.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tricreta.scopesms.R
import com.tricreta.scopesms.ui.home.HomeScreen
import com.tricreta.scopesms.ui.log.ActivityLogScreen
import com.tricreta.scopesms.ui.onboarding.OnboardingScreen
import com.tricreta.scopesms.ui.rules.RulesScreen
import com.tricreta.scopesms.ui.settings.SettingsScreen
import com.tricreta.scopesms.ui.templates.TemplatesScreen

/**
 * The five places the agent can be.
 *
 * @param labelRes shown under the bottom-bar icon.
 */
enum class Destination(val labelRes: Int, val icon: ImageVector) {
    HOME(R.string.nav_home, Icons.Default.Home),
    RULES(R.string.nav_rules, Icons.Default.ShoppingCart),
    TEMPLATES(R.string.nav_templates, Icons.Default.Edit),
    LOG(R.string.nav_log, Icons.AutoMirrored.Filled.List),
    SETTINGS(R.string.nav_settings, Icons.Default.Settings),
}

/**
 * The app shell: an onboarding gate, then a five-tab scaffold.
 *
 * ## Why navigation is hand-rolled
 * No `navigation-compose`. The graph is five flat, co-equal destinations with no
 * arguments, no deep links and no nested stacks — the whole of it is the `when`
 * below. A nav library would add a dependency, a route-string DSL and a
 * back-stack model to express `current = RULES`, and each is one more thing that
 * can break on a project whose only compiler used to be a CI runner. If a screen
 * ever needs real arguments or deep linking, that is the moment to reach for the
 * library — not before.
 *
 * Back behaviour is the one thing a library would give for free, so it is
 * explicit: from any tab, back returns to Home; from Home, back leaves the app.
 * That is what the system bar does everywhere else, and what an agent pressing
 * back after checking their log expects.
 */
@Composable
fun ScopeSmsApp(
    modifier: Modifier = Modifier,
    viewModel: AppViewModel = viewModel(factory = AppViewModel.Factory),
) {
    val onboardingComplete by viewModel.onboardingComplete.collectAsStateWithLifecycle()

    // Null while DataStore's first read is in flight. Drawing nothing for those
    // few milliseconds is deliberate: defaulting to "not onboarded" would flash
    // the setup wizard at an agent who finished it weeks ago, every cold start.
    when (onboardingComplete) {
        null -> Unit
        false -> OnboardingScreen(modifier = modifier, onFinished = viewModel::completeOnboarding)
        true -> MainScaffold(modifier = modifier)
    }
}

@Composable
private fun MainScaffold(modifier: Modifier = Modifier) {
    // rememberSaveable: a rotation shouldn't dump the agent back on Home.
    var current by rememberSaveable { mutableStateOf(Destination.HOME) }

    BackHandler(enabled = current != Destination.HOME) { current = Destination.HOME }

    Scaffold(
        modifier = modifier,
        bottomBar = {
            NavigationBar {
                Destination.entries.forEach { destination ->
                    NavigationBarItem(
                        selected = current == destination,
                        onClick = { current = destination },
                        icon = { Icon(destination.icon, contentDescription = null) },
                        label = { Text(stringResource(destination.labelRes)) },
                    )
                }
            }
        },
    ) { padding ->
        val contentModifier = Modifier.padding(padding)
        when (current) {
            Destination.HOME -> HomeScreen(
                modifier = contentModifier,
                onAddRules = { current = Destination.RULES },
                onOpenSettings = { current = Destination.SETTINGS },
                onOpenLog = { current = Destination.LOG },
            )

            Destination.RULES -> RulesScreen(modifier = contentModifier)
            Destination.TEMPLATES -> TemplatesScreen(modifier = contentModifier)
            Destination.LOG -> ActivityLogScreen(modifier = contentModifier)
            Destination.SETTINGS -> SettingsScreen(modifier = contentModifier)
        }
    }
}
