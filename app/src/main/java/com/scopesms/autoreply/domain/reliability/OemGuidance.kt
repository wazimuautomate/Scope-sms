package com.scopesms.autoreply.domain.reliability

/**
 * A way to jump straight to an OEM's autostart screen.
 *
 * Every one of these is a guess until proven on a real handset — they are
 * undocumented, vendor-private, and renamed between OS versions without notice.
 * `OemSettingsLauncher` therefore treats the whole list as disposable: it probes
 * each in turn and silently moves on. Nothing here is load-bearing; the written
 * [OemGuidance.steps] are.
 */
sealed interface OemDeepLink {

    /** An explicit `package/class` component. Breaks when the vendor renames it. */
    data class Component(val packageName: String, val className: String) : OemDeepLink

    /**
     * An intent action. Rarer, but survives class renames, so it's worth trying
     * between component guesses rather than after all of them.
     */
    data class Action(val action: String) : OemDeepLink
}

/**
 * The OEM families that kill background apps differently enough to need
 * different words.
 *
 * Grouped by the *skin*, not the brand: Oppo, Realme and OnePlus all run ColorOS
 * and share one set of screens, so they share one entry.
 */
enum class OemFamily {
    /** Tecno, Infinix, itel — HiOS/XOS/itel OS. This app's primary market. */
    TRANSSION,

    /** Xiaomi, Redmi, Poco, Black Shark — MIUI/HyperOS. */
    XIAOMI,

    /** Oppo, Realme, OnePlus — ColorOS. */
    COLOROS,

    /** Vivo, iQOO — Funtouch/OriginOS. */
    VIVO,

    /** Huawei, Honor — EMUI. */
    HUAWEI,

    /** Samsung — One UI. No autostart list; the problem is "sleeping apps". */
    SAMSUNG,

    /** Anything else, including stock Android, where the platform exemption is the whole story. */
    GENERIC,
}

/**
 * What to tell the agent, and where to send them, for their specific phone.
 *
 * @param settingsAppName the vendor's app in the agent's words ("Phone Master"),
 *   or null where the path lives in system Settings.
 * @param steps the manual path, in the words the OEM's own UI uses. **This is
 *   the real deliverable** — see [OemAutostartGuide].
 * @param caveat a known trap on this OEM that no deep link or exemption fixes.
 * @param deepLinks best-effort shortcuts, most-likely first.
 */
data class OemGuidance(
    val family: OemFamily,
    val settingsAppName: String?,
    val steps: List<String>,
    val caveat: String?,
    val deepLinks: List<OemDeepLink>,
)

/**
 * Per-OEM "keep this app alive" instructions.
 *
 * ### Why this is mostly prose and not code
 * BUILD-PLAN Phase 9 is blunt about it: *"no code fix solves this, only user
 * settings + clear instructions."* Transsion, Xiaomi and friends layer their own
 * autostart whitelists on top of Android's battery exemption, and **no API can
 * read or request them**. `BatteryOptimizationManager` says the same thing from
 * the other side: the platform exemption is necessary but not sufficient here.
 *
 * So the deep links below are a convenience, and the [OemGuidance.steps] are the
 * contract. That inversion drives the whole design: every link is probed before
 * use and discarded if absent, and the instructions render whether or not any
 * link resolves.
 *
 * ### Provenance — read before trusting the component names
 * The manual paths are from `dontkillmyapp.com` and match the OEMs' own UI
 * wording. The component names come from the battle-tested Android libraries
 * that maintain these lists (`judemanutd/AutoStarter`, `chris-wolf/
 * autostart_settings`, Threema, pano-scrobbler).
 *
 * **The Transsion entries are the weakest link and it is the OEM that matters
 * most.** They appear in ~12 repos, but those repos largely copy one another —
 * that is popularity, not independent confirmation — and the most-used library
 * of the set, `AutoStarter`, has no Transsion entry at all. No decompiled
 * manifest proving the activity exists and is exported could be found. They are
 * the best available guess and **must be confirmed on the agent's real Tecno/
 * Infinix device**; memory.md carries this as an open item. This is exactly why
 * the instructions, not the links, are the contract.
 */
