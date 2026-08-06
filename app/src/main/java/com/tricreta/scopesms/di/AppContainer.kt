package com.tricreta.scopesms.di

import android.content.Context
import android.util.Log
import com.tricreta.scopesms.BuildConfig
import com.tricreta.scopesms.ScopeSmsApplication
import com.tricreta.scopesms.data.AppDatabase
import com.tricreta.scopesms.data.log.ActivityLogRepository
import com.tricreta.scopesms.data.rules.RoomPricingRuleRepository
import com.tricreta.scopesms.data.settings.GatewayCredentialsStore
import com.tricreta.scopesms.data.settings.SettingsRepository
import com.tricreta.scopesms.data.system.AppReset
import com.tricreta.scopesms.data.system.BatteryOptimizationManager
import com.tricreta.scopesms.data.templates.RoomMessageTemplateRepository
import com.tricreta.scopesms.domain.rules.PricingRuleRepository
import com.tricreta.scopesms.domain.rules.RuleCache
import com.tricreta.scopesms.domain.templates.MessageTemplateRepository
import com.tricreta.scopesms.domain.templates.TemplateCache
import com.tricreta.scopesms.network.BlazeTechGateway
import com.tricreta.scopesms.network.GatewayProvider
import com.tricreta.scopesms.network.GatewayRegistry
import com.tricreta.scopesms.network.HostPinnacleGateway
import com.tricreta.scopesms.network.SmsGateway
import com.tricreta.scopesms.update.AppUpdater
import com.tricreta.scopesms.queue.OutboundLog
import com.tricreta.scopesms.queue.OutboundQueue
import com.tricreta.scopesms.queue.RoomOutboundJobStore
import com.tricreta.scopesms.queue.SendJobWorker
import com.tricreta.scopesms.queue.SendResultListener
import com.tricreta.scopesms.reliability.OemSettingsLauncher
import com.tricreta.scopesms.reliability.ReliabilityInspector
import com.tricreta.scopesms.reliability.ReliabilityNotifier
import com.tricreta.scopesms.reliability.WatchingNotification
import com.tricreta.scopesms.telephony.AndroidSimReader
import com.tricreta.scopesms.telephony.PaymentPipeline
import com.tricreta.scopesms.telephony.SimReader
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch

/**
 * The app's object graph: manual DI, resolved at process scope.
 *
 * ## Manual DI, not Hilt — settled, do not relitigate
 * Phase 0 left this to the first phase that needed wiring; Phases 1 and 3 both
 * arrived at manual independently, and `memory.md` records it as closed.
 *
 * 1. The graph is a handful of process-scoped singletons and will stay that way.
 * 2. Hilt needs KSP, and CI is this project's only compiler (CLAUDE.md
 *    constraint 8) — every annotation processor is a per-push cost and one more
 *    failure mode nobody can reproduce locally. Room already forces KSP; that
 *    one is unavoidable, this one isn't.
 * 3. The awkward consumer is a `BroadcastReceiver`, which Android constructs
 *    itself. `@AndroidEntryPoint` would hide the process-scope lookup, not
 *    remove it.
 *
 * ## Everything here is lazy
 * This is constructed on **every** process start, including the headless ones an
 * incoming SMS causes at 2am. CLAUDE.md constraint 5 wants that path fast, so
 * construction itself must stay near-free: nothing below is built until
 * something asks for it, and no field does I/O to be created.
 *
 * Two rules for anything added here: stay `by lazy`, and never hold an Activity
 * `Context`.
 */
class AppContainer(context: Context) {

    /** Held so nothing can leak an Activity into a process-scoped singleton. */
    private val appContext: Context = context.applicationContext

    /**
     * For work that must outlive any screen — keeping the settings snapshot
     * warm, mirroring Room into the caches, and draining the outbound queue.
     *
     * `SupervisorJob` so one failing child can't cancel the scope and take
     * ingestion with it: a broken template collector shouldn't also blind the
     * rule cache. Never cancelled — its lifetime is the process's.
     */
    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ---- Phase 1: settings, SIM, battery ------------------------------------

