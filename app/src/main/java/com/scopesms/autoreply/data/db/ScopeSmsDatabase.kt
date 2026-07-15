package com.scopesms.autoreply.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.scopesms.autoreply.data.rules.PricingRuleDao
import com.scopesms.autoreply.data.rules.PricingRuleEntity
import com.scopesms.autoreply.data.templates.MessageTemplateDao
import com.scopesms.autoreply.data.templates.MessageTemplateEntity

/**
 * The app's single Room database. Created in Phase 3.
 *
 * ## Adding an entity (Phase 5b's `OutboundJob`, Phase 8's `ActivityLogEntry`)
 * This file is a known merge point for parallel sessions. To extend it:
 *
 * 1. Add the class to [Database.entities] and its DAO as an abstract method.
 * 2. Bump [Database.version].
 * 3. **Write a real [androidx.room.migration.Migration] and register it in
 *    [build].** Not optional — see below.
 * 4. Commit the regenerated JSON under `app/schemas`. It is the input to every
 *    future migration; without it Room cannot diff versions.
 *
 * ## No `fallbackToDestructiveMigration()` — ever
 * Its absence is the point, and it is why [build] will throw rather than
 * silently recover from a missing migration. This database holds the agent's
 * bundle prices and, from Phase 8, their activity history — a live record of
 * their business. Destructive migration wipes it on an app update. The agent
 * would open the app one morning to an empty price list and start replying to
 * customers by hand again, with nothing in the UI to explain why. A crash on a
 * developer's test device is a cheap, loud failure; that is a silent, expensive
 * one. `data/README.md` commits to this.
 */
@Database(
    entities = [
        PricingRuleEntity::class,
        MessageTemplateEntity::class,
    ],
    version = 1,
    // Writes app/schemas/<db>/<version>.json. Required for migrations; the
    // export path is configured in app/build.gradle.kts.
    exportSchema = true,
)
abstract class ScopeSmsDatabase : RoomDatabase() {

    abstract fun pricingRuleDao(): PricingRuleDao

    abstract fun messageTemplateDao(): MessageTemplateDao

    companion object {

        private const val NAME = "scope_sms.db"

        /**
         * Opens the database. Call once per process, from `di/AppContainer`.
         *
         * Nothing is passed to `allowMainThreadQueries()`: every caller here is
         * a coroutine on a background dispatcher, and the receive path doesn't
         * touch Room at all (CLAUDE.md constraint 5). If a future phase hits
         * Room's main-thread exception, the answer is to move that call off the
         * main thread, not to relax this.
         */
        fun build(context: Context): ScopeSmsDatabase =
            Room.databaseBuilder(context.applicationContext, ScopeSmsDatabase::class.java, NAME)
                .build()
    }
}
