package com.tricreta.scopesms.ui.templates

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.tricreta.scopesms.di.AppContainer
import com.tricreta.scopesms.domain.money.KshAmount
import com.tricreta.scopesms.domain.rules.PricingRule
import com.tricreta.scopesms.domain.rules.PurchaseWindow
import com.tricreta.scopesms.domain.templates.DefaultTemplates
import com.tricreta.scopesms.domain.templates.PromotionalToneChecker
import com.tricreta.scopesms.domain.templates.SmsLength
import com.tricreta.scopesms.domain.templates.SmsSegments
import com.tricreta.scopesms.domain.templates.TemplateEngine
import com.tricreta.scopesms.domain.templates.TemplateType
import com.tricreta.scopesms.domain.templates.TemplateValidation
import com.tricreta.scopesms.domain.templates.TemplateVariable
import com.tricreta.scopesms.domain.templates.ToneCheckResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One flow's editor state. */
data class TemplateEditorState(
    val type: TemplateType,
    val body: String = "",
    val savedBody: String = "",
    val preview: String = "",
    val length: SmsLength = SmsSegments.measure(""),
    val validation: TemplateValidation = TemplateValidation(emptyList(), emptyList()),
    val toneCheck: ToneCheckResult = ToneCheckResult.CLEAN,
    val isDefault: Boolean = true,
) {
    val hasUnsavedChanges: Boolean get() = body != savedBody
    val allowedVariables: List<TemplateVariable> get() = TemplateVariable.allowedFor(type).toList()
    val canSave: Boolean get() = validation.isValid && body.isNotBlank() && hasUnsavedChanges
}

data class TemplatesUiState(
    val unmatched: TemplateEditorState = TemplateEditorState(TemplateType.UNMATCHED),
    val matched: TemplateEditorState = TemplateEditorState(TemplateType.MATCHED),
    val offWindow: TemplateEditorState = TemplateEditorState(TemplateType.OFF_WINDOW),
) {
    fun forType(type: TemplateType): TemplateEditorState = when (type) {
        TemplateType.UNMATCHED -> unmatched
        TemplateType.MATCHED -> matched
        TemplateType.OFF_WINDOW -> offWindow
    }
}

/**
 * Drives the Templates screen.
 *
 * ## The preview is the point
 * Whatever the agent writes here is sent to a paying customer under their own
 * sender ID, with no draft step and no human in the loop. So the preview renders
 * through the *same* [TemplateEngine] the receiver uses — not a lookalike — and
 * the segment count comes from the same [SmsSegments] that decides what they pay
 * for. If the preview and the real reply could ever disagree, the preview would
 * be worse than useless.
 */