    val settings: SettingsRepository by lazy { SettingsRepository.create(appContext) }

    val simReader: SimReader by lazy { AndroidSimReader(appContext) }

    val batteryOptimization: BatteryOptimizationManager by lazy {
        BatteryOptimizationManager(appContext)
    }

    /** The "reset everything" action for Settings. See [AppReset]. */
    val appReset: AppReset by lazy { AppReset(appContext) }

    // ---- Phases 3/4/5b/8: the one Room database ----------------------------

    /**
     * Lazy so that constructing the container — which happens in
     * `Application.onCreate`, on the main thread, on every process start
     * including headless SMS wakeups — doesn't open SQLite. The first touch
     * comes from [start]'s collectors, already on [Dispatchers.IO].
     *
     * [AppDatabase.get] has its own double-checked locking because the queue
     * worker and the receiver can race on first access; this `by lazy` is about
     * not touching SQLite at all until something asks.
     */
    val database: AppDatabase by lazy { AppDatabase.get(appContext) }

    /** Phase 8 — the activity log. */
    val activityLog: ActivityLogRepository by lazy {
        ActivityLogRepository(database.activityLogDao())
    }

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

    // ---- Phase 5/5b: gateway credentials, client, outbound queue ------------

    /**
     * Encrypted at rest via an Android Keystore AES/GCM key — this is the
     * resolution of `memory.md` open decision 1. See the class for why
     * `EncryptedSharedPreferences` was rejected.
     */
    val gatewayCredentials: GatewayCredentialsStore by lazy {
        GatewayCredentialsStore.create(appContext)
    }

    /**
     * Each gateway client is built with credentials scoped to its OWN provider
     * ([GatewayCredentialsStore.scopedTo]) — it never needs to ask "which
     * provider is active", only "what's my own key". See [gatewayRegistry] for
     * the piece that answers "which one do I use for this job".
     */
    val blazeTechGateway: SmsGateway by lazy {
        BlazeTechGateway.create(gatewayCredentials.scopedTo(GatewayProvider.BLAZETECH))
    }

    val hostPinnacleGateway: SmsGateway by lazy {
        HostPinnacleGateway.create(gatewayCredentials.scopedTo(GatewayProvider.HOSTPINNACLE))
    }

    /**
     * Resolves a [GatewayProvider] — a job's own captured one, or the agent's
     * currently-active choice for a Settings test send — to the client that
     * actually sends it.
     */
    val gatewayRegistry: GatewayRegistry by lazy { GatewayRegistry(blazeTechGateway, hostPinnacleGateway) }

    val outboundQueue: OutboundQueue by lazy {
        OutboundQueue(
            store = RoomOutboundJobStore(database.outboundJobDao()),
            gateways = gatewayRegistry,
            // Closes Phase 5's open loop: the queue knew a send's outcome but had
            // nowhere to report it, so a failed reply updated a job row the agent
            // never sees. Now it lands in the activity log, which is the one place
            // BUILD-PLAN Phase 8 says they look.
            results = activityLogSink,
            // Field diagnosis: log every send attempt and its gateway reply so a
            // "why didn't it send" can be answered from logcat (2026-07-19).
            log = outboundLog,
        )
    }

    /**
     * Writes the send path's events to logcat. Masks the phone; never touches the
     * message body or API key (both sensitive — logcat is scooped up by bug
     * reports). See [OutboundLog].
     */
    private val outboundLog: OutboundLog by lazy {
        object : OutboundLog {
            override fun sending(transactionCode: String, phone: String, senderId: String) {
                Log.i(SEND_TAG, "Sending $transactionCode to ${maskPhone(phone)} as \"$senderId\"")
            }

            override fun sent(transactionCode: String, messageId: String) {
                val id = if (messageId.isBlank()) "no id yet" else "id $messageId"
                Log.i(SEND_TAG, "SENT $transactionCode ($id)")
            }

            override fun failed(transactionCode: String, reason: String) {
                Log.w(SEND_TAG, "FAILED $transactionCode: $reason")
            }
        }
    }

