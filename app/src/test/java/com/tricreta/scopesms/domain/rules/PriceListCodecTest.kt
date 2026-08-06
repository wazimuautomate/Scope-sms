package com.tricreta.scopesms.domain.rules

import com.google.common.truth.Truth.assertThat
import com.tricreta.scopesms.domain.money.KshAmount
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The price-list export/import codec (the "share prices between phones" feature).
 *
 * Robolectric because it uses `org.json`, which is a stub on the plain JVM but
 * real under Robolectric — the same reason the DAO tests run here. Pinned to
 * SDK 30, the minSdk floor, like the rest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class PriceListCodecTest {

    private val rules = listOf(
        PricingRule(1, KshAmount.ofShillings(20), "1GB Daily", isActive = true),
        PricingRule(2, KshAmount.ofShillings(50), "2GB Weekly", isActive = false),
    )

    @Test
    fun `export then import round-trips the prices`() {
        val json = PriceListCodec.export(rules, now = 1_721_000_000_000)
        val result = PriceListCodec.import(json)

        assertThat(result).isInstanceOf(PriceListCodec.ImportResult.Loaded::class.java)
        val loaded = (result as PriceListCodec.ImportResult.Loaded).rules

        assertThat(loaded).hasSize(2)
        assertThat(loaded[0].amount).isEqualTo(KshAmount.ofShillings(20))
        assertThat(loaded[0].bundleDescription).isEqualTo("1GB Daily")
        assertThat(loaded[0].isActive).isTrue()
        // The paused state survives the trip — a paused bundle stays paused.
        assertThat(loaded[1].isActive).isFalse()
    }

    @Test
    fun `amount is written in whole shillings, not cents`() {
        // The file is human-readable, so "20" not "2000" — and it's what import
        // reads back through ofShillings.
        val json = PriceListCodec.export(rules, now = 0)
        assertThat(json).contains("\"amount\": 20")
        assertThat(json).doesNotContain("2000")
    }

    @Test
    fun `a file that is not ours is rejected, never crashes`() {
        assertThat(PriceListCodec.import("not json at all"))
            .isEqualTo(PriceListCodec.ImportResult.NotAPriceList)
        assertThat(PriceListCodec.import("""{"something":"else"}"""))
            .isEqualTo(PriceListCodec.ImportResult.NotAPriceList)
        assertThat(PriceListCodec.import(""))
            .isEqualTo(PriceListCodec.ImportResult.NotAPriceList)
    }

    @Test
    fun `a newer format is called out as unsupported, not corrupt`() {
        // So the agent is told to update the app rather than told their file is
        // broken — different problem, different fix.
        val result = PriceListCodec.import("""{"scope_sms_prices":99,"prices":[]}""")
        assertThat(result).isEqualTo(PriceListCodec.ImportResult.UnsupportedVersion(99))
    }

    @Test
    fun `a single bad row is skipped, the rest import`() {
        // Importing 2 good prices beats rejecting the file over one broken row
        // the agent can re-add by hand.
        val json = """
            {
              "scope_sms_prices": 1,
              "prices": [
                { "amount": 20, "bundle": "1GB Daily" },
                { "amount": -5, "bundle": "bad amount" },
                { "amount": 50, "bundle": "" },
                { "amount": 100, "bundle": "5GB Monthly" }
              ]
            }
        """.trimIndent()

        val loaded = (PriceListCodec.import(json) as PriceListCodec.ImportResult.Loaded).rules

        assertThat(loaded.map { it.bundleDescription }).containsExactly("1GB Daily", "5GB Monthly")
        // A row that omits "active" defaults to active — "sell it" is the safe read.
        assertThat(loaded.all { it.isActive }).isTrue()
        // And a row with no category defaults to the default, never crashes.
        assertThat(loaded.all { it.category == BundleCategory.DEFAULT }).isTrue()
    }

    @Test
    fun `category round-trips through export and import`() {
        val withCategories = listOf(
            PricingRule(1, KshAmount.ofShillings(20), "1GB Daily", category = BundleCategory.DATA),
            PricingRule(2, KshAmount.ofShillings(30), "50 mins", category = BundleCategory.MINUTES),
            PricingRule(3, KshAmount.ofShillings(10), "200 SMS", category = BundleCategory.SMS),
        )
        val loaded = (
            PriceListCodec.import(PriceListCodec.export(withCategories, now = 0))
                as PriceListCodec.ImportResult.Loaded
            ).rules

        assertThat(loaded.map { it.category })
            .containsExactly(BundleCategory.DATA, BundleCategory.MINUTES, BundleCategory.SMS)
            .inOrder()
    }

    @Test
    fun `an unknown category name degrades to the default rather than throwing`() {
        val json = """
            {
              "scope_sms_prices": 1,
              "prices": [ { "amount": 20, "bundle": "1GB", "category": "TELEPORT" } ]
            }
        """.trimIndent()
        val loaded = (PriceListCodec.import(json) as PriceListCodec.ImportResult.Loaded).rules
        assertThat(loaded[0].category).isEqualTo(BundleCategory.DEFAULT)
    }

    @Test
    fun `purchase_limit round-trips through export and import`() {
        val withLimits = listOf(
            PricingRule(1, KshAmount.ofShillings(20), "1GB Daily", purchaseLimit = PurchaseLimit.ONCE_PER_DAY),
            PricingRule(2, KshAmount.ofShillings(50), "2GB Weekly", purchaseLimit = PurchaseLimit.MULTIPLE_PER_DAY),
        )
        val loaded = (
            PriceListCodec.import(PriceListCodec.export(withLimits, now = 0))
                as PriceListCodec.ImportResult.Loaded
            ).rules

        assertThat(loaded.map { it.purchaseLimit })
            .containsExactly(PurchaseLimit.ONCE_PER_DAY, PurchaseLimit.MULTIPLE_PER_DAY)
            .inOrder()
    }

    @Test
    fun `a missing or unknown purchase_limit degrades to the default rather than throwing`() {
        val json = """
            {
              "scope_sms_prices": 1,
              "prices": [
                { "amount": 20, "bundle": "1GB" },
                { "amount": 30, "bundle": "50 mins", "purchase_limit": "TWICE_A_WEEK" }
              ]
            }
        """.trimIndent()
        val loaded = (PriceListCodec.import(json) as PriceListCodec.ImportResult.Loaded).rules
        assertThat(loaded.all { it.purchaseLimit == PurchaseLimit.DEFAULT }).isTrue()
    }

    // --- Purchase window ------------------------------------------------------

    @Test
    fun `purchase window round-trips through export and import`() {
        val restricted = listOf(
            PricingRule(
                1,
                KshAmount.ofShillings(19),
                "1GB 1Hr",
                purchaseWindow = PurchaseWindow(16 * 60, 22 * 60 + 59),
            ),
        )
        val loaded = (
            PriceListCodec.import(PriceListCodec.export(restricted, now = 0))
                as PriceListCodec.ImportResult.Loaded
            ).rules

        assertThat(loaded.single().purchaseWindow).isEqualTo(PurchaseWindow(16 * 60, 22 * 60 + 59))
    }

    @Test
    fun `a file with an explicit all-day window round-trips as all-day`() {
        val allDay = listOf(PricingRule(1, KshAmount.ofShillings(20), "1GB Daily"))
        val loaded = (
            PriceListCodec.import(PriceListCodec.export(allDay, now = 0))
                as PriceListCodec.ImportResult.Loaded
            ).rules

        assertThat(loaded.single().purchaseWindow).isEqualTo(PurchaseWindow.DEFAULT)
    }

    @Test
    fun `a file with no window keys at all defaults to all-day`() {
        // An older export, or a hand-written file, simply omits the keys.
        val json = """
            {
              "scope_sms_prices": 1,
              "prices": [ { "amount": 20, "bundle": "1GB Daily" } ]
            }
        """.trimIndent()
        val loaded = (PriceListCodec.import(json) as PriceListCodec.ImportResult.Loaded).rules

        assertThat(loaded.single().purchaseWindow).isEqualTo(PurchaseWindow.DEFAULT)
        assertThat(loaded.single().purchaseWindow.isAllDay).isTrue()
    }

    @Test
    fun `a file with only one of the two window keys defaults to all-day rather than throwing`() {
        val json = """
            {
              "scope_sms_prices": 1,
              "prices": [
                { "amount": 20, "bundle": "1GB Daily", "window_start_minute": 960 }
              ]
            }
        """.trimIndent()
        val loaded = (PriceListCodec.import(json) as PriceListCodec.ImportResult.Loaded).rules

        assertThat(loaded.single().purchaseWindow).isEqualTo(PurchaseWindow.DEFAULT)
    }

    @Test
    fun `a file with an out-of-range window value defaults to all-day rather than crashing`() {
        val json = """
            {
              "scope_sms_prices": 1,
              "prices": [
                { "amount": 20, "bundle": "1GB Daily", "window_start_minute": 9999, "window_end_minute": 100 }
              ]
            }
        """.trimIndent()
        val loaded = (PriceListCodec.import(json) as PriceListCodec.ImportResult.Loaded).rules

        assertThat(loaded.single().purchaseWindow).isEqualTo(PurchaseWindow.DEFAULT)
    }

    @Test
    fun `the exported window is written in minutes, not a time string`() {
        val restricted = listOf(
            PricingRule(
                1,
                KshAmount.ofShillings(19),
                "1GB 1Hr",
                purchaseWindow = PurchaseWindow(16 * 60, 22 * 60 + 59),
            ),
        )
        val json = PriceListCodec.export(restricted, now = 0)

        assertThat(json).contains("\"window_start_minute\": 960")
        assertThat(json).contains("\"window_end_minute\": 1379")
    }
}
