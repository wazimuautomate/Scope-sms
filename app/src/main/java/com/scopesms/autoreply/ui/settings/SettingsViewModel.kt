package com.scopesms.autoreply.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.scopesms.autoreply.BuildConfig
import com.scopesms.autoreply.di.AppContainer
import com.scopesms.autoreply.domain.reliability.OemAutostartGuide
import com.scopesms.autoreply.domain.reliability.OemGuidance
import com.scopesms.autoreply.domain.money.KshAmount
import com.scopesms.autoreply.domain.rules.PricingRule
import com.scopesms.autoreply.domain.sim.SimSelection
import com.scopesms.autoreply.domain.templates.TemplateEngine
import com.scopesms.autoreply.domain.templates.TemplateType
import com.scopesms.autoreply.domain.update.UpdateStatus
import com.scopesms.autoreply.network.GatewayCredentials
import com.scopesms.autoreply.network.SendOutcome
import com.scopesms.autoreply.telephony.SimInfo
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
    val batteryExempt: Boolean = true,
    val gatewayConfigured: Boolean = false,
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
    val update: UpdateStatus? = null,
    val checkingForUpdate: Boolean = false,
) {
    /**
     * The key, masked. Never the real thing — a screenshot of Settings is the
     * most likely way it leaks, and the agent only needs to know one is stored.
     */
    val maskedApiKey: String get() = if (gatewayConfigured) "••••••••••••" else ""

    val canSaveGateway: Boolean
        get() = apiKeyInput.isNotBlank() && senderIdInput.isNotBlank()

    val canTestSend: Boolean get() = gatewayConfigured && testSend !is TestSendState.Sending
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
        refresh()
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

    /** Which sample the test-send button should use. */
    enum class TestKind { PLAIN, MATCHED, UNMATCHED }

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
     * [kind] chooses the body: a plain "it works" line, or a rendered sample of
     * the actual matched/unmatched reply so the agent can see on their own phone
     * exactly what a customer would receive — the client asked for both. The
     * samples render through the *real* template engine and the agent's *real*
     * templates and price list, so a wording or segment surprise shows up here.
     */
    fun sendTest(phone: String, kind: TestKind = TestKind.PLAIN) {
        if (!_uiState.value.canTestSend) return

        _uiState.update { it.copy(testSend = TestSendState.Sending) }
        viewModelScope.launch {
            val message = sampleBody(kind)
            val outcome = container.gateway.sendSms(phone = phone.trim(), message = message)
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

    /** Renders the sample body for [kind] from the live templates and prices. */
    private suspend fun sampleBody(kind: TestKind): String {
        if (kind == TestKind.PLAIN) return TEST_MESSAGE

        val templates = container.templateCache.currentOrNull() ?: return TEST_MESSAGE
        val activeRules = container.ruleCache.currentOrNull()?.activeRules.orEmpty()
        val sampleAmount = activeRules.firstOrNull()?.amount ?: KshAmount.ofShillings(SAMPLE_SHILLINGS)

        val values = when (kind) {
            TestKind.MATCHED -> TemplateEngine.matchedValues(
                name = SAMPLE_NAME,
                amount = sampleAmount,
                phone = SAMPLE_PHONE,
                matchedRule = activeRules.firstOrNull() ?: SAMPLE_RULE,
            )
            TestKind.UNMATCHED -> TemplateEngine.unmatchedValues(
                name = SAMPLE_NAME,
                amount = KshAmount.ofShillings(SAMPLE_ODD_SHILLINGS),
                phone = SAMPLE_PHONE,
                activeRules = activeRules,
            )
            TestKind.PLAIN -> return TEST_MESSAGE
        }

        val type = if (kind == TestKind.MATCHED) TemplateType.MATCHED else TemplateType.UNMATCHED
        return TemplateEngine.render(templates.forType(type), values)
    }

    fun dismissTestResult() {
        _uiState.update { it.copy(testSend = TestSendState.Idle) }
    }

    /**
     * Phase 11's update check. On demand only — never on a timer.
     *
     * The agent's phone is on a metered connection they pay for by the megabyte,
     * and an app that quietly polls GitHub in the background to tell them
     * nothing has changed is spending their money to do it.
     */
    fun checkForUpdate() {
        if (_uiState.value.checkingForUpdate) return
        _uiState.update { it.copy(checkingForUpdate = true) }
        viewModelScope.launch {
            val status = container.updateChecker.check()
            _uiState.update { it.copy(update = status, checkingForUpdate = false) }
        }
    }

    companion object {
        private const val TEST_MESSAGE =
            "Scope SMS test message. Your gateway is set up correctly."

        // Stand-ins for the matched/unmatched sample sends, mirroring the
        // Templates preview so the two agree.
        private const val SAMPLE_NAME = "John Kamau"
        private const val SAMPLE_PHONE = "0712345678"
        private const val SAMPLE_SHILLINGS = 50L
        private const val SAMPLE_ODD_SHILLINGS = 35L
        private val SAMPLE_RULE = PricingRule(
            id = 0,
            amount = KshAmount.ofShillings(SAMPLE_SHILLINGS),
            bundleDescription = "2GB Weekly",
        )

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    ?: error("No Application in CreationExtras.")
                SettingsViewModel(app, AppContainer.from(app))
            }
        }
    }
}
