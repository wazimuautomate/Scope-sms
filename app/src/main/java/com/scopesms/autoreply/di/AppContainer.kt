package com.scopesms.autoreply.di

import android.content.Context
import com.scopesms.autoreply.ScopeSmsApplication
import com.scopesms.autoreply.data.settings.SettingsRepository
import com.scopesms.autoreply.data.system.BatteryOptimizationManager
import com.scopesms.autoreply.reliability.OemSettingsLauncher
import com.scopesms.autoreply.reliability.ReliabilityInspector
import com.scopesms.autoreply.reliability.ReliabilityNotifier
import com.scopesms.autoreply.telephony.AndroidSimReader
import com.scopesms.autoreply.telephony.SimReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The app's object graph: manual DI, resolved at process scope.
 *
 * ### The decision (di/README.md said the first phase that needs DI makes it)
 * **Manual DI, not Hilt.** Phase 1 is the first phase with anything to wire, so
 * per `memory.md` this is now settled — don't relitigate it per phase.
 *
 * Reasons, in order of weight:
 * 1. The graph is four singletons and will realistically end at a dozen. Hilt's
 *    ceremony buys nothing at that size.
 * 2. Hilt needs KSP. This project's only compiler is a GitHub Actions runner
 *    (CLAUDE.md constraint 8), so every annotation processor is a build-time
 *    cost paid on every push and one more thing that can break a build nobody
 *    can reproduce locally. Room already forces KSP on us in Phase 3; that one
 *    is unavoidable, this one isn't.
 * 3. The awkward constraint `di/README.md` names — a `BroadcastReceiver` is
 *    constructed by the system, so the graph must be reachable from process
 *    scope — is solved the same way either way ([from]). Hilt's
 *    `@AndroidEntryPoint` would hide that lookup, not remove it.
 *
 * Revisit only if the graph grows scopes (per-Activity, per-worker) that make
 * hand-wiring genuinely error-prone. Log it in memory.md if so.
 *
 * ### Everything here is lazy
 * This is constructed on **every** process start, including the headless ones
 * an incoming SMS causes at 2am. CLAUDE.md constraint 5 wants that path fast,
 * so construction itself must stay near-free: nothing below is built until
 * something asks for it, and no field does I/O to be created.
 */
class AppContainer(context: Context) {

    /** Held so nothing can leak an Activity into a process-scoped singleton. */
    private val appContext: Context = context.applicationContext

    /**
     * For work that must outlive any screen — keeping the settings cache warm,
     * and later draining the outbound queue.
     *
     * `SupervisorJob` so one failing child can't cancel the scope and take
     * ingestion with it. Never cancelled: its lifetime is the process's.
     */
    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings: SettingsRepository by lazy { SettingsRepository.create(appContext) }

    val simReader: SimReader by lazy { AndroidSimReader(appContext) }

    val batteryOptimization: BatteryOptimizationManager by lazy {
        BatteryOptimizationManager(appContext)
    }

    // --- Phase 9: reliability hardening ------------------------------------

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

    companion object {
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