object OemAutostartGuide {

    /**
     * Identify the phone from [brand] and [manufacturer] (`Build.BRAND`,
     * `Build.MANUFACTURER`).
     *
     * Both are matched, and that is deliberate rather than belt-and-braces:
     * Transsion devices report inconsistent `MANUFACTURER` values — a Tecno may
     * say "TECNO", "TECNO MOBILE LIMITED", or something else entirely — while
     * `BRAND` is the more reliable half. Matching the pair catches devices that
     * either field alone would miss.
     */
    fun detect(brand: String, manufacturer: String): OemFamily {
        val haystack = "$brand $manufacturer".lowercase()

        fun matches(vararg needles: String) = needles.any { it in haystack }

        return when {
            matches("transsion", "tecno", "infinix", "itel") -> OemFamily.TRANSSION
            matches("xiaomi", "redmi", "poco", "blackshark") -> OemFamily.XIAOMI
            matches("oppo", "realme", "oneplus") -> OemFamily.COLOROS
            matches("vivo", "iqoo") -> OemFamily.VIVO
            matches("huawei", "honor") -> OemFamily.HUAWEI
            matches("samsung") -> OemFamily.SAMSUNG
            else -> OemFamily.GENERIC
        }
    }

    /** Guidance for [family]. Total — every family has something useful to say. */
    fun guidanceFor(family: OemFamily): OemGuidance = when (family) {
        OemFamily.TRANSSION -> OemGuidance(
            family = family,
            settingsAppName = "Phone Master",
            steps = listOf(
                "Open the Phone Master app.",
                "Tap Toolbox, then Auto-start management.",
                "Find Scope SMS in the list and turn it on.",
                "Go back to Settings, tap Battery Lab, then Battery Saving Settings.",
                "Turn off Power Saving Management For Apps.",
                "In Recents, swipe up to find Scope SMS and tap the padlock icon to lock it.",
            ),
            caveat = "Tecno, Infinix and itel phones are the strictest of all. If replies " +
                "still stop overnight after these steps, open Phone Master > Power Marathon " +
                "and turn off Power Boost as well.",
            deepLinks = listOf(
                // Order is by evidence strength, not neatness. The first is the
                // one ~12 libraries agree on; the action-based second survives a
                // class rename, so it is tried before the remaining guesses; the
                // third is itel's separate app (different package entirely, and
                // independently attested by Threema); the last just opens Phone
                // Master so the agent can at least follow the steps by hand.
                OemDeepLink.Component("com.transsion.phonemaster", "com.cyin.himgr.autostart.AutoStartActivity"),
                OemDeepLink.Action("com.cyin.himgr.applicationmanager.view.activities.AUTO_START_ACTIVITY"),
                OemDeepLink.Component("com.transsion.phonemanager", "com.itel.autobootmanager.activity.AutoBootMgrActivity"),
                OemDeepLink.Component("com.cyin.himgr", "com.cyin.himgr.autostart.AutoStartActivity"),
                OemDeepLink.Component("com.transsion.phonemaster", "com.cyin.himgr.applicationmanager.view.activities.AutoStartActivity"),
                OemDeepLink.Component("com.transsion.phonemaster", "com.cyin.himgr.ads.SplashActivity"),
            ),
        )

        OemFamily.XIAOMI -> OemGuidance(
            family = family,
            settingsAppName = "Security",
            steps = listOf(
                "Open Settings, then Apps, then find Scope SMS.",
                "Tap App permissions, then turn on Background autostart.",
                "On older MIUI: open the Security app, tap Permissions, then Auto-start, " +
                    "and turn on Scope SMS.",
                "In the Security app, tap Battery, then App Battery Saver, then Scope SMS, " +
                    "and choose No restrictions.",
            ),
            caveat = null,
            deepLinks = listOf(
                OemDeepLink.Component("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
                OemDeepLink.Action("miui.intent.action.OP_AUTO_START"),
            ),
        )

        OemFamily.COLOROS -> OemGuidance(
            family = family,
            settingsAppName = null,
            steps = listOf(
                "Open Settings, then Battery, then Power saving settings.",
                "Tap App battery management, then find Scope SMS.",
                "Turn on Allow auto-launch and Allow background activity.",
            ),
            caveat = null,
            deepLinks = listOf(
                OemDeepLink.Component("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
                OemDeepLink.Component("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
                OemDeepLink.Component("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
                // OnePlus's own com.oneplus.security chain-launch screen is
                // deliberately absent: it is reported broken from Android 11
                // onward, which is this app's *minimum* version (minSdk 30), so
                // it could never work for a single user of this app. Modern
                // OnePlus runs ColorOS anyway and the entries above cover it.
            ),
        )

        OemFamily.VIVO -> OemGuidance(
            family = family,
            settingsAppName = "i Manager",
            steps = listOf(
                "Open Settings, then More settings, then Applications, then Autostart.",
                "Turn on Scope SMS.",
                "Go to Settings, then Battery, then Background power consumption management, " +
                    "and allow Scope SMS to run in the background.",
            ),
            caveat = null,
            deepLinks = listOf(
                OemDeepLink.Component("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
                OemDeepLink.Component("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
            ),
        )

        OemFamily.HUAWEI -> OemGuidance(
            family = family,
            settingsAppName = "Phone Manager",
            steps = listOf(
                "Open Settings, then Battery, then App launch.",
                "Find Scope SMS and switch it from Manage automatically to Manage manually.",
                "Turn on all three: Auto-launch, Secondary launch, and Run in background.",
                "In Settings > Battery, turn off Smart tune-up if you see it.",
            ),
            caveat = "Huawei phones from EMUI 9 onwards run a service called PowerGenie that " +
                "can stop apps even after these steps, and it has no user setting to turn it " +
                "off. If replies keep stopping on this phone, it may not be a reliable phone " +
                "to run Scope SMS on.",
            deepLinks = listOf(
                OemDeepLink.Component("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
                OemDeepLink.Component("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
            ),
        )

        OemFamily.SAMSUNG -> OemGuidance(
            family = family,
            settingsAppName = null,
            steps = listOf(
                "Open Settings, then Apps, then Scope SMS, then Battery.",
                "Choose Unrestricted.",
                "Go back to Settings, then Battery and device care, then Battery, " +
                    "then Background usage limits.",
                "Make sure Scope SMS is NOT in the Sleeping apps or Deep sleeping apps list. " +
                    "Remove it if it is there.",
                "Turn off Put unused apps to sleep.",
            ),
            caveat = "Samsung has no auto-start list. The setting that actually stops Scope SMS " +
                "is Sleeping apps — check that list even if everything else looks right.",
            deepLinks = listOf(
                // Samsung has no autostart screen at all; these open the battery
                // screen, which is the nearest thing that exists.
                OemDeepLink.Component("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
                OemDeepLink.Component("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"),
            ),
        )

        OemFamily.GENERIC -> OemGuidance(
            family = family,
            settingsAppName = null,
            steps = listOf(
                "Open Settings, then Apps, then Scope SMS.",
                "Tap Battery and choose Unrestricted (or Don't optimise).",
                "If your phone has its own battery or phone manager app, find its " +
                    "auto-start list and allow Scope SMS.",
            ),
            caveat = null,
            deepLinks = emptyList(),
        )
    }

    /** Convenience: detect and look up in one step. */
    fun guidanceFor(brand: String, manufacturer: String): OemGuidance =
        guidanceFor(detect(brand, manufacturer))
}
