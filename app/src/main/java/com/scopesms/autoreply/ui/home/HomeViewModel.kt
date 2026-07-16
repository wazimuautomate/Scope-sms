package com.scopesms.autoreply.ui.home

import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.scopesms.autoreply.di.AppContainer
import com.scopesms.autoreply.domain.log.DashboardStats
import com.scopesms.autoreply.domain.notifications.NotificationToggles
import com.scopesms.autoreply.domain.permissions.AppPermission
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Everything wrong with the app's setup, in the order the agent should fix it.
 *
 * Each of these is a way the app can be silently doing nothing — which is the
 * failure mode CLAUDE.md constraint 9 and Phase 9 both single out, because a
 * broken Scope SMS looks exactly like a quiet day with no customers. Home says
 * so out loud rather than waiting to be asked.
 */
enum class SetupWarning {
    /** RECEIVE_SMS/READ_SMS revoked. Nothing is being read at all. */
    MISSING_PERMISSIONS,

    /** No bundle prices. Every payment classifies as NoRulesConfigured; nothing sends. */
    NO_RULES,

    /** No API key/sender ID. Replies are decided, then fail at the gateway. */
    NO_GATEWAY,

    /** Both flows off. A legitimate choice, but worth stating. */
    BOTH_TOGGLES_OFF,

    /** The OEM can kill the app in the background at any time. */
    NOT_BATTERY_EXEMPT,
}

data class HomeUiState(
    val stats: DashboardStats = DashboardStats.EMPTY,
    val toggles: NotificationToggles = NotificationToggles.DEFAULT,
    val warnings: List<SetupWarning> = emptyList(),
) {
    /**
     * True when the app can actually act on a payment: it can read SMS, it has
     * prices, it has credentials, and at least one flow is on.
     *
     * The battery exemption is deliberately excluded — its absence is a risk,
     * not a stoppage, and calling the app "not monitoring" because of it would
     * cry wolf on a device that is working fine.
     */
    val isMonitoring: Boolean
        get() = warnings.none {
            it == SetupWarning.MISSING_PERMISSIONS ||
                it == SetupWarning.NO_RULES ||
                it == SetupWarning.NO_GATEWAY ||
                it == SetupWarning.BOTH_TOGGLES_OFF
        }
}

/** Drives Home: the status banner, the two toggles, and today's tiles. */
class HomeViewModel(
    application: Application,
    private val container: AppContainer,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) : AndroidViewModel(application) {

    /**
     * Re-read on resume rather than observed: permissions and the battery
     * exemption change in system UI, which the app has no callback for.
     */
    private val systemState = MutableStateFlow(SystemState())

    private data class SystemState(
        val permissionsGranted: Boolean = true,
        val batteryExempt: Boolean = true,
    )

    val uiState: StateFlow<HomeUiState> = combine(
        container.activityLog.statsForToday(),
        container.settings.notificationToggles,
        container.ruleCache.snapshots,
        container.gatewayCredentials.isConfigured,
        systemState,
    ) { stats, toggles, rules, gatewayConfigured, system ->
        HomeUiState(
            stats = stats,
            toggles = toggles,
            warnings = buildList {
                if (!system.permissionsGranted) add(SetupWarning.MISSING_PERMISSIONS)
                // rules is null before the cache's first load — not "no rules".
                // Warning then would flash "add your prices" on every cold start.
                if (rules != null && rules.hasNoActiveRules) add(SetupWarning.NO_RULES)
                if (!gatewayConfigured) add(SetupWarning.NO_GATEWAY)
                if (toggles.allDisabled) add(SetupWarning.BOTH_TOGGLES_OFF)
                if (!system.batteryExempt) add(SetupWarning.NOT_BATTERY_EXEMPT)
            },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), HomeUiState())

    /** Duplicate bundle prices, for the Rules screen's warning. */
    val duplicateAmountCount: StateFlow<Int> = container.ruleCache.snapshots
        .map { it?.duplicateAmounts?.size ?: 0 }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), 0)

    init {
        refresh()
    }

    /** Call on every resume — see [systemState]. */
    fun refresh() {
        val context = getApplication<Application>()
        val granted = AppPermission.requestable(sdkInt)
            .filterNot { it.isOptional }
            .all {
                ContextCompat.checkSelfPermission(context, it.id) == PackageManager.PERMISSION_GRANTED
            }

        systemState.update {
            it.copy(
                permissionsGranted = granted,
                batteryExempt = container.batteryOptimization.isExempt(),
            )
        }
    }

    fun setUnmatchedEnabled(enabled: Boolean) {
        viewModelScope.launch { container.settings.setUnmatchedReplyEnabled(enabled) }
    }

    fun setMatchedEnabled(enabled: Boolean) {
        viewModelScope.launch { container.settings.setMatchedReplyEnabled(enabled) }
    }

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    ?: error("No Application in CreationExtras.")
                HomeViewModel(app, AppContainer.from(app))
            }
        }
    }
}
