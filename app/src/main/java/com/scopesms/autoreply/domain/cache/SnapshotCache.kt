package com.scopesms.autoreply.domain.cache

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A process-scoped, in-memory projection of something Room owns, read on the
 * SMS receive path.
 *
 * CLAUDE.md constraint 5: the detect-and-decide path is synchronous and must
 * absorb ~10 SMS in 1–3 seconds without touching disk. So the receiver reads a
 * cache, not the database. Room stays the source of truth; `di/AppContainer`
 * collects each repository's `Flow` and calls [publish] on every change.
 *
 * Subclassed by `RuleCache` and `TemplateCache` — the mechanics below are
 * fiddly enough (a first-load latch, a lock-free read path) that having one
 * copy of them, tested once, beats two that can drift apart.
 *
 * ## Why writers don't update the cache themselves
 * The obvious alternative — every repository write also pokes the cache — has a
 * failure mode this design doesn't: it relies on *every* future caller
 * remembering. One `INSERT` added in Phase 7 that forgets, and the agent edits a
 * bundle price while the receiver keeps quoting the old one at paying customers.
 * Driving the cache from Room's own invalidation means any write, from any
 * phase, through any DAO, lands here — including writes this class will never
 * hear about.
 *
 * ## Thread safety
 * Reads are lock-free: the snapshot reference is volatile (via
 * [MutableStateFlow]) and every snapshot is immutable, so a reader sees either
 * the old value or the new one, never a half-built one. Writes are rare (a human
 * typing into a settings screen); reads are on the hot path. The trade is the
 * right way round.
 *
 * @param Source what Room hands over — typically a list of domain rows.
 * @param Snapshot the indexed, immutable form the hot path reads.
 */
abstract class SnapshotCache<Source, Snapshot : Any> {

    private val state = MutableStateFlow<Snapshot?>(null)
    private val firstLoad = CompletableDeferred<Unit>()

    /** Indexes [source] into the form the receive path reads. Must be pure. */
    protected abstract fun buildSnapshot(source: Source): Snapshot

    /**
     * For the UI to observe. Null until the first load.
     *
     * Deliberately not the way to get a snapshot for a send decision — see
     * [awaitLoaded].
     */
    val snapshots: StateFlow<Snapshot?> = state.asStateFlow()

    /** The current snapshot, or null before the first load. UI/diagnostics only. */
    fun currentOrNull(): Snapshot? = state.value

    val isLoaded: Boolean get() = state.value != null

    /**
     * The snapshot to decide against, suspending until the first load lands.
     *
     * **This is the only way to obtain a snapshot for a send decision, and that
     * is the point.** An incoming SMS can start the process from cold: Android
     * constructs the Application, the receiver runs within milliseconds, and the
     * first Room read has not returned. A cache that answered "empty" during
     * that window would classify a perfectly good payment as unmatched and text
     * the customer a price list they never needed — the exact mistake this app
     * exists to stop the agent making by hand. Handing out a snapshot only once
     * one exists makes that window unrepresentable rather than merely
     * documented.
     *
     * This costs one Room read per *process start*, not per SMS, so constraint 5
     * still holds. Call it inside the receiver's `goAsync()` window.
     *
     * If Room cannot be read at all this never resumes, so the caller owns the
     * deadline: wrap it in `withTimeout` and treat expiry as a failure to log
     * loudly, not a message to drop. Phase 5b owns that path.
     */
    suspend fun awaitLoaded(): Snapshot {
        firstLoad.await()
        return checkNotNull(state.value) { "first load completed without publishing a snapshot" }
    }

    /**
     * Replaces the snapshot and releases anything waiting on [awaitLoaded].
     *
     * Called by the container's Room collector. Nothing else should call it —
     * a snapshot published from anywhere but Room is by definition not what Room
     * holds, which is the stale-cache bug this class exists to prevent.
     */
    fun publish(source: Source) {
        state.value = buildSnapshot(source)
        firstLoad.complete(Unit)
    }
}
