package com.scopesms.autoreply

import android.app.Application
import com.scopesms.autoreply.di.AppContainer
import kotlinx.coroutines.flow.launchIn

/**
 * Process-level anchor for Scope SMS.
 *
 * Holds the DI container (manual DI — decided in Phase 1, see
 * [AppContainer] and memory.md), which later phases hang the rule/template
 * cache and the outbound queue off.
 *
 * `onCreate` must stay cheap. This runs on every process start, including the
 * headless ones caused by an incoming SMS waking the manifest receiver — so
 * anything slow added here is paid on the hot path CLAUDE.md constraint 5 asks
 * to keep fast.
 */
class ScopeSmsApplication : Application() {

    /** Constructed eagerly, but every dependency inside it is lazy. */
    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        warmSettingsCache()
    }

    /**
     * Starts collecting the SIM selection so its in-memory snapshot is
     * populated.
     *
     * Not a preload for its own sake. The Phase 2 receiver's SIM filter needs
     * the agent's choice synchronously (CLAUDE.md constraint 5), and this is
     * what makes `SettingsRepository.currentSimSelection()` answer from memory
     * instead of falling back to a disk read. Kicking it off here means the
     * read overlaps with the receiver's own startup rather than landing in
     * front of the first SMS of a burst.
     *
     * Cheap and non-blocking: a Flow collection launched on a background
     * dispatcher, not an `onCreate` that waits for the disk.
     */
    private fun warmSettingsCache() {
        container.settings.simSelection.launchIn(container.applicationScope)
    }
}
