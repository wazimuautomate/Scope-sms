package com.tricreta.scopesms.ui.update

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.tricreta.scopesms.di.AppContainer
import com.tricreta.scopesms.domain.update.UpdateResolution
import com.tricreta.scopesms.domain.update.UpdateTarget
import com.tricreta.scopesms.update.AppUpdater
import com.tricreta.scopesms.update.DownloadStep
import com.tricreta.scopesms.update.UpdateError
import com.tricreta.scopesms.update.UpdateFlowState
import com.tricreta.scopesms.update.VerifyResult
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Drives the Settings "Check for updates" flow. On demand only — never a
 * background poll: the agent's connection is metered and they pay for the data.
 *
 * The engine ([AppUpdater]) does all network/file/PM work and only *builds* the
 * install intents; this ViewModel owns the state and cancellation, and the
 * composable launches the intents (it has the Activity context). Split that way
 * so nothing here holds an Activity and the download is cleanly cancellable.
 */
class UpdateViewModel(
    application: Application,
    private val updater: AppUpdater,
) : AndroidViewModel(application) {

    private val _state = MutableStateFlow<UpdateFlowState>(UpdateFlowState.Idle)
    val state: StateFlow<UpdateFlowState> = _state.asStateFlow()

    private var downloadJob: Job? = null

    fun check() {
        val current = _state.value
        if (current is UpdateFlowState.Checking || current is UpdateFlowState.Downloading) return
        _state.value = UpdateFlowState.Checking
        viewModelScope.launch {
            _state.value = when (val resolution = updater.check()) {
                is UpdateResolution.Available -> UpdateFlowState.Available(resolution.target, resolution.forced)
                UpdateResolution.UpToDate -> UpdateFlowState.UpToDate
                UpdateResolution.Unknown -> UpdateFlowState.Error(UpdateError.ManifestUnreadable)
            }
        }
    }

    fun download(target: UpdateTarget, forced: Boolean) {
        if (downloadJob?.isActive == true) return
        downloadJob = viewModelScope.launch {
            _state.value = UpdateFlowState.Downloading(null, forced)
            var done: DownloadStep.Done? = null
            updater.download(target).collect { step ->
                when (step) {
                    is DownloadStep.Progress -> _state.value = UpdateFlowState.Downloading(step.percent, forced)
                    is DownloadStep.Done -> done = step
                    is DownloadStep.Failed -> _state.value = UpdateFlowState.Error(step.error)
                }
            }
            val result = done ?: return@launch // a Failed step already set the error state
            _state.value = UpdateFlowState.Verifying
            _state.value = when (updater.verify(result.file, result.sha256Hex, target)) {
                VerifyResult.Ok -> UpdateFlowState.ReadyToInstall(result.file, forced)
                VerifyResult.HashMismatch -> UpdateFlowState.Error(UpdateError.HashMismatch)
                VerifyResult.WrongPackage -> UpdateFlowState.Error(UpdateError.WrongPackage)
                VerifyResult.SignatureMismatch -> UpdateFlowState.Error(UpdateError.SignatureMismatch)
            }
        }
    }

    fun cancel() {
        downloadJob?.cancel()
        downloadJob = null
        _state.value = UpdateFlowState.Idle
    }

    fun installBlocked() {
        _state.value = UpdateFlowState.Error(UpdateError.InstallBlocked)
    }

    // Pass-throughs the composable uses to build + launch the install; the
    // Activity context lives there, not here.
    fun canInstall(): Boolean = updater.canInstall()
    fun unknownSourcesIntent(): Intent = updater.unknownSourcesIntent()
    fun installIntent(file: File): Intent = updater.installIntent(file)

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    ?: error("No Application in CreationExtras.")
                UpdateViewModel(app, AppContainer.from(app).appUpdater)
            }
        }
    }
}
