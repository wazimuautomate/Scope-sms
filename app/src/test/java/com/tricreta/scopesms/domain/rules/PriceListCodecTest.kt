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
    }
}
