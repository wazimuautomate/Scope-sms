package com.scopesms.autoreply.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.scopesms.autoreply.di.AppContainer
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Owns the one piece of state above every screen: has setup been completed?
 */
class AppViewModel(
    application: Application,
    private val container: AppContainer,
) : AndroidViewModel(application) {

    /** Null until DataStore's first read lands — see [ScopeSmsApp]. */
    val onboardingComplete: StateFlow<Boolean?> = container.settings.onboardingComplete
        .map { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    fun completeOnboarding() {
        viewModelScope.launch { container.settings.setOnboardingComplete(true) }
    }

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    ?: error("No Application in CreationExtras.")
                AppViewModel(app, AppContainer.from(app))
            }
        }
    }
}
