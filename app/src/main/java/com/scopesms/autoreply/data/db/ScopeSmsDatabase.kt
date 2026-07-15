package com.scopesms.autoreply.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.scopesms.autoreply.data.log.ActivityLogDao
import com.scopesms.autoreply.data.log.ActivityLogEntity

/**
 * The app's single Room database.
 *
 * ### 🔴 Shared across phases — read before adding to it
 * Phase 8 created this file because it was the first phase to need Room, but it
 * is **not Phase 8's private property**. Phase 3 (`PricingRuleEntity`) and Phase
 * 5b (`OutboundJobEntity`) both need tables here, and at the time of writing
 * those phases are being built in parallel and haven't merged. Whoever merges
 * after this lands should **add to this class, not create a second database**:
 *
 * 1. add the entity to [entities], add its DAO as an abstract fun,
 * 2. bump [VERSION] and supply a migration (see below),
 * 3. expose the DAO from `AppContainer`.
 *
 * Two databases would mean two files, two connections, and no possibility of a
 * transaction spanning a rule change and a log write. One is correct.
 *
 * ### Migrations
 * [VERSION] is 1 and there is deliberately **no `fallbackToDestructiveMigration`**.
 * Destructive fallback would wipe the agent's price list and their entire
 * transaction history on a schema change — for an app their income depends on,
 * silently deleting their data is the worst possible response to a bump. A
 * missing migration should fail loudly in CI/testing instead, which is what Room
 * does by default.
 *
 * While every table here is still pre-release, a version bump is cheap. Once the
 * agent has the APK, every bump needs a real [androidx.room.migration.Migration].
 *
 * ### Schema export
 * `exportSchema = true` writes `app/schemas/…json`, which is committed. It is
 * what makes a future migration test possible (`room-testing` is already in the
 * version catalog) and what lets a reviewer see a schema change in a diff rather
 * than having to infer it from annotations.
 */
@Database(
    entities = [
        ActivityLogEntity::class,
        // Phase 3:  PricingRuleEntity::class
        // Phase 5b: OutboundJobEntity::class
    ],
    version = ScopeSmsDatabase.VERSION,
    exportSchema = true,
)
abstract class ScopeSmsDatabase : RoomDatabase() {

    abstract fun activityLogDao(): ActivityLogDao

    // Phase 3:  abstract fun pricingRuleDao(): PricingRuleDao
    // Phase 5b: abstract fun outboundJobDao(): OutboundJobDao

    companion object {
        const val VERSION = 1

        private const val NAME = "scope_sms.db"

        /**
         * Builds the database. Call once per process — `AppContainer` holds it
         * lazily, which is what makes that true.
         *
         * No `allowMainThreadQueries`: every call site here is a suspend fun or a
         * Flow, and the receiver path must never block (CLAUDE.md constraint 5).
         */
        fun create(context: Context): ScopeSmsDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                ScopeSmsDatabase::class.java,
                NAME,
            ).build()
    }
}
