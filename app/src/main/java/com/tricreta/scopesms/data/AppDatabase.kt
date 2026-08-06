package com.tricreta.scopesms.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.tricreta.scopesms.data.log.ActivityLogDao
import com.tricreta.scopesms.data.log.ActivityLogEntity
import com.tricreta.scopesms.data.rules.PricingRuleDao
import com.tricreta.scopesms.data.rules.PricingRuleEntity
import com.tricreta.scopesms.data.templates.MessageTemplateDao
import com.tricreta.scopesms.data.templates.MessageTemplateEntity
import com.tricreta.scopesms.queue.OutboundJob
import com.tricreta.scopesms.queue.OutboundJobDao
import com.tricreta.scopesms.queue.OutboundJobStatus

/**
 * The app's Room database.
 *
 * All four entities live here: pricing rules (Phase 3), message templates
 * (Phase 4), the outbound send queue (Phase 5b) and the activity log (Phase 8).
 *
 * Phases 3/4, 5b and 8 were built in parallel and each wrote its own "the app's
 * database", each correctly reasoning it was first. They were consolidated into
 * this one during integration: two databases mean two SQLite files and two
 * connections, and no transaction could ever span a queue job and its log row.
 *
 * To add an entity:
 *  1. add it to `entities`,
 *  2. add its DAO accessor,
 *  3. bump [DB_VERSION] and add a `Migration` — see the migration note below,
 *  4. commit the regenerated `app/schemas/…json`.
 *
 * ---
 * ## Migrations are not optional
 *
 * `data/README.md`: *"Once the agent is running this on their live business, a
 * destructive migration throws away their bundle rules and activity history…
 * `fallbackToDestructiveMigration()` is not acceptable in a shipped build."*
 *
 * That call is deliberately absent below. Before v1.0 reaches the agent, a
 * destructive path would only cost a developer their test data — but this app
 * distributes as a direct-install APK the agent updates by hand, and the first
 * update that silently wiped their bundle prices would look exactly like the app
 * breaking. Schemas are exported from the first version (see `ksp { }` in
 * `app/build.gradle.kts`) so real migrations are writable when that day comes.
 */
@Database(
    entities = [
        // Phase 3 — the agent's bundle price list.
        PricingRuleEntity::class,
        // Phase 4 — the two reply templates.
        MessageTemplateEntity::class,
        // Phase 5b — the outbound send queue.
        OutboundJob::class,
        // Phase 8 — the activity log.
        ActivityLogEntity::class,
    ],
    version = AppDatabase.DB_VERSION,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    /** Phase 3. */
    abstract fun pricingRuleDao(): PricingRuleDao

    /** Phase 4. */
    abstract fun messageTemplateDao(): MessageTemplateDao

    /** Phase 5b. */
    abstract fun outboundJobDao(): OutboundJobDao

    /**
     * Phase 8.
     *
     * [ActivityLogEntity] stores its enums as plain `String` columns and converts
     * in `data/log/ActivityLogEntity.kt`, so it deliberately adds nothing to
     * [Converters] — see that file for why (an enum stored by ordinal silently
     * re-points historical rows when a constant is inserted mid-list).
     */
    abstract fun activityLogDao(): ActivityLogDao

    companion object {
        const val DB_VERSION = 5

        private const val DB_NAME = "scope-sms.db"

        /**
         * v1 → v2: adds the bundle [category] column (Phase: bundle categories).
         *
         * A plain nullable `ADD COLUMN` — no default. Existing bundle prices are
         * preserved untouched and left NULL, which the app reads as
         * [com.tricreta.scopesms.domain.rules.BundleCategory.DEFAULT]. Data-safe:
         * nothing is dropped or rewritten, which is the whole reason
         * `fallbackToDestructiveMigration()` is absent (see the class doc — the
         * agent runs this on their live business).
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE pricing_rules ADD COLUMN category TEXT")
            }
        }

        /**
         * v2 → v3: adds the bundle [com.tricreta.scopesms.domain.rules.PurchaseLimit]
         * column (once/day vs multiple/day).
         *
         * Same shape as [MIGRATION_1_2] and for the same reason: a plain
         * nullable `ADD COLUMN`, no default. Existing bundle prices are
         * preserved untouched and left NULL, which the app reads as
         * [com.tricreta.scopesms.domain.rules.PurchaseLimit.DEFAULT]
         * (unrestricted) — every bundle the agent already priced keeps
         * behaving exactly as it did before this column existed.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE pricing_rules ADD COLUMN purchaseLimit TEXT")
            }
        }

        /**
         * v3 → v4: two independent additions, bundled into one migration because
         * they landed in the same release.
         *
         * - `pricing_rules.windowStartMinute` / `windowEndMinute` — the bundle
         *   purchase-window feature (minutes since midnight; both NULL means "all
         *   day", same nullable/no-default shape as [MIGRATION_1_2]/[MIGRATION_2_3]
         *   and for the same reason: existing bundles are preserved untouched and
         *   read back as [com.tricreta.scopesms.domain.rules.PurchaseWindow.DEFAULT]
         *   (all day) until the agent edits one to say otherwise.
         * - `outbound_jobs.provider` — which SMS gateway
         *   ([com.tricreta.scopesms.network.GatewayProvider]) a queued job was
         *   created under, mirroring how `outbound_jobs.senderId` is already
         *   captured at enqueue time rather than read live: a job must send
         *   through the account its sender ID was registered with, even if the
         *   agent switches the active gateway in Settings while jobs are still
         *   queued. NULL (every job queued before this column existed) reads back
         *   as [com.tricreta.scopesms.network.GatewayProvider.BLAZETECH] — the
         *   only gateway that existed before this release.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE pricing_rules ADD COLUMN windowStartMinute INTEGER")
                db.execSQL("ALTER TABLE pricing_rules ADD COLUMN windowEndMinute INTEGER")
                db.execSQL("ALTER TABLE outbound_jobs ADD COLUMN provider TEXT")
            }
        }

        /**
         * v4 → v5: `activity_log.provider` — which
         * [com.tricreta.scopesms.network.GatewayProvider] a logged payment's
         * reply actually went out through, so the activity log's "check status"
         * action (HostPinnacle's `reports/status` lookup) knows which gateway to
         * ask. Same nullable/no-default shape as every migration above: every
         * row logged before this column existed reads back as null, which the
         * UI treats as "assume BlazeTech" — the only gateway that could have
         * sent it — same fallback [com.tricreta.scopesms.queue.OutboundJob.provider]
         * already uses.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE activity_log ADD COLUMN provider TEXT")
            }
        }

        @Volatile
        private var instance: AppDatabase? = null

        /**
         * Process-wide singleton.
         *
         * Room is expensive to open and an incoming SMS can start this process
         * cold, so opening it twice would be paid on exactly the path CLAUDE.md
         * constraint 5 wants fast. Double-checked locking rather than `by lazy`
         * because the receiver and the worker can race on first access.
         */
        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }

        private fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, DB_NAME)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                // No fallbackToDestructiveMigration() — see the class doc.
                .build()
    }
}

/**
 * Room maps enums natively, but the mapping is stored by `name`, which quietly
 * couples the database's contents to Kotlin identifiers. Declaring it here makes
 * that explicit: renaming an [OutboundJobStatus] constant is a schema change
 * requiring a migration, not a refactor.
 */
class Converters {

    @TypeConverter
    fun toOutboundJobStatus(value: String): OutboundJobStatus = OutboundJobStatus.valueOf(value)

    @TypeConverter
    fun fromOutboundJobStatus(status: OutboundJobStatus): String = status.name
}
