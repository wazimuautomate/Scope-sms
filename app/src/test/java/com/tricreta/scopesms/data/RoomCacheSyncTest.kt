package com.tricreta.scopesms.data

import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import com.tricreta.scopesms.data.AppDatabase
import com.tricreta.scopesms.data.rules.RoomPricingRuleRepository
import com.tricreta.scopesms.data.templates.RoomMessageTemplateRepository
import com.tricreta.scopesms.domain.money.KshAmount
import com.tricreta.scopesms.domain.rules.BundleCategory
import com.tricreta.scopesms.domain.rules.MatchOutcome
import com.tricreta.scopesms.domain.rules.PricingRule
import com.tricreta.scopesms.domain.rules.PurchaseLimit
import com.tricreta.scopesms.domain.rules.PurchaseWindow
import com.tricreta.scopesms.domain.rules.RuleCache
import com.tricreta.scopesms.domain.rules.RuleSnapshot
import com.tricreta.scopesms.domain.templates.TemplateCache
import com.tricreta.scopesms.domain.templates.TemplateSnapshot
import com.tricreta.scopesms.domain.templates.TemplateType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * BUILD-PLAN Phase 3's remaining exit criteria, against a real Room database:
 * the cache stays in sync with Room across CRUD, and matching is not a DB round
 * trip.
 *
 * ## Why real Room rather than a fake DAO
 * A fake would prove the wiring in this test file and nothing about the app.
 * Everything that can actually break here is Room's: whether `@Upsert` updates
 * in place or inserts a second row, whether a `Flow` really re-emits after a
 * `DELETE`, whether `Boolean` survives a round trip through SQLite. None of it
 * is observable without the real thing — and CI is the only place this project
 * compiles at all (CLAUDE.md constraint 8), so a fake here would mean nobody had
 * ever run this code before the agent did.
 *
 * ## Why `@Config(sdk = [30])`
 * `memory.md` flags that Robolectric needs JDK 21 for SDK 36+ while CI
 * provisions 17. Pinning to 30 sidesteps that entirely — the API 30 android-all
 * jar is Java 11-compiled — and 30 is `minSdk`, which CLAUDE.md constraint 1
 * says is the level to verify against anyway. The floor, not the ceiling.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class RoomCacheSyncTest {

    private lateinit var db: AppDatabase
    private lateinit var rules: RoomPricingRuleRepository
    private lateinit var templates: RoomMessageTemplateRepository
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            AppDatabase::class.java,
        ).build()

        rules = RoomPricingRuleRepository(db.pricingRuleDao())
        templates = RoomMessageTemplateRepository(db.messageTemplateDao())
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    @After
    fun tearDown() {
        scope.cancel()
        if (db.isOpen) db.close()
    }

    /** Mirrors what `AppContainer.start()` does — that wiring is what's under test. */
    private fun startRuleSync(cache: RuleCache): Job =
        scope.launch { rules.observeAll().collect(cache::publish) }

    private fun startTemplateSync(cache: TemplateCache): Job =
        scope.launch { templates.observeAll().collect(cache::publish) }

    // --- CRUD keeps the cache in sync ---------------------------------------

    @Test
    fun `an inserted rule reaches the cache`() = runBlocking<Unit> {
        val cache = RuleCache()
        startRuleSync(cache)

        rules.upsert(PricingRule(0, KshAmount.ofShillings(20), "1GB Daily"))

        val snapshot = cache.awaitRules("the new rule to appear") { it.activeRules.size == 1 }
        assertThat(snapshot.classify(KshAmount.ofShillings(20)))
            .isInstanceOf(MatchOutcome.Matched::class.java)
    }

    @Test
    fun `an edited price is quoted, not the old one`() = runBlocking<Unit> {
        // The stale-cache bug this whole design exists to prevent: the agent
        // re-prices a bundle, and the next customer must not be told the old
        // description.
        val cache = RuleCache()
        startRuleSync(cache)
        val id = rules.upsert(PricingRule(0, KshAmount.ofShillings(50), "1.5GB Weekly"))
        cache.awaitRules("the original to be stored") { it.activeRules.size == 1 }

        rules.upsert(PricingRule(id, KshAmount.ofShillings(50), "2GB Weekly"))

        val snapshot = cache.awaitRules("the edit to apply") {
            it.activeRules.singleOrNull()?.bundleDescription == "2GB Weekly"
        }
        // And @Upsert updated in place rather than inserting a second row.
        assertThat(snapshot.allRules).hasSize(1)
    }

    @Test
    fun `a deleted rule leaves the cache`() = runBlocking<Unit> {
        val cache = RuleCache()
        startRuleSync(cache)
        val id = rules.upsert(PricingRule(0, KshAmount.ofShillings(20), "1GB Daily"))
        cache.awaitRules("the rule to be stored") { it.activeRules.size == 1 }

        rules.delete(id)

        val snapshot = cache.awaitRules("the deletion to apply") { it.hasNoActiveRules }
        assertThat(snapshot.classify(KshAmount.ofShillings(20)))
            .isEqualTo(MatchOutcome.NoRulesConfigured)
    }

    @Test
    fun `deactivating a rule stops it matching without deleting it`() = runBlocking<Unit> {
        val cache = RuleCache()
        startRuleSync(cache)
        val id = rules.upsert(PricingRule(0, KshAmount.ofShillings(20), "1GB Daily"))
        cache.awaitRules("the rule to be active") { it.activeRules.size == 1 }

        rules.setActive(id, false)

        val snapshot = cache.awaitRules("the rule to pause") { it.activeRules.isEmpty() }
        assertThat(snapshot.allRules).hasSize(1) // still there for the UI to show
    }

    @Test
    fun `isActive survives the SQLite round trip`() = runBlocking<Unit> {
        rules.upsert(PricingRule(0, KshAmount.ofShillings(20), "Active bundle", isActive = true))
        rules.upsert(PricingRule(0, KshAmount.ofShillings(50), "Paused bundle", isActive = false))

        val stored = rules.getAll()

        assertThat(stored.single { it.bundleDescription == "Active bundle" }.isActive).isTrue()
        assertThat(stored.single { it.bundleDescription == "Paused bundle" }.isActive).isFalse()
    }

    @Test
    fun `amounts survive the round trip to the cent`() = runBlocking<Unit> {
        rules.upsert(PricingRule(0, KshAmount(2050), "Oddly priced bundle"))

        assertThat(rules.getAll().single().amount).isEqualTo(KshAmount(2050))
    }

    @Test
    fun `category and purchase limit survive the round trip`() = runBlocking<Unit> {
        rules.upsert(
            PricingRule(
                0,
                KshAmount.ofShillings(20),
                "1GB Daily",
                category = BundleCategory.DATA,
                purchaseLimit = PurchaseLimit.ONCE_PER_DAY,
            ),
        )

        val stored = rules.getAll().single()

        assertThat(stored.category).isEqualTo(BundleCategory.DATA)
        assertThat(stored.purchaseLimit).isEqualTo(PurchaseLimit.ONCE_PER_DAY)
    }

    @Test
    fun `a restricted purchase window survives the round trip`() = runBlocking<Unit> {
        val window = PurchaseWindow(16 * 60, 22 * 60 + 59) // 4:00 PM to 10:59 PM
        rules.upsert(
            PricingRule(0, KshAmount.ofShillings(19), "1GB 1Hr", purchaseWindow = window),
        )

        val stored = rules.getAll().single()

        assertThat(stored.purchaseWindow).isEqualTo(window)
    }

    @Test
    fun `a rule saved with no purchase window round-trips as all-day`() = runBlocking<Unit> {
        rules.upsert(PricingRule(0, KshAmount.ofShillings(20), "1GB Daily"))

        val stored = rules.getAll().single()

        assertThat(stored.purchaseWindow).isEqualTo(PurchaseWindow.DEFAULT)
        assertThat(stored.purchaseWindow.isAllDay).isTrue()
    }

    @Test
    fun `duplicate amounts are storable and resolve to the newest`() = runBlocking<Unit> {
        // No unique index, deliberately — see PricingRuleEntity. Both rows must
        // store, and the snapshot must then pick one deterministically.
        val cache = RuleCache()
        startRuleSync(cache)

        rules.upsert(PricingRule(0, KshAmount.ofShillings(50), "1.5GB Weekly"))
        rules.upsert(PricingRule(0, KshAmount.ofShillings(50), "2GB Weekly"))

        val snapshot = cache.awaitRules("both rows to be stored") { it.allRules.size == 2 }
        val outcome = snapshot.classify(KshAmount.ofShillings(50))
        assertThat((outcome as MatchOutcome.Matched).rule.bundleDescription).isEqualTo("2GB Weekly")
        assertThat(snapshot.duplicateAmounts).containsExactly(KshAmount.ofShillings(50))
    }

    @Test
    fun `findActiveByAmount ignores paused rules`() = runBlocking<Unit> {
        rules.upsert(PricingRule(0, KshAmount.ofShillings(50), "Paused", isActive = false))
        rules.upsert(PricingRule(0, KshAmount.ofShillings(50), "Live", isActive = true))

        val found = rules.findActiveByAmount(KshAmount.ofShillings(50))

        assertThat(found.map { it.bundleDescription }).containsExactly("Live")
    }

    // --- The lookup is not a DB round trip ------------------------------------

    @Test
    fun `matching still works after the database is closed`() = runBlocking<Unit> {
        // The strongest available proof of BUILD-PLAN's "map access, not a DB
        // round trip" — and a deterministic one, with no timing or benchmark
        // involved. If classify() reached for Room, this would throw instead of
        // answering.
        val cache = RuleCache()
        val sync = startRuleSync(cache)
        rules.upsert(PricingRule(0, KshAmount.ofShillings(20), "1GB Daily"))
        val snapshot = cache.awaitRules("the rule to be cached") { it.activeRules.size == 1 }

        sync.cancel() // stop collecting first, so the close is clean
        db.close()

        assertThat(snapshot.classify(KshAmount.ofShillings(20)))
            .isInstanceOf(MatchOutcome.Matched::class.java)
        assertThat(snapshot.classify(KshAmount.ofShillings(35))).isEqualTo(MatchOutcome.Unmatched)
    }

    // --- Templates -----------------------------------------------------------

    @Test
    fun `a saved template reaches the cache and overrides the default`() = runBlocking<Unit> {
        val cache = TemplateCache()
        startTemplateSync(cache)

        templates.save(TemplateType.MATCHED, "Asante {name}!")

        val snapshot = cache.awaitTemplates("the saved wording to appear") {
            it.forType(TemplateType.MATCHED).body == "Asante {name}!"
        }
        assertThat(snapshot.forType(TemplateType.MATCHED).isDefault).isFalse()
        // The other flow is untouched and still on the shipped wording.
        assertThat(snapshot.forType(TemplateType.UNMATCHED).isDefault).isTrue()
    }

    @Test
    fun `resetting a template returns it to the shipped default`() = runBlocking<Unit> {
        val cache = TemplateCache()
        startTemplateSync(cache)
        templates.save(TemplateType.UNMATCHED, "Custom wording")
        cache.awaitTemplates("the custom wording to be stored") {
            !it.forType(TemplateType.UNMATCHED).isDefault
        }

        templates.resetToDefault(TemplateType.UNMATCHED)

        cache.awaitTemplates("the reset to apply") { it.forType(TemplateType.UNMATCHED).isDefault }
        assertThat(templates.getAll()).isEmpty()
    }

    @Test
    fun `saving a template twice replaces rather than duplicates`() = runBlocking<Unit> {
        templates.save(TemplateType.MATCHED, "First")
        templates.save(TemplateType.MATCHED, "Second")

        // Structurally guaranteed by `type` being the primary key. Asserted
        // because that guarantee is the entire reason this entity deviates from
        // BUILD-PLAN's (id, type, ...) shape.
        assertThat(templates.getAll()).hasSize(1)
        assertThat(templates.getAll().single().body).isEqualTo("Second")
    }

    @Test
    fun `the two flows are stored independently`() = runBlocking<Unit> {
        templates.save(TemplateType.MATCHED, "Matched wording")
        templates.save(TemplateType.UNMATCHED, "Unmatched wording")

        val stored = templates.getAll().associate { it.type to it.body }

        assertThat(stored[TemplateType.MATCHED]).isEqualTo("Matched wording")
        assertThat(stored[TemplateType.UNMATCHED]).isEqualTo("Unmatched wording")
    }
}

