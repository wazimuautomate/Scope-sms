package com.scopesms.autoreply.data.rules

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.scopesms.autoreply.domain.money.KshAmount
import com.scopesms.autoreply.domain.rules.PricingRule

/**
 * A pricing rule as stored. The domain model is
 * [com.scopesms.autoreply.domain.rules.PricingRule]; they're separate so
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
 * see [com.scopesms.autoreply.domain.rules.RuleSnapshot.from].
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
) {
    fun toDomain(): PricingRule = PricingRule(
        id = id,
        amount = KshAmount(amountCents),
        bundleDescription = bundleDescription,
        isActive = isActive,
    )

    companion object {
        fun fromDomain(rule: PricingRule): PricingRuleEntity = PricingRuleEntity(
            id = rule.id,
            amountCents = rule.amount.cents,
            bundleDescription = rule.bundleDescription,
            isActive = rule.isActive,
        )
    }
}
