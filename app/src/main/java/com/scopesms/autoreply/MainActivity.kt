package com.scopesms.autoreply

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.scopesms.autoreply.ui.setup.SetupScreen
import com.scopesms.autoreply.ui.setup.SetupViewModel
import com.scopesms.autoreply.ui.theme.ScopeSmsTheme

/**
 * Sole entry point. Phase 7 replaces this with the real navigation graph
 * (Onboarding → Home → Rules → Templates → Activity Log → Settings); until then
 * it hosts Phase 1's setup screen so the permission/SIM flow can be exercised
 * on a real device.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            ScopeSmsTheme {
                val viewModel: SetupViewModel = viewModel(factory = SetupViewModel.Factory)

                // Permissions and the battery exemption are granted in system
                // UI, which means the agent leaves and comes back. Re-reading on
                // every resume is what makes the screen reflect what they just
                // did — without it they return to a screen still insisting the
                // permission they granted is missing.
                LifecycleResumeEffect(viewModel) {
                    viewModel.refresh()
                    onPauseOrDispose { }
                }

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    SetupScreen(
                        modifier = Modifier.padding(innerPadding),
                        viewModel = viewModel,
                    )
                }
            }
        }
    }
}
