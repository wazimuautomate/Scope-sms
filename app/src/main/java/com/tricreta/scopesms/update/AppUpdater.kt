package com.tricreta.scopesms.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.content.pm.SigningInfo
import android.net.Uri
import android.os.StatFs
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.tricreta.scopesms.domain.update.Sha256
import com.tricreta.scopesms.domain.update.SignatureMatch
import com.tricreta.scopesms.domain.update.SignatureVerdict
import com.tricreta.scopesms.domain.update.UpdateResolution
import com.tricreta.scopesms.domain.update.UpdateResolver
import com.tricreta.scopesms.domain.update.UpdateTarget
import com.tricreta.scopesms.domain.update.toHex
import com.tricreta.scopesms.network.UpdateManifestClient
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request

/** A step in the streaming APK download. */
sealed interface DownloadStep {
    /** [percent] null = indeterminate (no Content-Length). */
    data class Progress(val percent: Int?) : DownloadStep
    data class Done(val file: File, val sha256Hex: String) : DownloadStep
    data class Failed(val error: UpdateError) : DownloadStep
}

/** The outcome of verifying a downloaded APK before it's offered to the installer. */
sealed interface VerifyResult {
    data object Ok : VerifyResult
    data object HashMismatch : VerifyResult
    data object WrongPackage : VerifyResult
    data object SignatureMismatch : VerifyResult
}

/**
 * All the Android machinery of the in-app update: fetch `update.json`, stream and
 * verify the APK, and **build** (never launch) the install intents.
 *
 * Holds app context only — DI-safe, never an Activity. Launching an intent needs
 * an Activity, which the composable has; keeping launch out of here is exactly
 * what makes this class leak-free and lets the pure decisions ([UpdateResolver],
 * [Sha256], [SignatureMatch]) stay JVM-tested. Nothing here throws — every
 * failure is a typed result, like the gateway client.
 */
