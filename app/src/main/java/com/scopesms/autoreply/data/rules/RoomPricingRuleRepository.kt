package com.scopesms.autoreply.data.rules

import com.scopesms.autoreply.domain.money.KshAmount
import com.scopesms.autoreply.domain.rules.PricingRule
import com.scopesms.autoreply.domain.rules.PricingRuleRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Room-backed price list. The source of truth; the cache is a projection of it.
 *
 * Thin by design — it maps between [PricingRuleEntity] and [PricingRule] and
 * does nothing else. No cache poking: the container collects [observeAll] and
 * publishes from there, so a write made through any path updates the cache
 * without this class arranging it. See
 * [com.scopesms.autoreply.domain.cache.SnapshotCache].
 */
class RoomPricingRuleRepository(private val dao: PricingRuleDao) : PricingRuleRepository {

    override fun observeAll(): Flow<List<PricingRule>> =
        dao.observeAll().map { rows -> rows.map(PricingRuleEntity::toDomain) }

    override suspend fun getAll(): List<PricingRule> =
        dao.getAll().map(PricingRuleEntity::toDomain)

    override suspend fun upsert(rule: PricingRule): Long =
        dao.upsert(PricingRuleEntity.fromDomain(rule))

    override suspend fun delete(id: Long) = dao.deleteById(id)

    override suspend fun setActive(id: Long, isActive: Boolean) = dao.setActive(id, isActive)

    override suspend fun findActiveByAmount(amount: KshAmount): List<PricingRule> =
        dao.findActiveByAmount(amount.cents).map(PricingRuleEntity::toDomain)
}
