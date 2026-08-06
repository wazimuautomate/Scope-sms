package com.tricreta.scopesms.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.tricreta.scopesms.BuildConfig
import com.tricreta.scopesms.di.AppContainer
import com.tricreta.scopesms.domain.notifications.NotificationToggles
import com.tricreta.scopesms.domain.reliability.OemAutostartGuide
import com.tricreta.scopesms.domain.reliability.OemGuidance
import com.tricreta.scopesms.domain.settings.ThemePreference
import com.tricreta.scopesms.domain.sim.SimSelection
import com.tricreta.scopesms.network.GatewayCredentials
import com.tricreta.scopesms.network.SendOutcome
import com.tricreta.scopesms.telephony.SimInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The client's default sender ID — most agents send under SKYSCOPE_. */
const val DEFAULT_SENDER_ID = "SKYSCOPE_"

/** Result of the "send a test SMS" action. */
sealed interface TestSendState {
    data object Idle : TestSendState
    data object Sending : TestSendState
    data class Success(val messageId: String) : TestSendState

    /** @param reason `SendFailure.description` — already written for the agent. */
    data class Failure(val reason: String) : TestSendState
}

data class SettingsUiState(
    val sims: List<SimInfo> = emptyList(),
    val simSelection: SimSelection = SimSelection.DEFAULT,
    // The two reply flows moved here from Home at the client's request — Home now
    // shows the latest replies instead. These are the agent's throttle on
    // sender-ID ban risk.
    val toggles: NotificationToggles = NotificationToggles.DEFAULT,
    val themePreference: ThemePreference = ThemePreference.DEFAULT,
    val batteryExempt: Boolean = true,
    val gatewayConfigured: Boolean = false,
    // Extra sender addresses whitelisted to be read as M-Pesa confirmations,
    // beyond the official shortcode — e.g. the agent's own SKYSCOPE_ number
    // when it resells a service that texts the same till-confirmation format.
    val trustedSenders: Set<String> = emptySet(),
    val trustedSenderInput: String = "",
    val senderId: String = "",
    val apiKeyInput: String = "",
    // The client's default. Most agents send under SKYSCOPE_, so pre-fill it —
    // they can still overwrite it. Applied only until a stored value loads.
    val senderIdInput: String = DEFAULT_SENDER_ID,
    val saveFailed: Boolean = false,
    val testSend: TestSendState = TestSendState.Idle,
    val oemGuidance: OemGuidance? = null,
    val versionName: String = BuildConfig.VERSION_NAME,
    val versionCode: Int = BuildConfig.VERSION_CODE,
) {
    /**
     * The key, masked. Never the real thing — a screenshot of Settings is the
     * most likely way it leaks, and the agent only needs to know one is stored.
     */
    val maskedApiKey: String get() = if (gatewayConfigured) "••••••••••••" else ""

    val canSaveGateway: Boolean
        get() = apiKeyInput.isNotBlank() && senderIdInput.isNotBlank()

    val canTestSend: Boolean get() = gatewayConfigured && testSend !is TestSendState.Sending

    /** Blank, or already whitelisted (case-insensitively) — nothing to add. */
    val canAddTrustedSender: Boolean
        get() = trustedSenderInput.isNotBlank() &&
            trustedSenders.none { it.equals(trustedSenderInput.trim(), ignoreCase = true) }
}

