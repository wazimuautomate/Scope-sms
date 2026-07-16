package com.tricreta.scopesms.update

import com.tricreta.scopesms.domain.update.UpdateTarget
import java.io.File

/**
 * The single state the update UI renders. A linear progression:
 * Idle → Checking → (UpToDate | Available → Downloading → Verifying →
 * ReadyToInstall) with [Error] reachable from any step.
 */
sealed interface UpdateFlowState {
    data object Idle : UpdateFlowState
    data object Checking : UpdateFlowState
    data object UpToDate : UpdateFlowState
    data class Available(val target: UpdateTarget, val forced: Boolean) : UpdateFlowState

    /** [percent] is null when the server sent no Content-Length (indeterminate). */
    data class Downloading(val percent: Int?, val forced: Boolean) : UpdateFlowState
    data object Verifying : UpdateFlowState
    data class ReadyToInstall(val apkFile: File, val forced: Boolean) : UpdateFlowState
    data class Error(val reason: UpdateError) : UpdateFlowState
}

/**
 * One distinct, user-explainable failure. The UI maps each to its own string —
 * "it failed" alone tells the agent nothing they can act on, which network's copy
 * rules forbid.
 */
sealed interface UpdateError {
    data object NoNetwork : UpdateError

    /**
     * This build carries no read token, so it cannot reach the private release
     * repo. Not a failure the agent can retry away — the message points them at
     * the manual download instead. Shipped (CI-built) APKs never hit this; it is
     * for local/dev builds and a clear signal if the token was never wired.
     */
    data object NotConfigured : UpdateError
    data object ManifestUnreadable : UpdateError
    data object InsufficientStorage : UpdateError
    data class DownloadFailed(val httpCode: Int?) : UpdateError
    data object HashMismatch : UpdateError
    data object WrongPackage : UpdateError
    data object SignatureMismatch : UpdateError
    data object InstallBlocked : UpdateError
    data class Unexpected(val label: String) : UpdateError
}