    /** Adapts the activity log to the queue's port. */
    private val activityLogSink: SendResultListener by lazy {
        object : SendResultListener {
            override suspend fun onSent(transactionCode: String, gatewayMessageId: String?) {
                activityLog.markSent(transactionCode, gatewayMessageId)
            }

            override suspend fun onFailed(transactionCode: String, reason: String) {
                activityLog.markFailed(transactionCode, reason)
            }
        }
    }

    /**
     * The in-app updater: reads update.json, downloads + verifies the APK, and
     * hands it to the system installer. Called from Settings on demand only —
     * the agent's data is metered, so nothing here polls in the background.
     *
     * Holds only [appContext] and stays `by lazy`, per the container's two rules.
     */
    val appUpdater: AppUpdater by lazy {
        AppUpdater.create(
            context = appContext,
            manifestUrl = BuildConfig.UPDATE_MANIFEST_URL,
            // Read-only GitHub token, baked in from a build secret (never
            // committed). Empty in a build without the secret → the updater
            // reports "not configured" rather than erroring.
            readToken = BuildConfig.UPDATE_READ_TOKEN,
            installedPackage = BuildConfig.APPLICATION_ID,
            installedVersionCode = BuildConfig.VERSION_CODE.toLong(),
        )
    }

    /**
     * The decide path the SMS receiver calls. Phases 2→3→4→6→5b→8, joined.
     */
    val paymentPipeline: PaymentPipeline by lazy {
        PaymentPipeline(
            ruleCache = ruleCache,
            templateCache = templateCache,
            settings = settings,
            activityLog = activityLog,
            queue = outboundQueue,
            credentials = gatewayCredentials,
            requestDrain = { SendJobWorker.enqueueDrain(appContext) },
        )
    }

