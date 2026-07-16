package com.tricreta.scopesms.data.rules

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * CRUD for the agent's price list.
 *
 * Nothing here runs on the SMS receive path — that reads
 * [com.tricreta.scopesms.domain.rules.RuleCache]. These are the UI's edits plus
 * the container's initial load.
 *
 * Scoped to exactly what `RoomPricingRuleRepository` calls. Room generates an
 * implementation for every method whether or not anything uses it, and an
 * unused `@Delete` here is a query nobody has run, tested, or thought about the
 * consequences of.
 */
@Dao
interface PricingRuleDao {

    /**
     * Every rule, cheapest first, re-emitting on any change to the table.
     *
     * This `Flow` is what keeps the in-memory cache honest: Room's invalidation
     * tracker fires it for *any* write to `pricing_rules`, including ones made
     * by code that has never heard of the cache.
     */
    @Query("SELECT * FROM pricing_rules ORDER BY amountCents ASC")
    fun observeAll(): Flow<List<PricingRuleEntity>>

    @Query("SELECT * FROM pricing_rules ORDER BY amountCents ASC")
    suspend fun getAll(): List<PricingRuleEntity>

    /** Active rules at a given price — for the duplicate warning on the rules screen. */
    @Query("SELECT * FROM pricing_rules WHERE amountCents = :amountCents AND isActive = 1")
    suspend fun findActiveByAmount(amountCents: Long): List<PricingRuleEntity>

    /** Inserts when id is 0, updates otherwise. Returns the row id. */
    @Upsert
    suspend fun upsert(rule: PricingRuleEntity): Long

    @Query("DELETE FROM pricing_rules WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE pricing_rules SET isActive = :isActive WHERE id = :id")
    suspend fun setActive(id: Long, isActive: Boolean)
}
