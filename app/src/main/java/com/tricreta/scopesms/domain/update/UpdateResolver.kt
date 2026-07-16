package com.tricreta.scopesms.domain.update

/**
 * What `update.json` describes, once validated enough to act on. Every field is
 * present and safe: [sha256] is a real 64-hex digest, [apkUrl] is non-blank.
 */
data class UpdateTarget(
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    /** Lowercase 64-char hex. Guaranteed valid by [UpdateResolver]. */
    val sha256: String,
    val releaseNotes: String?,
)

/**
 * The verdict of comparing the installed build against the update manifest.
 *
 * [Unknown] is distinct from [UpToDate] on purpose: a missing or malformed
 * manifest must never read as "you have the latest version", which would be a
 * guess dressed up as a fact.
 */
sealed interface UpdateResolution {
    data object UpToDate : UpdateResolution
    data class Available(val target: UpdateTarget, val forced: Boolean) : UpdateResolution
    data object Unknown : UpdateResolution
}

/**
 * Decides whether the manifest offers a newer build — the part of the update
 * feature with all the off-by-one risk, kept pure so it is JVM-tested with no
 * network or Android.
 *
 * The comparison is on **versionCode**, the monotonic integer the platform
 * installer itself uses to decide an install is an update. versionName never
 * drives the newer/older decision, so "1.10 vs 1.9" — the classic string-compare
 * trap — cannot happen here; versionName is carried only for display.
 */
object UpdateResolver {

    fun resolve(
        installedVersionCode: Long,
        manifestVersionCode: Long?,
        manifestVersionName: String?,
        apkUrl: String?,
        sha256: String?,
        releaseNotes: String?,
        required: Boolean?,
        minimumSupportedVersionCode: Long?,
    ): UpdateResolution {
        val versionCode = manifestVersionCode ?: return UpdateResolution.Unknown
        val versionName = manifestVersionName?.trim().orEmpty()
        val url = apkUrl?.trim().orEmpty()
        val sha = sha256?.trim()?.lowercase().orEmpty()

        // Nothing installable → Unknown, not UpToDate. A blank URL or a bad hash
        // means we cannot safely offer or verify anything.
        if (versionName.isEmpty() || url.isEmpty() || !Sha256.isValidHex(sha)) {
            return UpdateResolution.Unknown
        }

        // Strictly greater. Equal is up to date, and older is too — a rolled-back
        // or re-cut manifest must never prompt a downgrade the installer would
        // reject anyway, leaving the prompt stuck forever.
        if (versionCode <= installedVersionCode) return UpdateResolution.UpToDate

        // Forced when the publisher flags it, or when this build predates the
        // minimum still supported (e.g. a broken older version that must move on).
        val forced = required == true ||
            (minimumSupportedVersionCode != null && installedVersionCode < minimumSupportedVersionCode)

        return UpdateResolution.Available(
            target = UpdateTarget(
                versionCode = versionCode,
                versionName = versionName,
                apkUrl = url,
                sha256 = sha,
                releaseNotes = releaseNotes?.trim()?.takeIf { it.isNotEmpty() },
            ),
            forced = forced,
        )
    }
}