class TemplatesViewModel(
    application: Application,
    private val container: AppContainer,
) : AndroidViewModel(application) {

    /** Null = "show what's stored"; non-null = the agent has started typing. */
    private val drafts = MutableStateFlow<Map<TemplateType, String>>(emptyMap())

    val uiState: StateFlow<TemplatesUiState> = combine(
        container.templateCache.snapshots,
        container.ruleCache.snapshots,
        drafts,
    ) { templates, rules, edits ->
        val activeRules = rules?.activeRules.orEmpty()
        TemplatesUiState(
            unmatched = editorFor(TemplateType.UNMATCHED, templates, edits, activeRules),
            matched = editorFor(TemplateType.MATCHED, templates, edits, activeRules),
            offWindow = editorFor(TemplateType.OFF_WINDOW, templates, edits, activeRules),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), TemplatesUiState())

    private fun editorFor(
        type: TemplateType,
        templates: com.tricreta.scopesms.domain.templates.TemplateSnapshot?,
        edits: Map<TemplateType, String>,
        activeRules: List<PricingRule>,
    ): TemplateEditorState {
        val stored = templates?.forType(type)
        val savedBody = stored?.body ?: DefaultTemplates.bodyFor(type)
        val body = edits[type] ?: savedBody
        val rendered = TemplateEngine.render(body, sampleValues(type, activeRules))

        return TemplateEditorState(
            type = type,
            body = body,
            savedBody = savedBody,
            preview = rendered,
            length = SmsSegments.measure(rendered),
            validation = TemplateEngine.validate(body, type),
            // Checked against the *rendered* preview, not the raw body: a real
            // bundle description substituted via {bundle_list}/{package} can
            // itself be promotional-sounding even when the template text
            // around it isn't, and the rendered preview is what Safaricom's
            // filter actually sees.
            toneCheck = PromotionalToneChecker.check(rendered),
            isDefault = stored?.isDefault ?: true,
        )
    }

    /**
     * Realistic stand-ins for a preview.
     *
     * The agent's **real** price list is used for `{bundle_list}` rather than a
     * made-up one — that variable is the whole reason the unmatched reply exists,
     * and its length is what pushes the message onto a second segment they pay
     * for. A fake two-line list would preview a message that costs less than the
     * one that actually goes out.
     */
    private fun sampleValues(
        type: TemplateType,
        activeRules: List<PricingRule>,
    ): Map<TemplateVariable, String?> = when (type) {
        TemplateType.UNMATCHED -> TemplateEngine.unmatchedValues(
            name = SAMPLE_NAME,
            amount = KshAmount.ofShillings(SAMPLE_ODD_AMOUNT),
            phone = SAMPLE_PHONE,
            activeRules = activeRules,
            mpesaCode = SAMPLE_MPESA_CODE,
        )

        TemplateType.MATCHED -> TemplateEngine.matchedValues(
            name = SAMPLE_NAME,
            amount = activeRules.firstOrNull()?.amount ?: KshAmount.ofShillings(SAMPLE_AMOUNT),
            phone = SAMPLE_PHONE,
            matchedRule = activeRules.firstOrNull() ?: SAMPLE_RULE,
            mpesaCode = SAMPLE_MPESA_CODE,
        )

        // {purchase_window} preview: the agent's real first bundle when one
        // exists (its real window, whatever it is), or a bundle with a
        // deliberately restricted window when there are none yet — a fresh
        // install's bundles are all unrestricted by default, and previewing
        // "12:00 AM to 11:59 PM" would be a confusing stand-in for what this
        // flow is actually about.
        TemplateType.OFF_WINDOW -> TemplateEngine.offWindowValues(
            name = SAMPLE_NAME,
            amount = activeRules.firstOrNull()?.amount ?: KshAmount.ofShillings(SAMPLE_AMOUNT),
            phone = SAMPLE_PHONE,
            matchedRule = activeRules.firstOrNull() ?: SAMPLE_OFF_WINDOW_RULE,
            mpesaCode = SAMPLE_MPESA_CODE,
        )
    }

    fun edit(type: TemplateType, body: String) {
        drafts.value = drafts.value + (type to body)
    }

    fun save(type: TemplateType) {
        val body = drafts.value[type] ?: return
        viewModelScope.launch {
            container.messageTemplateRepository.save(type, body)
            // Drop the draft so the editor follows the cache again — but only if
            // it still holds what we just saved. The agent can keep typing while
            // the Room write is in flight, and dropping unconditionally would
            // silently revert those keystrokes to the saved text in front of them.
            drafts.update { current -> if (current[type] == body) current - type else current }
        }
    }

    fun resetToDefault(type: TemplateType) {
        viewModelScope.launch {
            container.messageTemplateRepository.resetToDefault(type)
            // Unconditional here, unlike save(): reset is an explicit "throw away
            // what's in the editor", so discarding a draft is the intent.
            drafts.update { it - type }
        }
    }

    fun discard(type: TemplateType) {
        drafts.value = drafts.value - type
    }

    /** Inserts a variable at the end of the body — the chip tap. */
    fun appendVariable(type: TemplateType, variable: TemplateVariable) {
        val current = uiState.value.forType(type).body
        val separator = if (current.isEmpty() || current.endsWith(" ")) "" else " "
        edit(type, current + separator + variable.token)
    }

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        private const val SAMPLE_NAME = "John Kamau"
        private const val SAMPLE_PHONE = "0712345678"
        private const val SAMPLE_MPESA_CODE = "QGL7XCP2KD"

        /** An amount that matches nothing — what an unmatched reply is triggered by. */
        private const val SAMPLE_ODD_AMOUNT = 35L
        private const val SAMPLE_AMOUNT = 50L

        private val SAMPLE_RULE = PricingRule(
            id = 0,
            amount = KshAmount.ofShillings(SAMPLE_AMOUNT),
            bundleDescription = "2GB Weekly",
        )

        /** A representative restricted bundle, so the off-window preview is never blank. */
        private val SAMPLE_OFF_WINDOW_RULE = PricingRule(
            id = 0,
            amount = KshAmount.ofShillings(SAMPLE_AMOUNT),
            bundleDescription = "2GB Weekly",
            purchaseWindow = PurchaseWindow(16 * 60, 22 * 60 + 59), // 4:00 PM to 10:59 PM
        )

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    ?: error("No Application in CreationExtras.")
                TemplatesViewModel(app, AppContainer.from(app))
            }
        }
    }
}
