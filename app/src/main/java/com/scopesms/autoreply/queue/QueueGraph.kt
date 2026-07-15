package com.scopesms.autoreply.queue

import android.content.Context

/**
 * How [SendJobWorker] reaches its [OutboundQueue].
 *
 * **This is not the project's DI decision, and deliberately so.** `di/README.md`
 * says the first phase that genuinely needs DI makes the call and records it in
 * `memory.md`, and points at Phase 3 as the likely owner — Phase 3/4 is being
 * built in parallel with this session and may well be deciding it right now.
 * Picking Hilt-or-manual here, from a phase that needs exactly one binding,
 * would pre-empt that with the narrowest possible view of the problem.
 *
 * So this is the smallest seam that satisfies the real constraint (`di/README.md`:
 * *"a BroadcastReceiver is constructed by the system, so the object graph must
 * be reachable from process scope"*) — which applies to WorkManager's workers
 * for the same reason. It is a single nullable slot, no framework, no ceremony.
 *
 * **To whoever owns the DI decision:** absorb this. Have the real container call
 * [install] in `ScopeSmsApplication.onCreate`, or replace it outright — nothing
 * else references it, and [SendJobWorker] is the only reader. It exists so
 * Phase 5b could ship without blocking on your choice, not to constrain it.
 */
object QueueGraph {

    @Volatile
    private var factory: ((Context) -> OutboundQueue)? = null

    /** Called once from `Application.onCreate` (or the DI container it builds). */
    fun install(factory: (Context) -> OutboundQueue) {
        this.factory = factory
    }

    /**
     * @return the queue, or `null` if nothing has been installed yet.
     *
     * Null is a real state, not a bug: an SMS can wake this process before the
     * wiring exists, and the worker treats null as "nothing to drain" rather
     * than crashing on the agent's phone. Once Phase 6/7 wires credentials in,
     * it is non-null for the process's life.
     */
    fun outboundQueue(context: Context): OutboundQueue? =
        factory?.invoke(context.applicationContext)

    /** Test hook. */
    internal fun reset() {
        factory = null
    }
}
