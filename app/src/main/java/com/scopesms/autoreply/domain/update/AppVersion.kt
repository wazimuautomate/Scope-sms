package com.scopesms.autoreply.domain.update

/**
 * A semantic version, for comparing what's installed against what GitHub offers.
 *
 * Pure and JVM-tested. The comparison is the whole feature: get it wrong in one
 * direction and the agent is nagged forever by an update that is already
 * installed; wrong in the other and they never hear about a fix.
 */
data class AppVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
) : Comparable<AppVersion> {

    override fun compareTo(other: AppVersion): Int = compareValuesBy(
        this,
        other,
        AppVersion::major,
        AppVersion::minor,
        AppVersion::patch,
    )

    override fun toString(): String = "$major.$minor.$patch"

    companion object {

        /**
         * Parses `1.2.3`, `v1.2.3`, or `1.2.3-rc1`, or returns null.
         *
         * Tolerates the `v` prefix because that is how the git tag and the
         * GitHub Release name are written, while `BuildConfig.VERSION_NAME` has
         * no prefix — this is the one place those two conventions meet.
         *
         * A pre-release suffix is **parsed and then ignored**, deliberately.
         * Ordering `1.0.0-rc1` against `1.0.0` correctly needs the full semver
         * pre-release precedence rules, and this app has no use for them: it
         * ships one build to one agent. Treating them as equal means a `-rc`
         * build never nags about the release of the same number, which is the
         * safe direction to be wrong in.
         *
         * Returns null rather than guessing. A malformed tag must mean "no
         * update offered", never "an update to version 0".
         */
        fun parse(raw: String?): AppVersion? {
            val text = raw?.trim()?.removePrefix("v")?.removePrefix("V") ?: return null
            val match = PATTERN.matchEntire(text) ?: return null
            val (major, minor, patch) = match.destructured

            return AppVersion(
                major = major.toIntOrNull() ?: return null,
                minor = minor.toIntOrNull() ?: return null,
                patch = patch.toIntOrNull() ?: return null,
            )
        }

        /** `1.2.3` with an optional `-suffix` / `+build` that is matched but discarded. */
        private val PATTERN = Regex("""(\d+)\.(\d+)\.(\d+)(?:[-+].*)?""")
    }
}

/**
 * What the update check concluded.
 *
 * Every arm is silent except [Available]. An update check that interrupts the
 * agent to say "you're up to date" is an update check they will learn to
 * dismiss, and this app already asks for their attention when a send fails —
 * which actually matters.
 */
sealed interface UpdateStatus {

    /** Newest release is the installed one, or older. Say nothing. */
    data object UpToDate : UpdateStatus

    data class Available(
        val version: AppVersion,
        /** The GitHub Release page. Opened in a browser — never auto-installed. */
        val url: String,
        val notes: String?,
    ) : UpdateStatus

    /**
     * Couldn't tell — offline, rate-limited, no releases yet, unparseable tag.
     *
     * Not surfaced as an error. The agent did not ask for this check; failing it
     * quietly costs them nothing, and they have a working app either way.
     */
    data object Unknown : UpdateStatus
}

/**
 * Decides whether [latestTag] is worth telling the agent about.
 *
 * Split from the HTTP call so the comparison — the part with the off-by-one — is
 * testable without a network.
 */
object UpdateCheck {

    fun evaluate(
        installedVersionName: String,
        latestTag: String?,
        releaseUrl: String?,
        notes: String? = null,
    ): UpdateStatus {
        val installed = AppVersion.parse(installedVersionName) ?: return UpdateStatus.Unknown
        val latest = AppVersion.parse(latestTag) ?: return UpdateStatus.Unknown
        if (releaseUrl.isNullOrBlank()) return UpdateStatus.Unknown

        // Strictly greater. Equal is up to date, and *older* is too: a
        // deleted-and-recreated release, or a sideloaded build newer than the
        // published one, must not prompt a downgrade — installing it would fail
        // on the version code anyway, and the prompt would never go away.
        return if (latest > installed) {
            UpdateStatus.Available(version = latest, url = releaseUrl, notes = notes)
        } else {
            UpdateStatus.UpToDate
        }
    }
}
