package com.tricreta.scopesms.domain.rules

import com.tricreta.scopesms.domain.money.KshAmount
import org.json.JSONArray
import org.json.JSONObject

/**
 * Serialises the price list to and from a small, human-readable JSON document,
 * so an agent can copy their bundles between phones.
 *
 * ## Shape
 * ```
 * {
 *   "scope_sms_prices": 1,
 *   "exported_at": 1721000000000,
 *   "prices": [
 *     { "amount": 20, "bundle": "1GB Daily", "active": true },
 *     { "amount": 50, "bundle": "2GB Weekly", "active": true }
 *   ]
 * }
 * ```
 *
 * ## Two rules that matter
 * - **`amount` is whole shillings, not cents.** The file is something the agent
 *   might read or hand-edit; `"amount": 20` is obvious where `2000` invites
 *   someone to "fix" it. It round-trips through [KshAmount.ofShillings], and
 *   because every rule is already whole shillings (rule entry rejects decimals),
 *   nothing is lost. A row that somehow carried cents is rejected on import
 *   rather than silently truncated.
 * - **Import never throws.** It is handed a file the agent picked, which may be
 *   truncated, from a newer version, or not ours at all. Every parse failure is
 *   a typed [ImportResult], because a crash while restoring a backup is the
 *   worst moment to have one.
 *
 * `org.json` is used rather than Moshi: it is in the Android platform (no
 * dependency, no R8 keep rules), and this document is small and flat.
 */
object PriceListCodec {

    private const val VERSION_KEY = "scope_sms_prices"
    private const val EXPORTED_AT_KEY = "exported_at"
    private const val PRICES_KEY = "prices"
    private const val AMOUNT_KEY = "amount"
    private const val BUNDLE_KEY = "bundle"
    private const val ACTIVE_KEY = "active"
    private const val CATEGORY_KEY = "category"

    // Stays 1 even though `category` was added: it is an *optional* field. An
    // older app ignores it and still imports the prices; this app defaults a file
    // without it to BundleCategory.DEFAULT. Bumping the version would make the old
    // app reject the file outright — the worse outcome for the agent's data.
    private const val CURRENT_VERSION = 1

    /**
     * Renders [rules] to the export document.
     *
     * @param now epoch millis stamped into the file. Passed in rather than read
     *   here so the codec stays pure and testable.
     */
    fun export(rules: List<PricingRule>, now: Long): String {
        val prices = JSONArray()
        for (rule in rules) {
            // Whole shillings. A rule can't hold cents (entry forbids it), so this
            // is lossless; guard anyway rather than write a fractional "amount".
            require(rule.amount.isWholeShillings) {
                "Rule ${rule.id} has cents; price list is meant to be whole shillings"
            }
            prices.put(
                JSONObject()
                    .put(AMOUNT_KEY, rule.amount.shillings)
                    .put(BUNDLE_KEY, rule.bundleDescription)
                    .put(ACTIVE_KEY, rule.isActive)
                    .put(CATEGORY_KEY, rule.category.name),
            )
        }

        return JSONObject()
            .put(VERSION_KEY, CURRENT_VERSION)
            .put(EXPORTED_AT_KEY, now)
            .put(PRICES_KEY, prices)
            .toString(2)
    }

    /** The result of reading an import file. */
    sealed interface ImportResult {

        /**
         * Parsed cleanly. [rules] have `id = 0` — they are new rows to insert,
         * not edits to existing ones; the importer decides whether to merge or
         * replace.
         */
        data class Loaded(val rules: List<ImportedRule>) : ImportResult

        /** Not a Scope SMS price file, or too corrupt to read. */
        data object NotAPriceList : ImportResult

        /**
         * Recognisably ours, but a newer format than this app understands.
         * Distinct from [NotAPriceList] so the agent is told to update the app
         * rather than told their file is broken.
         */
        data class UnsupportedVersion(val version: Int) : ImportResult
    }

    /** A rule as read from a file, before it becomes a [PricingRule]. */
    data class ImportedRule(
        val amount: KshAmount,
        val bundleDescription: String,
        val isActive: Boolean,
        val category: BundleCategory,
    )

    /**
     * Reads an export document. Never throws.
     */
    fun import(text: String): ImportResult {
        val root = try {
            JSONObject(text)
        } catch (e: Exception) {
            return ImportResult.NotAPriceList
        }

        if (!root.has(VERSION_KEY)) return ImportResult.NotAPriceList

        val version = root.optInt(VERSION_KEY, -1)
        if (version <= 0) return ImportResult.NotAPriceList
        if (version > CURRENT_VERSION) return ImportResult.UnsupportedVersion(version)

        val array = root.optJSONArray(PRICES_KEY) ?: return ImportResult.NotAPriceList

        val rules = buildList {
            for (i in 0 until array.length()) {
                val row = array.optJSONObject(i) ?: continue

                // A row must have a whole-shilling amount and a non-blank bundle.
                // A bad row is skipped, not fatal: importing 9 of 10 good prices
                // beats rejecting the whole file over one the agent can re-add.
                if (!row.has(AMOUNT_KEY)) continue
                val shillings = row.optLong(AMOUNT_KEY, -1)
                if (shillings < 0) continue

                val bundle = row.optString(BUNDLE_KEY).trim()
                if (bundle.isEmpty()) continue

                add(
                    ImportedRule(
                        amount = KshAmount.ofShillings(shillings),
                        bundleDescription = bundle,
                        // Absent → active. An older/hand-written file may omit it,
                        // and "sell this bundle" is the safe assumption.
                        isActive = row.optBoolean(ACTIVE_KEY, true),
                        // Absent/unknown → DEFAULT (older files, hand edits).
                        category = BundleCategory.fromName(
                            row.optString(CATEGORY_KEY).ifBlank { null },
                        ),
                    ),
                )
            }
        }

        return ImportResult.Loaded(rules)
    }
}