    /**
     * Starts the background work the graph owns. Call once, from
     * `Application.onCreate`.
     *
     * Returns immediately; the first cache snapshot lands a few milliseconds
     * later. Anything deciding whether to reply must therefore await
     * [com.tricreta.scopesms.domain.cache.SnapshotCache.awaitLoaded] rather
     * than read whatever happens to be there — see that method for why.
     */
    fun start() {
        // Warms the SIM-selection snapshot so the receiver's filter answers from
        // memory instead of falling back to a disk read (constraint 5). Kicking
        // it off here means the read overlaps with the receiver's own startup
        // rather than landing in front of the first SMS of a burst.
        settings.simSelection.launchIn(applicationScope)

        // Same reasoning for the trusted-senders whitelist: the receiver's
        // sender check reads this synchronously too, so it must be warm before
        // the first SMS of a burst arrives rather than fall back to disk.
        settings.trustedSenders.launchIn(applicationScope)

        // Same reasoning again for the active gateway: PaymentPipeline reads it
        // synchronously on the async decide-and-enqueue path (once per payment,
        // same category of read as the sender ID) to choose which provider's
        // credentials to resolve and which gateway a job is queued under.
        settings.activeGatewayProvider.launchIn(applicationScope)

        applicationScope.launch {
            keepInSync("rules", pricingRuleRepository.observeAll(), ruleCache::publish)
        }
        applicationScope.launch {
            keepInSync("templates", messageTemplateRepository.observeAll(), templateCache::publish)
        }

        // Reassure the agent the app is on watch. Cheap and idempotent; re-posted
        // on every start and boot. Not a foreground service — see the class.
        watchingNotification.show()

        // Anything a previous process left PENDING — queued while the phone had
        // no data, or stranded when the process died mid-drain — goes out now.
        // WorkManager's CONNECTED constraint holds it until there's a network,
        // so this is safe to request on every start, including offline ones.
        //
        // On the background scope, never inline, for two reasons that both bite
        // in production:
        //  1. `WorkManager.enqueue` writes to its own database. `start()` is
        //     called from `Application.onCreate`, on the main thread, on every
        //     process start including the headless ones an incoming SMS causes —
        //     CLAUDE.md constraint 5 keeps disk I/O off exactly that path.
        //  2. It can throw when WorkManager's initializer hasn't run — see
        //     `SendJobWorker.enqueueDrain`, which owns that guard so no caller
        //     has to remember it.
        applicationScope.launch { SendJobWorker.enqueueDrain(appContext) }

        // A periodic safety net so a drain still runs when no new SMS arrives to
        // trigger one. On the stricter background limits of newer One UI a worker
        // can be killed mid-send or its KEEP-dropped re-trigger lost, stranding a
        // job on "Sending…" until the next payment or an app restart. This runs a
        // drain about every 15 minutes regardless, reclaiming any such job. See
        // SendJobWorker.enqueuePeriodicDrain.
        applicationScope.launch { SendJobWorker.enqueuePeriodicDrain(appContext) }
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
     * their own.
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
                // belongs in logcat, which bug reports scoop up wholesale.
                Log.e(TAG, "$label cache sync failed (attempt $attempt), retrying", cause)
                delay(retryDelayMs(attempt))
                true
            }
            .collect { publish(it) }
    }

    private fun retryDelayMs(attempt: Long): Long =
        min(BASE_RETRY_DELAY_MS shl attempt.coerceAtMost(POW_CAP).toInt(), MAX_RETRY_DELAY_MS)

    // ---- Phase 9: reliability hardening ------------------------------------

    val reliabilityInspector: ReliabilityInspector by lazy {
        ReliabilityInspector(appContext, settings, simReader, batteryOptimization)
    }

    val reliabilityNotifier: ReliabilityNotifier by lazy { ReliabilityNotifier(appContext) }

    /**
     * Resolved lazily like everything else, which matters more here than it
     * looks: this one queries the PackageManager on construction of its intent
     * list, and `BootCompletedReceiver` never touches it. Building it eagerly
     * would put a package lookup on every headless process start.
     */
    val oemSettingsLauncher: OemSettingsLauncher by lazy { OemSettingsLauncher(appContext) }

    /** The quiet ongoing "watching" notification the agent asked to see. */
    val watchingNotification: WatchingNotification by lazy { WatchingNotification(appContext) }

    companion object {
        private const val TAG = "ScopeSms/Container"
        private const val BASE_RETRY_DELAY_MS = 250L
        private const val MAX_RETRY_DELAY_MS = 60_000L

        /** Keeps the shift from overflowing before `min` can cap it. */
        private const val POW_CAP = 16L

        /** logcat tag for the outbound send path. */
        private const val SEND_TAG = "ScopeSms/Send"

        /** Last 3 digits only — enough to correlate in a log, never enough to identify. */
        private fun maskPhone(phone: String): String =
            if (phone.length <= 3) "***" else "***" + phone.takeLast(3)

        /**
         * Reaches the container from anywhere holding a `Context` — including a
         * `BroadcastReceiver`, which the system constructs and hands nothing.
         *
         * Throws rather than falling back if the Application isn't ours. That
         * only happens if `android:name` is dropped from the manifest, which
         * would silently break ingestion at 2am; failing loudly on the next
         * launch is the kinder outcome.
         */
        fun from(context: Context): AppContainer {
            val app = context.applicationContext
            check(app is ScopeSmsApplication) {
                "Expected ScopeSmsApplication but was ${app.javaClass.name}. " +
                    "Check android:name on <application> in AndroidManifest.xml."
            }
            return app.container
        }
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
    get() = AppContainer.from(this)
