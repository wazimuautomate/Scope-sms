package com.tricreta.scopesms.domain.rules

import com.tricreta.scopesms.domain.money.KshAmount
import kotlinx.coroutines.flow.Flow

/**
 * Durable storage for the agent's price list. Implemented by
 * `data/rules/RoomPricingRuleRepository`.
 *
 * The interface lives in `domain/` so this package keeps no dependency on Room
 * (`domain/README.md`), which is what lets the rules engine be tested on the
 * JVM in milliseconds — the primary safety net given there is no local build
 * (CLAUDE.md constraint 8).
 *
 * Nothing on the SMS hot path calls this. The receiver reads [RuleCache]; this
 * is for the UI's edits and for the container's initial load.
 */
interface PricingRuleRepository {

    /**
     * Emits the full rule list, and re-emits on every change.
     *
     * The container collects this into [RuleCache]. Room backs it with its own
     * invalidation tracker, so writes made anywhere — including by code written
     * in later phases — land here without that code knowing the cache exists.
     */
    fun observeAll(): Flow<List<PricingRule>>

    suspend fun getAll(): List<PricingRule>

    /** Inserts when [PricingRule.id] is 0, updates otherwise. Returns the row id. */
    suspend fun upsert(rule: PricingRule): Long

    suspend fun delete(id: Long)

    suspend fun setActive(id: Long, isActive: Boolean)

    /**
     * Active rules already charging [amount].
     *
     * For the rules screen to warn before saving a second bundle at a price
     * that already has one. Matching itself never calls this — it reads the
     * cache. See [RuleSnapshot.duplicateAmounts] for how a duplicate that gets
     * saved anyway is resolved.
     */
    suspend fun findActiveByAmount(amount: KshAmount): List<PricingRule>
}
