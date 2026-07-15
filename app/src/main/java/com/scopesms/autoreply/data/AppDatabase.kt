package com.scopesms.autoreply.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.scopesms.autoreply.queue.OutboundJob
import com.scopesms.autoreply.queue.OutboundJobDao
import com.scopesms.autoreply.queue.OutboundJobStatus

/**
 * The app's Room database.
 *
 * ---
 * ## 🔶 Shared file — read before editing (Phases 3, 4, 8)
 *
 * `data/README.md` assigns this database four owners: Phase 3 (`PricingRule`),
 * Phase 4 (`MessageTemplate`), Phase 5b (`OutboundJob`, below) and Phase 8
 * (`ActivityLogEntry`). Phase 5b created the file because it was the first to
 * need Room and none of the others had committed anything yet — **not** because
 * it owns it.
 *
 * To add your entity:
 *  1. add it to `entities`,
 *  2. add its DAO accessor,
 *  3. bump [DB_VERSION] and add a `Migration` — see the migration note below,
 *  4. commit the regenerated `app/schemas/…json`.
 *
 * If you hit this as a git add/add conflict: keep both entity lists, keep both
 * DAO accessors. Nothing here is Phase-5b-specific beyond the `OutboundJob`
 * lines.
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
        // Phase 5b — the outbound send queue.
        OutboundJob::class,
        // Phase 3 — PricingRule::class
        // Phase 4 — MessageTemplate::class
        // Phase 8 — ActivityLogEntry::class
    ],
    version = AppDatabase.DB_VERSION,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun outboundJobDao(): OutboundJobDao

    companion object {
        const val DB_VERSION = 1

        private const val DB_NAME = "scope-sms.db"

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
