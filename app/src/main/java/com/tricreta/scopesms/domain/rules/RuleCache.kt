package com.tricreta.scopesms.domain.rules

import com.tricreta.scopesms.domain.cache.SnapshotCache

/**
 * The in-memory price list the SMS receive path matches against.
 *
 * Fed from Room by `di/AppContainer`; see [SnapshotCache] for the contract, the
 * cold-start hazard, and why writers don't update this directly.
 *
 * ```
 * // On the decide path (Phase 5b), inside goAsync():
 * val snapshot = withTimeout(5_000) { ruleCache.awaitLoaded() }
 * when (val outcome = snapshot.classify(payment.amount)) { ... }
 * ```
 */
class RuleCache : SnapshotCache<List<PricingRule>, RuleSnapshot>() {

    override fun buildSnapshot(source: List<PricingRule>): RuleSnapshot = RuleSnapshot.from(source)
}
