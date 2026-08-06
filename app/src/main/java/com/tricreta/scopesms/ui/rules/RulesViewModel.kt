package com.tricreta.scopesms.ui.rules

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.tricreta.scopesms.di.AppContainer
import com.tricreta.scopesms.domain.money.KshAmount
import com.tricreta.scopesms.domain.rules.BundleCategory
import com.tricreta.scopesms.domain.rules.PriceListCodec
import com.tricreta.scopesms.domain.rules.PricingRule
import com.tricreta.scopesms.domain.rules.PurchaseLimit
import com.tricreta.scopesms.domain.rules.PurchaseWindow
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
    val category: BundleCategory = BundleCategory.DEFAULT,
    val purchaseLimit: PurchaseLimit = PurchaseLimit.DEFAULT,
    val purchaseWindow: PurchaseWindow = PurchaseWindow.DEFAULT,
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
            category = rule.category,
            purchaseLimit = rule.purchaseLimit,
            purchaseWindow = rule.purchaseWindow,
        )
    }

    fun cancelEditing() {
        editing.value = null
    }

    fun updateDraft(
        amountText: String? = null,
        description: String? = null,
        category: BundleCategory? = null,
        purchaseLimit: PurchaseLimit? = null,
        purchaseWindow: PurchaseWindow? = null,
    ) {
        editing.update { draft ->
            draft?.copy(
                amountText = amountText ?: draft.amountText,
                description = description ?: draft.description,
                category = category ?: draft.category,
                purchaseLimit = purchaseLimit ?: draft.purchaseLimit,
                purchaseWindow = purchaseWindow ?: draft.purchaseWindow,
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
            //
            // Only '.' — a thousands separator is not cents. parseWholeShillings
            // strips commas and accepts "1,000", so rejecting it here would block
            // a perfectly good price and tell the agent it had cents in it.
            draft.amountText.contains('.') -> RuleInputError.AMOUNT_NOT_WHOLE
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
                    category = draft.category,
                    purchaseLimit = draft.purchaseLimit,
                    purchaseWindow = draft.purchaseWindow,
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

    // ---- Export / import (share prices between phones) ----------------------

    /**
     * The current price list as a shareable JSON document, or null if there is
     * nothing to export.
     *
     * @param now epoch millis, from the caller so this stays free of a clock.
     */
    fun buildExport(now: Long): String? {
        val rules = uiState.value.rules
        if (rules.isEmpty()) return null
        return PriceListCodec.export(rules, now)
    }

    /** What an import did, for the screen to report. */
    data class ImportSummary(val added: Int, val skippedDuplicates: Int)

    /**
     * Reads [text] and adds its prices, skipping any whose amount already exists.
     *
     * @param onResult called on the main thread with the outcome: the summary on
     *   success, or null if the file wasn't a readable price list.
     */
    fun applyImport(text: String, onResult: (ImportSummary?) -> Unit) {
        when (val result = PriceListCodec.import(text)) {
            is PriceListCodec.ImportResult.Loaded -> viewModelScope.launch {
                // Merge, not replace: importing must never wipe prices the agent
                // already has. Skip any amount that already exists so a re-import
                // is idempotent rather than piling up duplicates.
                // Mutable so a file that lists the same amount twice adds it once.
                val existing = uiState.value.rules.map { it.amount }.toMutableSet()
                var added = 0
                var skipped = 0
                for (row in result.rules) {
                    if (!existing.add(row.amount)) {
                        skipped++
                        continue
                    }
                    container.pricingRuleRepository.upsert(
                        PricingRule(
                            id = 0,
                            amount = row.amount,
                            bundleDescription = row.bundleDescription,
                            isActive = row.isActive,
                            category = row.category,
                            purchaseLimit = row.purchaseLimit,
                            purchaseWindow = row.purchaseWindow,
                        ),
                    )
                    added++
                }
                onResult(ImportSummary(added = added, skippedDuplicates = skipped))
            }

            // Both "not ours" and "too new" collapse to null for the caller; the
            // screen shows a single "couldn't read that file" message either way.
            PriceListCodec.ImportResult.NotAPriceList,
            is PriceListCodec.ImportResult.UnsupportedVersion,
            -> onResult(null)
        }
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
