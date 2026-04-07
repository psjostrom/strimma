package com.psjostrom.strimma.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.psjostrom.strimma.R
import com.psjostrom.strimma.update.DownloadState
import com.psjostrom.strimma.update.UpdateInfo

@Composable
fun UpdateDialog(
    info: UpdateInfo,
    downloadState: DownloadState,
    onUpdate: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!info.isForced) onDismiss() },
        title = {
            Text(
                stringResource(if (info.isForced) R.string.update_required_title else R.string.update_available_title),
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(R.string.update_version, info.version),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                if (info.changelog.isNotBlank()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        info.changelog,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (info.isForced) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.update_forced_message),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (downloadState == DownloadState.DOWNLOADING) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(end = 8.dp),
                        strokeWidth = 2.dp
                    )
                }
                Button(
                    onClick = onUpdate,
                    enabled = downloadState != DownloadState.DOWNLOADING
                ) {
                    Text(
                        stringResource(
                            when (downloadState) {
                                DownloadState.DOWNLOADING -> R.string.update_downloading
                                DownloadState.FAILED -> R.string.update_retry
                                else -> R.string.update_button
                            }
                        )
                    )
                }
            }
        },
        dismissButton = {
            if (!info.isForced) {
                OutlinedButton(onClick = onDismiss) {
                    Text(stringResource(R.string.update_later))
                }
            }
        }
    )
}
