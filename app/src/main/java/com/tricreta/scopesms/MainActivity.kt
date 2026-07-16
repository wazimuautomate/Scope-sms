package com.tricreta.scopesms

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tricreta.scopesms.di.AppContainer
import com.tricreta.scopesms.domain.settings.ThemePreference
import com.tricreta.scopesms.ui.ScopeSmsApp
import com.tricreta.scopesms.ui.theme.ScopeSmsTheme

/**
 * Sole entry point.
 *
 * Everything above this is [ScopeSmsApp]: the onboarding gate and the five-tab
 * scaffold. Phase 1's plain `SetupScreen` — which existed only so its exit
 * criteria were tappable on a real device — is gone, replaced rather than
 * extended, as its own note asked for.
 *
 * Note what this class does *not* do: nothing here starts SMS ingestion. That
 * runs from a manifest-registered receiver whether or not an Activity has ever
 * been opened, which is the entire point — the agent's phone is in their pocket
 * when the payment lands.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            // The agent's light/dark/system choice, applied to the whole app.
            // AppContainer.from uses the application context, so reading it from
            // an Activity here leaks nothing. The initial SYSTEM is only in play
            // for the first frame before DataStore's read lands.
            val settings = remember { AppContainer.from(this).settings }
            val themePreference by settings.themePreference
                .collectAsStateWithLifecycle(initialValue = ThemePreference.DEFAULT)

            val darkTheme = when (themePreference) {
                ThemePreference.SYSTEM -> isSystemInDarkTheme()
                ThemePreference.LIGHT -> false
                ThemePreference.DARK -> true
            }

            ScopeSmsTheme(darkTheme = darkTheme) {
                ScopeSmsApp(modifier = Modifier.fillMaxSize())
            }
        }
    }
}
