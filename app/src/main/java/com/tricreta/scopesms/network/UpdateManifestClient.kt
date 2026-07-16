package com.tricreta.scopesms.network

import android.util.Log
import androidx.annotation.Keep
import com.squareup.moshi.Json
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * `update.json` exactly as it sits on GitHub. Every field nullable — a truncated
 * or garbled manifest must parse to a typed "unreadable" object, never crash
 * Moshi. `@Keep` (kept by the existing `network.**` proguard rule) because Moshi
 * reads these fields reflectively; without it R8 would rename them in release and
 * every field would read null.
 */
@Keep
data class RemoteUpdateManifest(
    @Json(name = "versionCode") val versionCode: Long?,
    @Json(name = "versionName") val versionName: String?,
    @Json(name = "apkUrl") val apkUrl: String?,
    @Json(name = "sha256") val sha256: String?,
    @Json(name = "releaseNotes") val releaseNotes: String?,
    @Json(name = "required") val required: Boolean?,
    @Json(name = "minimumSupportedVersionCode") val minimumSupportedVersionCode: Long?,
)

/**
 * Fetches the update manifest from a stable raw URL.
 *
 * Never throws. Offline, an HTTP error, or unparseable JSON all return null — the
 * caller treats that as "couldn't tell", the same quiet-failure discipline the
 * gateway client follows. `Cache-Control: no-cache` so a freshly published
 * release isn't hidden behind a stale CDN copy on the raw endpoint.
 */
class UpdateManifestClient(
    private val client: OkHttpClient,
    private val url: String,
) {
    private val adapter = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()
        .adapter(RemoteUpdateManifest::class.java)

    suspend fun fetch(): RemoteUpdateManifest? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(url)
                .header("Cache-Control", "no-cache")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.d(TAG, "Update manifest HTTP ${response.code}")
                    return@withContext null
                }
                val bodyText = response.body?.string() ?: return@withContext null
                adapter.fromJson(bodyText)
            }
        } catch (e: Exception) {
            // Broad on purpose: an IOException offline is expected, a Moshi error
            // on a changed shape is not, and neither is worth crashing Settings.
            Log.d(TAG, "Update manifest fetch failed: ${e.javaClass.simpleName}")
            null
        }
    }

    private companion object {
        const val TAG = "ScopeSms/Update"
    }
}
