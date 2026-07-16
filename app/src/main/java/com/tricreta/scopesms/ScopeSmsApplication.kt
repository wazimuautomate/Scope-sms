package com.tricreta.scopesms

import android.app.Application
import com.tricreta.scopesms.di.AppContainer
import com.tricreta.scopesms.diagnostics.CrashReporter

/**
 * Process-level anchor for Scope SMS.
 *
 * Holds the object graph (manual DI — see [AppContainer] and memory.md). This
 * has to be process-scoped rather than owned by an Activity: an incoming M-Pesa
 * SMS wakes the manifest-registered receiver with no UI running at all, and that
 * receiver still needs the settings snapshot, the rule and template caches, and
 * the outbound send queue.
 *
 * `onCreate` stays cheap, which matters more than it looks: this runs on every
 * process start, including the headless ones an incoming SMS causes, and it sits
 * on the path CLAUDE.md constraint 5 asks to keep fast. [AppContainer] opens no
 * database here — its Room handle is lazy, and the collectors [AppContainer.start]
 * launches are on a background dispatcher.
 *
 * Reach the graph from a receiver or Activity with
 * [com.tricreta.scopesms.di.appContainer], not by casting this class by hand.
 */
class ScopeSmsApplication : Application() {

    /** Constructed eagerly, but every dependency inside it is lazy. */
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        // First, so a crash anywhere after this — including one while composing a
        // screen on a specific OEM device we can't reproduce off-device — leaves a
        // shareable stack trace instead of a silent force-close. Cheap: it only
        // installs a handler here and writes on an actual fatal crash.
        CrashReporter.install(this)
        container.start()
    }
}
