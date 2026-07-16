package com.scopesms.autoreply.network

import android.util.Log
import androidx.annotation.Keep
import com.scopesms.autoreply.domain.update.UpdateCheck
import com.scopesms.autoreply.domain.update.UpdateStatus
import com.squareup.moshi.Json
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.GET

/**
 * Asks GitHub whether there's a newer APK. Phase 11.
 *
 * BUILD-PLAN Phase 11: *"query the GitHub Releases API for the latest tag,
 * compare to current version, prompt with a download link if newer — no
 * auto-install."*
 *
 * ## Never throws, never blocks anything that matters
 * This is the least important network call in the app, and it must behave like
 * it. It runs on demand from Settings, never on the SMS path. Every failure —
 * offline, rate-limited, no releases published, a tag nobody can parse — is
 * [UpdateStatus.Unknown], which the UI shows as nothing at all. The agent did
 * not ask for this check; failing it quietly costs them nothing.
 *
 * ## Unauthenticated, on purpose
 * The repo is the client's and the endpoint is public. GitHub allows 60
 * unauthenticated requests/hour per IP, which is ~59 more than this needs. A
 * token would be another secret to store, rotate and leak, for nothing.
 */
class UpdateChecker internal constructor(
    private val api: GitHubReleasesApi,
    private val installedVersionName: String,
) {

    suspend fun check(): UpdateStatus = withContext(Dispatchers.IO) {
        try {
            val response = api.latestRelease()
            if (!response.isSuccessful) {
                // 404 is the normal state before the first release is published.
                Log.d(TAG, "Update check got HTTP ${response.code()}.")
                return@withContext UpdateStatus.Unknown
            }

            val release = response.body() ?: return@withContext UpdateStatus.Unknown
            UpdateCheck.evaluate(
                installedVersionName = installedVersionName,
                latestTag = release.tagName,
                releaseUrl = release.htmlUrl,
                notes = release.body,
            )
        } catch (e: Exception) {
            // Deliberately broad. An IOException offline is expected; a Moshi
            // error on a changed API shape is not, and neither is worth crashing
            // Settings over.
            Log.d(TAG, "Update check failed: ${e.javaClass.simpleName}")
            UpdateStatus.Unknown
        }
    }

    companion object {
        private const val TAG = "ScopeSms/Update"
        private const val TIMEOUT_SECONDS = 15L

        /** Public API, so no token. The repo is fixed at build time. */
        const val BASE_URL = "https://api.github.com/repos/wazimuautomate/Scope-sms/"

        fun create(
            installedVersionName: String,
            baseUrl: String = BASE_URL,
            client: OkHttpClient = defaultClient(),
        ): UpdateChecker {
            val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
            val retrofit = Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(client)
                .addConverterFactory(MoshiConverterFactory.create(moshi))
                .build()
            return UpdateChecker(retrofit.create(GitHubReleasesApi::class.java), installedVersionName)
        }

        /**
         * Short timeouts, unlike the gateway's 60s. Nothing waits on this and
         * the agent is looking at the screen; a check that hangs for a minute on
         * a bad connection is worse than one that gives up.
         */
        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }
}

internal interface GitHubReleasesApi {

    /** Excludes drafts and pre-releases, which is what we want. */
    @GET("releases/latest")
    suspend fun latestRelease(): retrofit2.Response<GitHubRelease>
}

/**
 * `@Keep` for the same reason the gateway models carry it: Moshi reads these
 * reflectively, so R8 would otherwise rename the fields and every update check
 * would silently return Unknown in release builds only.
 */
@Keep
internal data class GitHubRelease(
    @Json(name = "tag_name") val tagName: String?,
    @Json(name = "html_url") val htmlUrl: String?,
    /** The release notes. Shown to the agent so they know what changed. */
    val body: String?,
)