/** Drives Settings: SIM choice, gateway credentials, battery, OEM help, version. */
class SettingsViewModel(
    application: Application,
    private val container: AppContainer,
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    val batteryOptimization get() = container.batteryOptimization
    val oemSettingsLauncher get() = container.oemSettingsLauncher

    init {
        viewModelScope.launch {
            container.settings.simSelection.collect { selection ->
                _uiState.update { it.copy(simSelection = selection) }
            }
        }
        viewModelScope.launch {
            container.gatewayCredentials.isConfigured.collect { configured ->
                _uiState.update { it.copy(gatewayConfigured = configured) }
            }
        }
        viewModelScope.launch {
            container.gatewayCredentials.senderId.collect { id ->
                _uiState.update { state ->
                    state.copy(
                        senderId = id.orEmpty(),
                        // A stored ID wins over the default prefill; otherwise keep
                        // whatever the agent has typed (or the SKYSCOPE_ default).
                        senderIdInput = id?.takeIf { it.isNotBlank() }
                            ?: state.senderIdInput,
                    )
                }
            }
        }
        viewModelScope.launch {
            container.settings.notificationToggles.collect { toggles ->
                _uiState.update { it.copy(toggles = toggles) }
            }
        }
        viewModelScope.launch {
            container.settings.themePreference.collect { preference ->
                _uiState.update { it.copy(themePreference = preference) }
            }
        }
        viewModelScope.launch {
            container.settings.trustedSenders.collect { senders ->
                _uiState.update { it.copy(trustedSenders = senders) }
            }
        }
        refresh()
    }

    fun setUnmatchedEnabled(enabled: Boolean) {
        viewModelScope.launch { container.settings.setUnmatchedReplyEnabled(enabled) }
    }

    fun setMatchedEnabled(enabled: Boolean) {
        viewModelScope.launch { container.settings.setMatchedReplyEnabled(enabled) }
    }

    fun setOffWindowEnabled(enabled: Boolean) {
        viewModelScope.launch { container.settings.setOffWindowReplyEnabled(enabled) }
    }

    fun setThemePreference(preference: ThemePreference) {
        viewModelScope.launch { container.settings.setThemePreference(preference) }
    }

    /** Re-reads what system UI can change behind our back. */
    fun refresh() {
        _uiState.update {
            it.copy(
                sims = container.simReader.activeSims(),
                batteryExempt = container.batteryOptimization.isExempt(),
                oemGuidance = OemAutostartGuide.guidanceFor(
                    OemAutostartGuide.detect(
                        brand = android.os.Build.BRAND.orEmpty(),
                        manufacturer = android.os.Build.MANUFACTURER.orEmpty(),
                    ),
                ),
            )
        }
    }

    fun selectSims(selection: SimSelection) {
        viewModelScope.launch { container.settings.setSimSelection(selection) }
    }

    fun updateTrustedSenderInput(value: String) {
        _uiState.update { it.copy(trustedSenderInput = value) }
    }

    /** Adds the typed sender to the whitelist and clears the input. */
    fun addTrustedSender() {
        val state = _uiState.value
        if (!state.canAddTrustedSender) return

        val updated = state.trustedSenders + state.trustedSenderInput.trim()
        _uiState.update { it.copy(trustedSenders = updated, trustedSenderInput = "") }
        viewModelScope.launch { container.settings.setTrustedSenders(updated) }
    }

    fun removeTrustedSender(sender: String) {
        val updated = _uiState.value.trustedSenders - sender
        _uiState.update { it.copy(trustedSenders = updated) }
        viewModelScope.launch { container.settings.setTrustedSenders(updated) }
    }

    fun updateApiKeyInput(value: String) {
        _uiState.update { it.copy(apiKeyInput = value, saveFailed = false) }
    }

    fun updateSenderIdInput(value: String) {
        _uiState.update { it.copy(senderIdInput = value, saveFailed = false) }
    }

    fun saveGateway() {
        val state = _uiState.value
        if (!state.canSaveGateway) return

        viewModelScope.launch {
            val saved = container.gatewayCredentials.save(
                GatewayCredentials(
                    apiKey = state.apiKeyInput.trim(),
                    senderId = state.senderIdInput.trim(),
                ),
            )
            _uiState.update {
                it.copy(
                    // Clear the key from UI state on success. It stays in the
                    // encrypted store; keeping a copy in a ViewModel that
                    // survives backgrounding just widens the blast radius.
                    apiKeyInput = if (saved) "" else it.apiKeyInput,
                    saveFailed = !saved,
                    testSend = TestSendState.Idle,
                )
            }
        }
    }

    fun clearGateway() {
        viewModelScope.launch {
            container.gatewayCredentials.clear()
            _uiState.update {
                it.copy(
                    apiKeyInput = "",
                    senderIdInput = DEFAULT_SENDER_ID,
                    testSend = TestSendState.Idle,
                )
            }
        }
    }

    /**
     * Wipes the app back to a first-install state and restarts it — the agent's
     * "things got messy, start over" button. See
     * [com.tricreta.scopesms.data.system.AppReset]. The scary confirmation is in
     * the UI; by the time this runs the agent has agreed to lose everything.
     */
    fun resetApp() {
        // On the app scope, not viewModelScope: the wipe kills the process, which
        // must not race this ViewModel's own teardown.
        container.applicationScope.launch { container.appReset.wipeAndRestart() }
    }

    /**
     * Sends a real SMS through the real gateway to [phone].
     *
     * Deliberately not a mock or a dry-run. The failures this exists to catch —
     * an unregistered sender ID, a typo'd key, an empty balance — only happen at
     * the gateway, and BUILD-PLAN Phase 7 asks for this before onboarding
     * finishes precisely so the agent finds out now rather than when a customer
     * is waiting. It bypasses the queue because the agent is standing here
     * watching for the answer.
     *
     * A single plain "it works" line. The earlier "send a real price-list /
     * confirmation sample" variants were removed at the client's request — they
     * rendered from the live templates and reliably tripped the gateway, and the
     * agent already previews those exact messages on the Messages screen.
     */
    fun sendTest(phone: String) {
        if (!_uiState.value.canTestSend) return

        _uiState.update { it.copy(testSend = TestSendState.Sending) }
        viewModelScope.launch {
            val outcome = container.gateway.sendSms(phone = phone.trim(), message = TEST_MESSAGE)
            _uiState.update {
                it.copy(
                    testSend = when (outcome) {
                        is SendOutcome.Sent -> TestSendState.Success(outcome.messageId)
                        is SendOutcome.Failed -> TestSendState.Failure(outcome.reason.description)
                    },
                )
            }
        }
    }

    fun dismissTestResult() {
        _uiState.update { it.copy(testSend = TestSendState.Idle) }
    }

    companion object {
        private const val TEST_MESSAGE =
            "Scope SMS test message. Your gateway is set up correctly."

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    ?: error("No Application in CreationExtras.")
                SettingsViewModel(app, AppContainer.from(app))
            }
        }
    }
}
