package com.tricreta.scopesms.ui.update

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tricreta.scopesms.R
import com.tricreta.scopesms.update.UpdateError
import com.tricreta.scopesms.update.UpdateFlowState
import java.io.File

/**
 * The in-app updater UI. Reads update.json, downloads the APK with a progress
 * bar, verifies it (SHA-256 + package + signing cert), then hands it to the
 * system installer — which always shows its own confirmation. No silent install.
 */
@Composable
fun UpdateSection(
    modifier: Modifier = Modifier,
    viewModel: UpdateViewModel = viewModel(factory = UpdateViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // If the "install unknown apps" grant is missing we route to Settings, then
    // resume the install when the user comes back — the result code from that
    // screen is unreliable, so we re-query the grant instead of trusting it.
    val pendingInstall = remember { mutableStateOf<File?>(null) }
    val unknownSourcesLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        val file = pendingInstall.value
        pendingInstall.value = null
        if (file != null && viewModel.canInstall()) {
            context.startActivity(viewModel.installIntent(file))
        } else if (file != null) {
            viewModel.installBlocked()
        }
    }

    fun startInstall(file: File) {
        if (viewModel.canInstall()) {
            context.startActivity(viewModel.installIntent(file))
        } else {
            pendingInstall.value = file
            unknownSourcesLauncher.launch(viewModel.unknownSourcesIntent())
        }
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val busy = state is UpdateFlowState.Checking || state is UpdateFlowState.Downloading ||
            state is UpdateFlowState.Verifying
        OutlinedButton(onClick = viewModel::check, enabled = !busy) {
            Text(stringResource(R.string.settings_update_check))
        }

        when (val s = state) {
            UpdateFlowState.Idle -> Unit

            UpdateFlowState.Checking -> ProgressRow(stringResource(R.string.settings_update_checking))

            UpdateFlowState.UpToDate -> Text(
                text = stringResource(R.string.settings_update_current),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            is UpdateFlowState.Available -> AvailableCard(
                versionName = s.target.versionName,
                notes = s.target.releaseNotes,
                onDownload = { viewModel.download(s.target, s.forced) },
            )

            is UpdateFlowState.Downloading -> DownloadingCard(
                percent = s.percent,
                forced = s.forced,
                onCancel = viewModel::cancel,
            )

            UpdateFlowState.Verifying -> ProgressRow(stringResource(R.string.settings_update_verifying))

            is UpdateFlowState.ReadyToInstall -> ReadyCard(onInstall = { startInstall(s.apkFile) })

            is UpdateFlowState.Error -> ErrorCard(reason = s.reason, onRetry = viewModel::check)
        }
    }
}

@Composable
private fun ProgressRow(label: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.padding(4.dp))
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun AvailableCard(versionName: String, notes: String?, onDownload: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(R.string.settings_update_available, versionName),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
            )
            notes?.takeIf { it.isNotBlank() }?.let {
                Text(text = it.trim(), style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = onDownload) {
                Text(stringResource(R.string.settings_update_download))
            }
        }
    }
}

@Composable
private fun DownloadingCard(percent: Int?, forced: Boolean, onCancel: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = if (percent != null) {
                    stringResource(R.string.settings_update_downloading, percent)
                } else {
                    stringResource(R.string.settings_update_downloading_wait)
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            if (percent != null) {
                LinearProgressIndicator(
                    progress = { percent / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            if (!forced) {
                TextButton(onClick = onCancel) {
                    Text(stringResource(R.string.settings_update_cancel))
                }
            }
        }
    }
}

@Composable
private fun ReadyCard(onInstall: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(R.string.settings_update_ready),
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = onInstall) {
                Text(stringResource(R.string.settings_update_install))
            }
        }
    }
}

@Composable
private fun ErrorCard(reason: UpdateError, onRetry: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(text = updateErrorMessage(reason), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onRetry) {
                Text(stringResource(R.string.settings_update_retry))
            }
        }
    }
}

@Composable
private fun updateErrorMessage(error: UpdateError): String = when (error) {
    UpdateError.NoNetwork -> stringResource(R.string.settings_update_err_network)
    UpdateError.NotConfigured -> stringResource(R.string.settings_update_err_not_configured)
    UpdateError.ManifestUnreadable -> stringResource(R.string.settings_update_err_manifest)
    UpdateError.InsufficientStorage -> stringResource(R.string.settings_update_err_storage)
    is UpdateError.DownloadFailed -> stringResource(R.string.settings_update_err_download)
    UpdateError.HashMismatch -> stringResource(R.string.settings_update_err_hash)
    UpdateError.WrongPackage -> stringResource(R.string.settings_update_err_package)
    UpdateError.SignatureMismatch -> stringResource(R.string.settings_update_err_signature)
    UpdateError.InstallBlocked -> stringResource(R.string.settings_update_err_install)
    is UpdateError.Unexpected -> stringResource(R.string.settings_update_err_unexpected)
}
