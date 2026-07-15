package com.scopesms.autoreply.ui.rules

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.scopesms.autoreply.di.AppContainer
import com.scopesms.autoreply.domain.money.KshAmount
import com.scopesms.autoreply.domain.rules.PricingRule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What's wrong with the rule the agent is typing, if anything. */
enum class RuleInputError {
    /** Blank, negative, or not digits. */
    AMOUNT_INVALID,

    /**
     * They typed `50.50`.
     *
     * Bundle prices are whole shillings — see [KshAmount.parseWholeShillings].
     * Its own error, not folded into [AMOUNT_INVALID], because "that isn't a
     * number" and "we don't do cents here" send the agent looking in completely
     * different places.
     */
    AMOUNT_NOT_WHOLE,

    /** Ksh 0 buys nothing; a rule for it would confirm purchases of nothing. */
    AMOUNT_ZERO,

    /** No bundle description — `{package}` would render empty in the reply. */
    DESCRIPTION_BLANK,

    /**
     * Another active rule already has this price.
     *
     * Not fatal — the engine resolves it deterministically (newest wins) — but
     * the agent almost never means it, so the editor blocks it and says so.
     */
    AMOUNT_DUPLICATE,
}

data class RulesUiState(
    val rules: List<PricingRule> = emptyList(),
    val duplicateAmounts: Set<KshAmount> = emptySet(),
    val editing: RuleDraft? = null,
    val loaded: Boolean = false,
) {
    val activeCount: Int get() = rules.count { it.isActive }
}

/**
 * A rule being typed. Amount is a String because half-typed input isn't a number.
 */
data class RuleDraft(
    val id: Long = 0,
    val amountText: String = "",
    val description: String = "",
    val isActive: Boolean = true,
    val error: RuleInputError? = null,
) {
    val isNew: Boolean get() = id == 0L
}

/** Drives the Rules screen: the price list the whole app matches against. */
class RulesViewModel(
    application: Application,
    private val container: AppContainer,
) : AndroidViewModel(application) {

    private val editing = MutableStateFlow<RuleDraft?>(null)

    val uiState: StateFlow<RulesUiState> = kotlinx.coroutines.flow.combine(
        container.ruleCache.snapshots,
        editing.asStateFlow(),
    ) { snapshot, draft ->
        RulesUiState(
            rules = snapshot?.allRules.orEmpty(),
            duplicateAmounts = snapshot?.duplicateAmounts.orEmpty(),
            editing = draft,
            loaded = snapshot != null,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), RulesUiState())

    fun startAdding() {
        editing.value = RuleDraft()
    }

    fun startEditing(rule: PricingRule) {
        editing.value = RuleDraft(
            id = rule.id,
            // Whole shillings, never "50.00" — parseWholeShillings would reject
            // its own output otherwise, and the agent would have to delete the
            // decimals to save an edit they didn't make.
            amountText = rule.amount.shillings.toString(),
            description = rule.bundleDescription,
            isActive = rule.isActive,
        )
    }

    fun cancelEditing() {
        editing.value = null
    }

    fun updateDraft(amountText: String? = null, description: String? = null) {
        editing.update { draft ->
            draft?.copy(
                amountText = amountText ?: draft.amountText,
                description = description ?: draft.description,
                // Clear as they type: an error that outlives the thing it was
                // complaining about is just noise.
                error = null,
            )
        }
    }

    /** Validates and saves. No-op with an error set if the draft is bad. */
    fun save() {
        val draft = editing.value ?: return
        val amount = KshAmount.parseWholeShillings(draft.amountText)

        val error = when {
            draft.amountText.isBlank() -> RuleInputError.AMOUNT_INVALID
            // Order matters: a decimal is more specific than "invalid", and it's
            // the mistake an agent is far more likely to make.
            draft.amountText.contains('.') || draft.amountText.contains(',') ->
                RuleInputError.AMOUNT_NOT_WHOLE
            amount == null -> RuleInputError.AMOUNT_INVALID
            amount.cents == 0L -> RuleInputError.AMOUNT_ZERO
            draft.description.isBlank() -> RuleInputError.DESCRIPTION_BLANK
            clashesWithAnother(draft, amount) -> RuleInputError.AMOUNT_DUPLICATE
            else -> null
        }

        if (error != null || amount == null) {
            editing.update { it?.copy(error = error) }
            return
        }

        viewModelScope.launch {
            container.pricingRuleRepository.upsert(
                PricingRule(
                    id = draft.id,
                    amount = amount,
                    bundleDescription = draft.description.trim(),
                    isActive = draft.isActive,
                ),
            )
            editing.value = null
        }
    }

    /**
     * True if another *active* rule already quotes this price.
     *
     * Only active ones: a paused bundle isn't quoted to anyone, so re-using its
     * price is exactly what an agent replacing it would do.
     */
    private fun clashesWithAnother(draft: RuleDraft, amount: KshAmount): Boolean =
        draft.isActive &&
            uiState.value.rules.any { it.isActive && it.amount == amount && it.id != draft.id }

    fun setActive(rule: PricingRule, isActive: Boolean) {
        viewModelScope.launch { container.pricingRuleRepository.setActive(rule.id, isActive) }
    }

    fun delete(rule: PricingRule) {
        viewModelScope.launch { container.pricingRuleRepository.delete(rule.id) }
    }

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    ?: error("No Application in CreationExtras.")
                RulesViewModel(app, AppContainer.from(app))
            }
        }
    }
}

/** `MutableStateFlow.update` for a nullable value, without the null dance. */
private inline fun <T> MutableStateFlow<T>.update(transform: (T) -> T) {
    value = transform(value)
}