class AppUpdater internal constructor(
    private val appContext: Context,
    private val manifestClient: UpdateManifestClient,
    private val downloadClient: OkHttpClient,
    private val installedPackage: String,
    private val installedVersionCode: Long,
    /** Whether a read token was baked in — false in builds without the secret. */
    private val configured: Boolean,
) {

    /**
     * True when this build carries the read-only GitHub token the updater needs
     * to reach the private release repo. False → the UI offers manual updates
     * instead of failing with a network-looking error the agent can't act on.
     */
    fun isConfigured(): Boolean = configured

    /** Fetch + resolve. [UpdateResolution.Unknown] on any manifest problem. */
    suspend fun check(): UpdateResolution {
        val manifest = manifestClient.fetch() ?: return UpdateResolution.Unknown
        return UpdateResolver.resolve(
            installedVersionCode = installedVersionCode,
            manifestVersionCode = manifest.versionCode,
            manifestVersionName = manifest.versionName,
            apkUrl = manifest.apkUrl,
            sha256 = manifest.sha256,
            releaseNotes = manifest.releaseNotes,
            required = manifest.required,
            minimumSupportedVersionCode = manifest.minimumSupportedVersionCode,
        )
    }

    /**
     * Streams the APK to app-private storage, hashing as it goes so the SHA-256
     * is over the exact bytes with no second read. Cold and cancellable: cancel
     * the collecting coroutine and the partial file is deleted. Never throws.
     */
    fun download(target: UpdateTarget): Flow<DownloadStep> = flow {
        val dir = File(appContext.cacheDir, DOWNLOAD_DIR).apply {
            // Purge at the START of a download, never after handing the URI to the
            // installer — the system reads the file asynchronously and deleting
            // then would race it.
            deleteRecursively()
            mkdirs()
        }
        val out = File(dir, "scope-sms-${target.versionCode}.apk")

        val response = try {
            downloadClient.newCall(
                Request.Builder()
                    .url(target.apkUrl)
                    // The private-repo release asset is fetched through its
                    // api.github.com asset URL: octet-stream makes GitHub 302 to
                    // the binary instead of returning the asset's JSON metadata.
                    // The Bearer token rides only the api.github.com hop (network
                    // interceptor) and is dropped before the redirect to storage.
                    .header("Accept", "application/octet-stream")
                    .build(),
            ).execute()
        } catch (e: IOException) {
            emit(DownloadStep.Failed(UpdateError.NoNetwork))
            return@flow
        }

        response.use { resp ->
            if (!resp.isSuccessful) {
                emit(DownloadStep.Failed(UpdateError.DownloadFailed(resp.code)))
                return@flow
            }
            val body = resp.body ?: run {
                emit(DownloadStep.Failed(UpdateError.DownloadFailed(null)))
                return@flow
            }
            val total = body.contentLength()
            if (total > 0 && !hasSpaceFor(total)) {
                emit(DownloadStep.Failed(UpdateError.InsufficientStorage))
                return@flow
            }

            val digest = MessageDigest.getInstance("SHA-256")
            try {
                body.byteStream().use { input ->
                    out.outputStream().use { sink ->
                        val buf = ByteArray(8 * 1024)
                        var read = 0L
                        var n: Int
                        while (input.read(buf).also { n = it } >= 0) {
                            currentCoroutineContext().ensureActive()
                            sink.write(buf, 0, n)
                            digest.update(buf, 0, n)
                            read += n
                            emit(DownloadStep.Progress(if (total > 0) ((read * 100) / total).toInt() else null))
                        }
                    }
                }
            } catch (e: CancellationException) {
                out.delete()
                throw e
            } catch (e: IOException) {
                out.delete()
                emit(DownloadStep.Failed(if (isNoSpace(e)) UpdateError.InsufficientStorage else UpdateError.DownloadFailed(null)))
                return@flow
            }
            emit(DownloadStep.Done(out, digest.digest().toHex()))
        }
    }.flowOn(Dispatchers.IO)

    /** Hash (hard) → package name (hard) → signature (hard on readable mismatch). */
    fun verify(file: File, computedSha256: String, target: UpdateTarget): VerifyResult {
        if (!Sha256.matches(target.sha256, computedSha256)) return VerifyResult.HashMismatch

        val archiveInfo = appContext.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
        if (archiveInfo?.packageName != installedPackage) return VerifyResult.WrongPackage

        return when (signatureVerdict(file)) {
            SignatureVerdict.MISMATCH -> VerifyResult.SignatureMismatch
            // MATCH and CANT_VERIFY both proceed — the installer enforces
            // signatures itself, so an unreadable archive is not a hard stop.
            SignatureVerdict.MATCH, SignatureVerdict.CANT_VERIFY -> VerifyResult.Ok
        }
    }

    private fun signatureVerdict(file: File): SignatureVerdict {
        val pm = appContext.packageManager
        return try {
            val archive = pm.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
                ?: return SignatureVerdict.CANT_VERIFY
            // getPackageArchiveInfo does NOT populate sourceDir/publicSourceDir,
            // and signature reads need them pointed at the archive or signingInfo
            // comes back null. This is the load-bearing gotcha of this whole path.
            archive.applicationInfo?.let {
                it.sourceDir = file.absolutePath
                it.publicSourceDir = file.absolutePath
            }
            val archiveCerts = archive.signingInfo?.let { certHashes(it) }

            val installedCerts = pm.getPackageInfo(installedPackage, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo?.let { certHashes(it) } ?: emptySet()

            SignatureMatch.verdict(installed = installedCerts, archive = archiveCerts)
        } catch (e: Exception) {
            Log.d(TAG, "Signature verify unavailable: ${e.javaClass.simpleName}")
            SignatureVerdict.CANT_VERIFY
        }
    }

    private fun certHashes(info: SigningInfo): Set<String> {
        val signatures: Array<Signature> = if (info.hasMultipleSigners()) {
            info.apkContentsSigners
        } else {
            info.signingCertificateHistory
        } ?: return emptySet()
        val digest = MessageDigest.getInstance("SHA-256")
        return signatures.mapNotNull { sig -> runCatching { digest.digest(sig.toByteArray()).toHex() }.getOrNull() }
            .toSet()
    }

    /** The per-source "install unknown apps" grant. Always present at minSdk 30. */
    fun canInstall(): Boolean = appContext.packageManager.canRequestPackageInstalls()

    fun unknownSourcesIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$installedPackage"))

    /** Hands the verified APK to the system installer via a content:// URI. */
    fun installIntent(file: File): Intent {
        val uri = FileProvider.getUriForFile(appContext, "$installedPackage.fileprovider", file)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private fun hasSpaceFor(bytes: Long): Boolean = try {
        StatFs(appContext.cacheDir.path).availableBytes > bytes * 12 / 10
    } catch (e: Exception) {
        true // if we can't tell, don't block the download
    }

    companion object {
        private const val TAG = "ScopeSms/Update"
        private const val DOWNLOAD_DIR = "updates"
        private const val GITHUB_API_HOST = "api.github.com"

        fun create(
            context: Context,
            manifestUrl: String,
            readToken: String,
            installedPackage: String,
            installedVersionCode: Long,
        ): AppUpdater {
            val appContext = context.applicationContext

            // Attaches the read-only GitHub token, but ONLY on the api.github.com
            // hop. As a *network* interceptor it re-runs for every redirect, and
            // GitHub 302s an asset download to a pre-signed storage host — sending
            // Authorization there both leaks the token and is rejected ("only one
            // auth mechanism allowed"), so it must be scoped by host rather than
            // set once on the request. An empty token (a build without the secret)
            // adds nothing: the fetch 404s and the updater reports "not
            // configured" instead of pretending to work.
            val githubAuth = Interceptor { chain ->
                val request = chain.request()
                val authed = if (readToken.isNotEmpty() && request.url.host == GITHUB_API_HOST) {
                    request.newBuilder().header("Authorization", "Bearer $readToken").build()
                } else {
                    request
                }
                chain.proceed(authed)
            }

            // Manifest: short timeouts — nobody waits long on a small JSON.
            val manifestHttp = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .addNetworkInterceptor(githubAuth)
                .build()
            // APK: long per-chunk read timeout, NO call timeout — a multi-MB
            // download on rural 2G must not be killed wholesale. Redirects on for
            // the api.github.com asset → storage-host hop; the interceptor drops
            // the token on that hop.
            val downloadHttp = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .addNetworkInterceptor(githubAuth)
                .build()
            return AppUpdater(
                appContext = appContext,
                manifestClient = UpdateManifestClient(manifestHttp, manifestUrl),
                downloadClient = downloadHttp,
                installedPackage = installedPackage,
                installedVersionCode = installedVersionCode,
                configured = readToken.isNotBlank(),
            )
        }
    }
}

private fun isNoSpace(e: IOException): Boolean =
    e.message?.contains("ENOSPC", ignoreCase = true) == true ||
        e.message?.contains("No space", ignoreCase = true) == true
