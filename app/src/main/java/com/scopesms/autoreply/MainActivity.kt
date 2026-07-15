package com.scopesms.autoreply

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.scopesms.autoreply.ui.ScopeSmsApp
import com.scopesms.autoreply.ui.theme.ScopeSmsTheme

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
            ScopeSmsTheme {
                ScopeSmsApp(modifier = Modifier.fillMaxSize())
            }
        }
    }
}
