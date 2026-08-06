package com.tricreta.scopesms.domain.rules

import com.google.common.truth.Truth.assertThat
import com.tricreta.scopesms.domain.money.KshAmount
import kotlin.system.measureTimeMillis
import org.junit.Test

/**
 * BUILD-PLAN Phase 3's exit criteria: exact-match, no-match, duplicate-amount,
 * and a lookup that is a map access rather than a DB round trip.
 *
 * The Room half of "cache and Room stay in sync" lives in
 * `data/RoomCacheSyncTest`; this covers the matching logic, which is pure and
 * needs no Android.
 */
class RuleSnapshotTest {

    private fun rule(id: Long, shillings: Long, description: String, active: Boolean = true) =
        PricingRule(id, KshAmount.ofShillings(shillings), description, active)

    private val priceList = listOf(
        rule(1, 20, "1GB Daily"),
        rule(2, 50, "2GB Weekly"),
        rule(3, 100, "5GB Weekly"),
    )

    @Test
    fun `exact match returns the rule`() {
        val snapshot = RuleSnapshot.from(priceList)

        val outcome = snapshot.classify(KshAmount.ofShillings(50))

        assertThat(outcome).isInstanceOf(MatchOutcome.Matched::class.java)
        assertThat((outcome as MatchOutcome.Matched).rule.bundleDescription).isEqualTo("2GB Weekly")
    }

    @Test
    fun `no match when the amount is not a bundle price`() {
        val snapshot = RuleSnapshot.from(priceList)

        assertThat(snapshot.classify(KshAmount.ofShillings(35))).isEqualTo(MatchOutcome.Unmatched)
    }

    @Test
    fun `matching is exact to the cent`() {
        val snapshot = RuleSnapshot.from(priceList)

        // Ksh 20.50 buys nothing, even though Ksh 20 buys 1GB Daily. Rounding
        // or truncating here would confirm a purchase the customer never made.
        assertThat(snapshot.classify(KshAmount(2050))).isEqualTo(MatchOutcome.Unmatched)
        assertThat(snapshot.classify(KshAmount(1999))).isEqualTo(MatchOutcome.Unmatched)
        assertThat(snapshot.classify(KshAmount(2000))).isInstanceOf(MatchOutcome.Matched::class.java)
    }

    @Test
    fun `an empty price list is not the same as no match`() {
        // The distinction that stops a fresh install texting every paying
        // customer an empty price list.
        val snapshot = RuleSnapshot.from(emptyList())

        assertThat(snapshot.classify(KshAmount.ofShillings(20)))
            .isEqualTo(MatchOutcome.NoRulesConfigured)
        assertThat(snapshot.hasNoActiveRules).isTrue()
    }

    @Test
    fun `a price list of only inactive rules counts as no rules configured`() {
        // Same hazard as an empty table: nothing can ever match, so every
        // payment would look "unmatched" and get quoted a list that renders
        // empty.
        val snapshot = RuleSnapshot.from(priceList.map { it.copy(isActive = false) })

        assertThat(snapshot.classify(KshAmount.ofShillings(20)))
            .isEqualTo(MatchOutcome.NoRulesConfigured)
    }

    @Test
    fun `inactive rules never match but are still listed for the UI`() {
        val snapshot = RuleSnapshot.from(
            listOf(rule(1, 20, "1GB Daily"), rule(2, 50, "Paused bundle", active = false)),
        )

        assertThat(snapshot.classify(KshAmount.ofShillings(50))).isEqualTo(MatchOutcome.Unmatched)
        assertThat(snapshot.activeRules).hasSize(1)
        assertThat(snapshot.allRules).hasSize(2)
    }

    @Test
    fun `duplicate amounts resolve to the most recently added rule`() {
        val snapshot = RuleSnapshot.from(
            listOf(
                rule(1, 50, "1.5GB Weekly"), // the old price
                rule(7, 50, "2GB Weekly"), // agent re-priced by adding a new row
            ),
        )

        val outcome = snapshot.classify(KshAmount.ofShillings(50))

        assertThat((outcome as MatchOutcome.Matched).rule.bundleDescription).isEqualTo("2GB Weekly")
    }

    @Test
    fun `duplicate amounts are reported so the UI can warn`() {
        val snapshot = RuleSnapshot.from(
            listOf(rule(1, 50, "1.5GB Weekly"), rule(7, 50, "2GB Weekly"), rule(2, 20, "1GB Daily")),
        )

        // Resolving silently is not enough: the agent has two bundles at one
        // price and only ever sees one quoted.
        assertThat(snapshot.duplicateAmounts).containsExactly(KshAmount.ofShillings(50))
    }

