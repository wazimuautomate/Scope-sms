package com.tricreta.scopesms.ui.home

import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.tricreta.scopesms.di.AppContainer
import com.tricreta.scopesms.domain.log.ActivityRecord
import com.tricreta.scopesms.domain.log.DashboardStats
import com.tricreta.scopesms.domain.notifications.NotificationToggles
import com.tricreta.scopesms.domain.permissions.AppPermission
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

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

    /**
     * Whether the CURRENTLY ACTIVE gateway is configured — switches which
     * provider's `isConfigured` it's watching whenever the agent changes the
     * active gateway in Settings. `flatMapLatest`, not a second `combine`
     * argument, because `activeGatewayProvider` is itself a Flow the
     * downstream check depends on, not a value available up front.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val activeGatewayConfigured: Flow<Boolean> = container.settings.activeGatewayProvider
        .flatMapLatest { provider -> container.gatewayCredentials.isConfigured(provider) }

    val uiState: StateFlow<HomeUiState> = combine(
        // Wrapped, not called directly: statsForToday() captures the day boundary
        // at *call* time and bakes it into the Room query, and it is a fun rather
        // than a val precisely so re-collecting picks up the new day. Calling it
        // in this initializer would bind it once for the ViewModel's whole life —
        // so an app left open overnight would still show yesterday's tiles at 9am
        // and never correct itself. `flow { emitAll(...) }` re-invokes it on each
        // subscription, which is what WhileSubscribed gives us on every reopen.
        flow { emitAll(container.activityLog.statsForToday()) },
        container.settings.notificationToggles,
        container.ruleCache.snapshots,
        activeGatewayConfigured,
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

    /**
     * The three most recent processed payments, for Home's "Latest replies" list
     * — the space the two toggles used to occupy before they moved to Settings.
     *
     * `recent` is already newest-first and capped in the DAO; three is all Home
     * shows, and the agent taps through to the full Activity log for the rest.
     */
    val recentReplies: StateFlow<List<ActivityRecord>> = container.activityLog.recent
        .map { it.take(RECENT_REPLIES) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

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

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L
        private const val RECENT_REPLIES = 3

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    ?: error("No Application in CreationExtras.")
                HomeViewModel(app, AppContainer.from(app))
            }
        }
    }
}
