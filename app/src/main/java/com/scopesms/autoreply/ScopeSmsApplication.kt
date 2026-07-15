package com.scopesms.autoreply

import android.app.Application
import com.scopesms.autoreply.di.AppContainer

/**
 * Process-level anchor for Scope SMS.
 *
 * Phase 3 attached the object graph here, as Phase 0 anticipated. The reason
 * this has to be process-scoped rather than owned by an Activity: an incoming
 * M-Pesa SMS wakes the manifest-registered receiver with no UI running at all,
 * and that receiver still needs the rule cache, the template cache and (from
 * Phase 5b) the send queue.
 *
 * `onCreate` stays cheap, which matters more than it looks: this runs on every
 * process start, including the headless ones an incoming SMS causes, and it is
 * on the path CLAUDE.md constraint 5 asks to keep fast. [AppContainer] opens no
 * database here — its Room handle is lazy and the collectors it starts are on a
 * background dispatcher.
 *
 * Reach the graph from a receiver or Activity with
 * [com.scopesms.autoreply.di.appContainer], not by casting this class by hand.
 */
class ScopeSmsApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.start()
    }
}