    @Test
    fun `an inactive rule does not make its amount a duplicate`() {
        val snapshot = RuleSnapshot.from(
            listOf(rule(1, 50, "Old bundle", active = false), rule(2, 50, "New bundle")),
        )

        // Deactivating the old Ksh 50 and adding a new one is a supported way
        // to re-price. It must not raise a duplicate warning.
        assertThat(snapshot.duplicateAmounts).isEmpty()
        assertThat((snapshot.classify(KshAmount.ofShillings(50)) as MatchOutcome.Matched).rule.bundleDescription)
            .isEqualTo("New bundle")
    }

    @Test
    fun `no duplicates reported for a clean price list`() {
        assertThat(RuleSnapshot.from(priceList).duplicateAmounts).isEmpty()
    }

    @Test
    fun `active rules are ordered cheapest first for the bundle list`() {
        val snapshot = RuleSnapshot.from(
            listOf(rule(1, 100, "5GB"), rule(2, 20, "1GB"), rule(3, 50, "2GB")),
        )

        assertThat(snapshot.activeRules.map { it.bundleDescription })
            .containsExactly("1GB", "2GB", "5GB")
            .inOrder()
    }

    @Test
    fun `the empty snapshot is usable without construction`() {
        assertThat(RuleSnapshot.EMPTY.classify(KshAmount.ofShillings(20)))
            .isEqualTo(MatchOutcome.NoRulesConfigured)
        assertThat(RuleSnapshot.EMPTY.activeRules).isEmpty()
    }

    // --- Purchase window ------------------------------------------------------

    @Test
    fun `a matched amount outside its bundle's window classifies as OutOfWindow`() {
        val restricted = rule(1, 20, "1GB 1Hr")
            .copy(purchaseWindow = PurchaseWindow(16 * 60, 22 * 60 + 59)) // 4:00 PM to 10:59 PM
        val snapshot = RuleSnapshot.from(listOf(restricted))

        val outcome = snapshot.classify(KshAmount.ofShillings(20), minuteOfDay = 10 * 60) // 10:00 AM

        assertThat(outcome).isEqualTo(MatchOutcome.OutOfWindow(restricted))
    }

    @Test
    fun `a matched amount inside its bundle's window still classifies as Matched`() {
        val restricted = rule(1, 20, "1GB 1Hr")
            .copy(purchaseWindow = PurchaseWindow(16 * 60, 22 * 60 + 59))
        val snapshot = RuleSnapshot.from(listOf(restricted))

        val outcome = snapshot.classify(KshAmount.ofShillings(20), minuteOfDay = 18 * 60) // 6:00 PM

        assertThat(outcome).isEqualTo(MatchOutcome.Matched(restricted))
    }

    @Test
    fun `a null minuteOfDay skips the window check entirely`() {
        // Existing callers (mostly tests) that don't pass a minuteOfDay must see
        // every match as in-window, exactly as before this feature existed.
        val restricted = rule(1, 20, "1GB 1Hr")
            .copy(purchaseWindow = PurchaseWindow(16 * 60, 22 * 60 + 59))
        val snapshot = RuleSnapshot.from(listOf(restricted))

        assertThat(snapshot.classify(KshAmount.ofShillings(20))).isEqualTo(MatchOutcome.Matched(restricted))
    }

    @Test
    fun `an unrestricted bundle matches at any minute of the day`() {
        // PurchaseWindow.DEFAULT is every rule's starting point — nothing already
        // priced should ever classify as OutOfWindow by accident.
        val snapshot = RuleSnapshot.from(priceList)

        assertThat(snapshot.classify(KshAmount.ofShillings(20), minuteOfDay = 0))
            .isInstanceOf(MatchOutcome.Matched::class.java)
        assertThat(snapshot.classify(KshAmount.ofShillings(20), minuteOfDay = 1439))
            .isInstanceOf(MatchOutcome.Matched::class.java)
    }

    @Test
    fun `lookup is a map access, not a scan`() {
        // BUILD-PLAN Phase 3 asks for a benchmark-style check that matching is
        // O(1)-ish. The bound is deliberately loose — this runs on a shared CI
        // runner and the point is to catch a linear scan or an accidental I/O
        // call, not to measure the JIT. A list scan over 10k rules for 100k
        // lookups would be ~1e9 comparisons and miss this by orders of
        // magnitude.
        val large = (1..10_000L).map { rule(it, it, "Bundle $it") }
        val snapshot = RuleSnapshot.from(large)

        val elapsed = measureTimeMillis {
            repeat(100_000) { i ->
                snapshot.classify(KshAmount.ofShillings((i % 10_000).toLong() + 1))
            }
        }

        assertThat(elapsed).isLessThan(2_000)
    }
}
