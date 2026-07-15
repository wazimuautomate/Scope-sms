package com.scopesms.autoreply.di

import android.content.Context
import android.util.Log
import com.scopesms.autoreply.ScopeSmsApplication
import com.scopesms.autoreply.data.db.ScopeSmsDatabase
import com.scopesms.autoreply.data.rules.RoomPricingRuleRepository
import com.scopesms.autoreply.data.templates.RoomMessageTemplateRepository
import com.scopesms.autoreply.domain.rules.PricingRuleRepository
import com.scopesms.autoreply.domain.rules.RuleCache
import com.scopesms.autoreply.domain.templates.MessageTemplateRepository
import com.scopesms.autoreply.domain.templates.TemplateCache
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch

/**
 * The app's object graph, built once per process and hung off
 * [ScopeSmsApplication].
 *
 * ## Manual DI, not Hilt — the decision `memory.md` left to Phase 3
 * Phase 0 deliberately established neither and left the call to the first phase
 * that actually needed wiring. This is it, and the answer is manual, for three
 * reasons:
 *
 * 1. **The graph is five singletons.** A database, two repositories, two caches,
 *    all process-scoped, none with variants. Hilt's value shows up on graphs
 *    with scopes, qualifiers and test doubles to swap; there is none of that
 *    here, and the container below is short enough to read in one sitting.
 * 2. **The awkward consumer is a `BroadcastReceiver`**, which Android
 *    constructs itself. Hilt handles that with `@AndroidEntryPoint`, but manual
 *    DI handles it by reading a field off the Application — and the second needs
 *    no annotation processor, no generated code, and no explanation to whoever
 *    debugs a cold-start bug at 2am.
 * 3. **Build risk is real on this project.** There is no local Android Studio
 *    (CLAUDE.md constraint 8), so every mistake costs a CI round trip. Room
 *    already brings KSP; adding Hilt would add a second processor plus a Gradle
 *    plugin whose compatibility with AGP 9's built-in Kotlin nobody here has
 *    verified. That's a real cost against a benefit this graph doesn't collect.
 *
 * **This is now settled — `di/README.md` said the first decider settles it. Do
 * not relitigate per phase.** If the graph grows scopes and swappable
 * implementations later, revisit it deliberately and record why in `memory.md`.
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    /**
     * Survives for the process's lifetime, which is the point: it owns the cache
     * collectors that must stay live for a headless SMS wakeup.
     *
     * `SupervisorJob` so one collector failing can't take the other down with
     * it — a broken template cache shouldn't also blind the rule cache.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Lazy so that constructing the container — which happens in
     * `Application.onCreate`, on the main thread, on every process start
     * including headless SMS wakeups — doesn't open SQLite. The first touch
     * comes from [start]'s collectors, already on [Dispatchers.IO].
     */
    val database: ScopeSmsDatabase by lazy { ScopeSmsDatabase.build(appContext) }

    val pricingRuleRepository: PricingRuleRepository by lazy {
        RoomPricingRuleRepository(database.pricingRuleDao())
    }

    val messageTemplateRepository: MessageTemplateRepository by lazy {
        RoomMessageTemplateRepository(database.messageTemplateDao())
    }

    /** Read by the SMS receive path. Kept current by [start]. */
    val ruleCache = RuleCache()

    /** Read by the SMS receive path. Kept current by [start]. */
    val templateCache = TemplateCache()

    /**
     * Starts mirroring Room into the caches. Call once, from
     * `Application.onCreate`.
     *
     * Returns immediately; the first snapshot lands a few milliseconds later.
     * Anything deciding whether to reply must therefore await
     * [com.scopesms.autoreply.domain.cache.SnapshotCache.awaitLoaded] rather
     * than read whatever is there — see that method for why.
     */
    fun start() {
        scope.launch { keepInSync("rules", pricingRuleRepository.observeAll(), ruleCache::publish) }
        scope.launch {
            keepInSync("templates", messageTemplateRepository.observeAll(), templateCache::publish)
        }
    }

    /**
     * Collects [source] into [publish] forever, retrying on failure.
     *
     * **Retries without limit, on purpose.** If this collector dies, the cache
     * it feeds never loads, `awaitLoaded()` never resumes, and every incoming
     * payment silently fails to get a reply — the app looks alive while doing
     * nothing, which CLAUDE.md constraint 9 calls out as the outcome to avoid.
     * Giving up after N attempts would make that state permanent until the agent
     * happens to reboot their phone. The realistic causes (disk pressure on a
     * cheap handset, a transient SQLite lock) are exactly the kind that clear on
     * their own, so backing off and trying again is both cheaper and more likely
     * to recover than any alternative available here.
     *
     * Backoff caps at [MAX_RETRY_DELAY_MS] so a long-running failure costs
     * roughly one wakeup a minute rather than a spin loop on the agent's
     * battery.
     */
    private suspend fun <T> keepInSync(label: String, source: Flow<T>, publish: (T) -> Unit) {
        source
            .retryWhen { cause, attempt ->
                // The throwable only — never the collected rows. Template bodies
                // are the agent's words and rules are their pricing; neither
                // belongs in logcat, which any app on the device could once read
                // and which bug reports scoop up wholesale.
                Log.e(TAG, "$label cache sync failed (attempt $attempt), retrying", cause)
                delay(retryDelayMs(attempt))
                true
            }
            .collect { publish(it) }
    }

    private fun retryDelayMs(attempt: Long): Long =
        min(BASE_RETRY_DELAY_MS shl attempt.coerceAtMost(POW_CAP).toInt(), MAX_RETRY_DELAY_MS)

    private companion object {
        const val TAG = "ScopeSms/Container"
        const val BASE_RETRY_DELAY_MS = 250L
        const val MAX_RETRY_DELAY_MS = 60_000L

        /** Keeps the shift from overflowing before [min] can cap it. */
        const val POW_CAP = 16L
    }
}

/**
 * The container, from anywhere holding a [Context] — including a
 * `BroadcastReceiver`, which Android constructs with no chance to inject it.
 *
 * ```
 * // In a receiver:
 * val container = context.appContainer
 * val snapshot = withTimeout(5_000) { container.ruleCache.awaitLoaded() }
 * ```
 */
val Context.appContainer: AppContainer
    get() = (applicationContext as ScopeSmsApplication).container