// --- Polling helpers --------------------------------------------------------
//
// Room's invalidation is asynchronous: the write returns before the Flow
// re-emits. Polling to a deadline keeps these tests deterministic — a fixed
// sleep would be flaky on a loaded CI runner and needlessly slow on a fast one.
// On timeout they raise an AssertionError naming what was being waited for,
// because CI is the only place this runs and a bare TimeoutCancellationException
// from a shared runner is close to undebuggable.

private const val TIMEOUT_MS = 10_000L
private const val POLL_MS = 5L

private suspend fun RuleCache.awaitRules(
    description: String,
    predicate: (RuleSnapshot) -> Boolean,
): RuleSnapshot = awaitSnapshot(description, { currentOrNull() }, predicate)

private suspend fun TemplateCache.awaitTemplates(
    description: String,
    predicate: (TemplateSnapshot) -> Boolean,
): TemplateSnapshot = awaitSnapshot(description, { currentOrNull() }, predicate)

private suspend fun <T : Any> awaitSnapshot(
    description: String,
    current: () -> T?,
    predicate: (T) -> Boolean,
): T = try {
    withTimeout(TIMEOUT_MS) {
        var snapshot = current()
        while (snapshot == null || !predicate(snapshot)) {
            delay(POLL_MS)
            snapshot = current()
        }
        snapshot
    }
} catch (cause: TimeoutCancellationException) {
    throw AssertionError("Timed out after ${TIMEOUT_MS}ms waiting for $description.", cause)
}
