package com.tricreta.scopesms.data.rules

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.tricreta.scopesms.domain.money.KshAmount
import com.tricreta.scopesms.domain.rules.BundleCategory
import com.tricreta.scopesms.domain.rules.PricingRule
import com.tricreta.scopesms.domain.rules.PurchaseLimit
import com.tricreta.scopesms.domain.rules.PurchaseWindow

/**
 * A pricing rule as stored. The domain model is
 * [com.tricreta.scopesms.domain.rules.PricingRule]; they're separate so
 * `domain/` carries no Room dependency and stays JVM-testable
 * (`domain/README.md`).
 *
 * ## Why `amountCents` is a plain Long
 * The domain uses a [KshAmount] value class, but this column doesn't. Storing a
 * value class needs a `@TypeConverter` and gains nothing: SQLite would hold the
 * same integer either way. Converting at this boundary ([toDomain]/[fromDomain])
 * keeps the type safety where decisions are made and keeps Room's generated code
 * boring.
 *
 * ## Why there is no unique index on `amountCents`
 * Tempting, and wrong. The constraint that actually matters is "unique among
 * *active* rules" — an agent should be free to deactivate the old Ksh 50 bundle
 * and add a new one at the same price. Room's `@Index` can't express a partial
 * index, so a unique index here would forbid that legitimate edit while still
 * not being the rule we mean. Duplicates are instead resolved deterministically
 * when the snapshot is built and surfaced to the UI —
 * see [com.tricreta.scopesms.domain.rules.RuleSnapshot.from].
 */
@Entity(
    tableName = "pricing_rules",
    // Non-unique: matching reads the in-memory cache and never queries by
    // amount, but the rules screen checks this column to warn about duplicates
    // before saving.
    indices = [Index("amountCents")],
)
data class PricingRuleEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** The bundle's price in cents. See [KshAmount] for why not shillings. */
    val amountCents: Long,

    val bundleDescription: String,

    @ColumnInfo(defaultValue = "1")
    val isActive: Boolean = true,

    /**
     * Bundle category, stored by [BundleCategory.name].
     *
     * **Nullable on purpose.** The v1→v2 migration adds it with a plain
     * `ALTER TABLE … ADD COLUMN category TEXT` (no default), so every pre-category
     * row is NULL. That reads back as [BundleCategory.DEFAULT] via
     * [BundleCategory.fromName]; new writes always store a non-null name. A
     * nullable column with no default is what the migration produces exactly, so
     * Room's runtime schema check passes without the default-value quoting
     * subtleties a `NOT NULL DEFAULT` column would introduce.
     */
    val category: String? = BundleCategory.DEFAULT.name,

    /**
     * How often one customer can buy this bundle per day, stored by
     * [PurchaseLimit.name].
     *
     * **Nullable on purpose**, exactly like [category]: the v2→v3 migration
     * adds it with a plain `ALTER TABLE … ADD COLUMN purchaseLimit TEXT` (no
     * default), so every pre-existing row is NULL and reads back as
     * [PurchaseLimit.DEFAULT] via [PurchaseLimit.fromName] — the bundles the
     * client already entered keep behaving exactly as before until they
     * edit one to say otherwise.
     */
    val purchaseLimit: String? = PurchaseLimit.DEFAULT.name,

    /**
     * The bundle's purchase window, in minutes since local midnight.
     *
     * **Nullable on purpose**, exactly like [category]/[purchaseLimit]: the
     * v3→v4 migration adds both columns with a plain `ALTER TABLE … ADD COLUMN
     * … INTEGER` (no default), so every pre-existing row is NULL in both. That
     * reads back as [PurchaseWindow.DEFAULT] (all day, every day) via
     * [toDomain] — every bundle the client already priced keeps behaving
     * exactly as before until they edit one to restrict it. Both columns are
     * always written together (see [fromDomain]): there is no state where one
     * is null and the other isn't, other than a pre-migration row.
     */
    val windowStartMinute: Int? = PurchaseWindow.DEFAULT.startMinute,
    val windowEndMinute: Int? = PurchaseWindow.DEFAULT.endMinute,
) {
    fun toDomain(): PricingRule = PricingRule(
        id = id,
        amount = KshAmount(amountCents),
        bundleDescription = bundleDescription,
        isActive = isActive,
        category = BundleCategory.fromName(category),
        purchaseLimit = PurchaseLimit.fromName(purchaseLimit),
        purchaseWindow = if (windowStartMinute != null && windowEndMinute != null) {
            PurchaseWindow(windowStartMinute, windowEndMinute)
        } else {
            PurchaseWindow.DEFAULT
        },
    )

    companion object {
        fun fromDomain(rule: PricingRule): PricingRuleEntity = PricingRuleEntity(
            id = rule.id,
            amountCents = rule.amount.cents,
            bundleDescription = rule.bundleDescription,
            isActive = rule.isActive,
            category = rule.category.name,
            purchaseLimit = rule.purchaseLimit.name,
            // New writes are never null — null only ever occurs on rows from
            // before this column existed, exactly like category/purchaseLimit.
            windowStartMinute = rule.purchaseWindow.startMinute,
            windowEndMinute = rule.purchaseWindow.endMinute,
        )
    }
}
