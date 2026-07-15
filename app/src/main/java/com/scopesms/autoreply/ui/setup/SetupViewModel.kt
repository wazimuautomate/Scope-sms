package com.scopesms.autoreply.ui.setup

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.scopesms.autoreply.data.system.BatteryOptimizationManager
import com.scopesms.autoreply.di.AppContainer
import com.scopesms.autoreply.domain.permissions.AppPermission
import com.scopesms.autoreply.domain.sim.SimSelection
import com.scopesms.autoreply.telephony.SimInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * State for the Phase 1 setup screen.
 *
 * @param permissions grant state per permission, in ask order.
 * @param sims active SIMs. Empty until READ_PHONE_STATE is granted.
 * @param simSelection the agent's persisted choice.
 * @param batteryExempt live exemption state; null while unknown.
 */
data class SetupUiState(
    val permissions: List<PermissionStatus> = emptyList(),
    val sims: List<SimInfo> = emptyList(),
    val simSelection: SimSelection = SimSelection.DEFAULT,
    val batteryExempt: Boolean? = null,
) {
    /** True once every non-optional permission is granted. */
    val readyToIngest: Boolean
        get() = permissions.filterNot { it.permission.isOptional }.all { it.granted }

    val missingRequired: List<AppPermission>
        get() = permissions.filter { !it.granted && !it.permission.isOptional }.map { it.permission }
}

data class PermissionStatus(val permission: AppPermission, val granted: Boolean)

/**
 * Drives the setup screen: permissions, SIM picker, battery exemption.
 *
 * Phase 7 owns the real onboarding/settings design and is expected to replace
 * this screen. It should keep this ViewModel's shape or fold it into its own —
 * the logic here (live permission re-reads, SIM list refresh on grant, persisting
 * the choice) is what Phase 1's exit criteria are proven against.
 */
class SetupViewModel(
    application: Application,
    private val container: AppContainer,
    /** Injected so tests aren't pinned to the JVM's own SDK level. */
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(SetupUiState())
    val uiState: StateFlow<SetupUiState> = _uiState.asStateFlow()

    /**
     * Exposed so the screen can build the exemption intents. Deliberately not
     * wrapped in a `requestExemption()` method here: launching an Activity needs
     * an Activity context and its `ActivityNotFoundException` fallback is a UI
     * concern, so the ViewModel would only be laundering a Context it shouldn't
     * hold.
     */
    val batteryOptimization: BatteryOptimizationManager get() = container.batteryOptimization

    init {
        viewModelScope.launch {
            container.settings.simSelection.collect { selection ->
                _uiState.update { it.copy(simSelection = selection) }
            }
        }
        refresh()
    }

    /**
     * Re-reads everything the system can change behind our back.
     *
     * Called on every resume, not just once. Permissions and the battery
     * exemption can both be revoked in system settings while the app sits in
     * the background, and a SIM can be swapped — a screen that read them once at
     * construction would keep showing a stale green tick for state that no
     * longer holds.
     */
    fun refresh() {
        val context = getApplication<Application>()
        _uiState.update {
            it.copy(
                permissions = AppPermission.requestable(sdkInt).map { permission ->
                    PermissionStatus(permission, context.isGranted(permission))
                },
                // Guarded: without READ_PHONE_STATE this is empty anyway, but
                // asking wastes a binder call on every resume during onboarding.
                sims = if (context.isGranted(AppPermission.READ_PHONE_STATE)) {
                    container.simReader.activeSims()
                } else {
                    emptyList()
                },
                batteryExempt = container.batteryOptimization.isExempt(),
            )
        }
    }

    fun selectSims(selection: SimSelection) {
        viewModelScope.launch { container.settings.setSimSelection(selection) }
    }

    /** The permissions to hand to the system's request dialog. */
    fun permissionsToRequest(): Array<String> =
        AppPermission.requestable(sdkInt)
            .filterNot { getApplication<Application>().isGranted(it) }
            .map { it.id }
            .toTypedArray()

    private fun Context.isGranted(permission: AppPermission): Boolean =
        ContextCompat.checkSelfPermission(this, permission.id) == PackageManager.PERMISSION_GRANTED

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    ?: error("No Application in CreationExtras.")
                SetupViewModel(app, AppContainer.from(app))
            }
        }
    }
}
