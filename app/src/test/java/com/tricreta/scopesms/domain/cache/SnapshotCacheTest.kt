package com.tricreta.scopesms.domain.cache

import com.google.common.truth.Truth.assertThat
import com.tricreta.scopesms.domain.money.KshAmount
import com.tricreta.scopesms.domain.rules.MatchOutcome
import com.tricreta.scopesms.domain.rules.PricingRule
import com.tricreta.scopesms.domain.rules.RuleCache
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Test

/**
 * The cold-start contract. [SnapshotCache.awaitLoaded] exists to close a window
 * in which an incoming SMS is decided against a cache that hasn't loaded yet,
 * and these are the tests that hold it shut.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SnapshotCacheTest {

    private fun rule(id: Long, shillings: Long, description: String) =
        PricingRule(id, KshAmount.ofShillings(shillings), description)

    @Test
    fun `a fresh cache has not loaded and offers no snapshot`() {
        val cache = RuleCache()

        assertThat(cache.isLoaded).isFalse()
        assertThat(cache.currentOrNull()).isNull()
    }

    @Test
    fun `awaitLoaded suspends until the first publish rather than answering empty`() = runTest {
        // The bug this prevents: an SMS arrives on a cold process start, the
        // receiver asks the cache before Room has answered, and a Ksh 20
        // payment that *does* match gets classified NoRulesConfigured or
        // Unmatched. The caller must wait, not get a wrong answer fast.
        val cache = RuleCache()
        var outcome: MatchOutcome? = null

        val waiter = launch {
            outcome = cache.awaitLoaded().classify(KshAmount.ofShillings(20))
        }

        advanceUntilIdle()
        assertThat(outcome).isNull() // still waiting — crucially, not "Unmatched"

        cache.publish(listOf(rule(1, 20, "1GB Daily")))
        waiter.join()

        assertThat(outcome).isInstanceOf(MatchOutcome.Matched::class.java)
    }

    @Test
    fun `awaitLoaded returns immediately once loaded`() = runTest {
        val cache = RuleCache()
        cache.publish(listOf(rule(1, 20, "1GB Daily")))

        val snapshot = withTimeout(1_000) { cache.awaitLoaded() }

        assertThat(snapshot.activeRules).hasSize(1)
        assertThat(cache.isLoaded).isTrue()
    }

    @Test
    fun `publishing an empty list still counts as loaded`() = runTest {
        // An agent who genuinely has no rules yet must not hang the receiver
        // forever waiting for a load that already happened. "Loaded and empty"
        // is a real, answerable state — NoRulesConfigured.
        val cache = RuleCache()

        cache.publish(emptyList())

        val snapshot = withTimeout(1_000) { cache.awaitLoaded() }
        assertThat(snapshot.classify(KshAmount.ofShillings(20)))
            .isEqualTo(MatchOutcome.NoRulesConfigured)
    }

    @Test
    fun `republishing replaces the snapshot`() = runTest {
        val cache = RuleCache()
        cache.publish(listOf(rule(1, 20, "1GB Daily")))

        cache.publish(listOf(rule(1, 20, "1GB Daily"), rule(2, 50, "2GB Weekly")))

        assertThat(cache.awaitLoaded().classify(KshAmount.ofShillings(50)))
            .isInstanceOf(MatchOutcome.Matched::class.java)
    }

    @Test
    fun `an edited price is quoted immediately after republish`() = runTest {
        // The stale-cache bug in miniature: the agent re-prices Ksh 50 and the
        // very next customer must be quoted the new bundle, not the old one.
        val cache = RuleCache()
        cache.publish(listOf(rule(1, 50, "1.5GB Weekly")))

        cache.publish(listOf(PricingRule(1, KshAmount.ofShillings(50), "2GB Weekly")))

        val outcome = cache.awaitLoaded().classify(KshAmount.ofShillings(50))
        assertThat((outcome as MatchOutcome.Matched).rule.bundleDescription).isEqualTo("2GB Weekly")
    }

    @Test
    fun `snapshots are exposed for the UI to observe`() = runTest {
        val cache = RuleCache()

        cache.publish(listOf(rule(1, 20, "1GB Daily")))

        assertThat(cache.snapshots.value?.activeRules).hasSize(1)
    }

    @Test
    fun `a reader holding an old snapshot is unaffected by a republish`() = runTest {
        // Snapshots are immutable, which is what makes the lock-free read safe:
        // a receiver mid-decision can't have the price list changed underneath
        // it and see a half-applied edit.
        val cache = RuleCache()
        cache.publish(listOf(rule(1, 20, "1GB Daily")))
        val held = cache.awaitLoaded()

        cache.publish(emptyList())

        assertThat(held.classify(KshAmount.ofShillings(20)))
            .isInstanceOf(MatchOutcome.Matched::class.java)
        assertThat(cache.currentOrNull()?.hasNoActiveRules).isTrue()
    }
}
