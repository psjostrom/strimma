package com.psjostrom.strimma.ui.settings

import android.text.format.DateUtils
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.psjostrom.strimma.R
import com.psjostrom.strimma.ui.theme.InRange

@Composable
fun DataSettings(
    bgBroadcastEnabled: Boolean,
    onBgBroadcastEnabledChange: (Boolean) -> Unit,
    webServerEnabled: Boolean,
    webServerSecret: String,
    onWebServerEnabledChange: (Boolean) -> Unit,
    onWebServerSecretChange: (String) -> Unit,
    tidepoolEnabled: Boolean,
    onTidepoolEnabledChange: (Boolean) -> Unit,
    isTidepoolLoggedIn: Boolean,
    onTidepoolLogin: () -> Unit,
    onTidepoolLogout: () -> Unit,
    tidepoolOnlyWhileCharging: Boolean,
    onTidepoolOnlyWhileChargingChange: (Boolean) -> Unit,
    tidepoolOnlyWhileWifi: Boolean,
    onTidepoolOnlyWhileWifiChange: (Boolean) -> Unit,
    tidepoolLastUploadTime: Long,
    tidepoolLastError: String,
    onExportSettings: () -> Unit,
    onExportReadings: () -> Unit,
    onImportSettings: () -> Unit,
    onBack: () -> Unit
) {
    val onBg = MaterialTheme.colorScheme.onBackground
    val outline = MaterialTheme.colorScheme.outline
    val outlineVar = MaterialTheme.colorScheme.outlineVariant
    val errorColor = MaterialTheme.colorScheme.error
    var showConsentDialog by remember { mutableStateOf(false) }

    if (showConsentDialog) {
        TidepoolConsentDialog(
            onAccept = {
                showConsentDialog = false
                onTidepoolLogin()
            },
            onDismiss = { showConsentDialog = false }
        )
    }

    SettingsScaffold(title = stringResource(R.string.settings_data_title), onBack = onBack) {
        SettingsSection(stringResource(R.string.settings_data_integration)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(stringResource(R.string.settings_data_bg_broadcast), color = onBg, fontSize = 14.sp)
                    Text(
                        stringResource(R.string.settings_data_bg_broadcast_desc),
                        color = outline,
                        fontSize = 12.sp
                    )
                }
                Switch(checked = bgBroadcastEnabled, onCheckedChange = onBgBroadcastEnabledChange)
            }
            HorizontalDivider(color = outlineVar)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(stringResource(R.string.settings_data_web_server), color = onBg, fontSize = 14.sp)
                    Text(
                        stringResource(R.string.settings_data_web_server_desc),
                        color = outline,
                        fontSize = 12.sp
                    )
                }
                Switch(checked = webServerEnabled, onCheckedChange = onWebServerEnabledChange)
            }
            if (webServerEnabled) {
                var secretText by remember { mutableStateOf(webServerSecret) }
                LaunchedEffect(webServerSecret) {
                    if (webServerSecret != secretText) secretText = webServerSecret
                }
                OutlinedTextField(
                    value = secretText,
                    onValueChange = {
                        secretText = it
                        onWebServerSecretChange(it)
                    },
                    label = { Text(stringResource(R.string.settings_data_api_secret)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        SettingsSection("Tidepool") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("Upload to Tidepool", color = onBg, fontSize = 14.sp)
                    Text("Sync CGM data to your Tidepool account", color = outline, fontSize = 12.sp)
                }
                Switch(checked = tidepoolEnabled, onCheckedChange = onTidepoolEnabledChange)
            }

            if (tidepoolEnabled) {
                HorizontalDivider(color = outlineVar)
                if (isTidepoolLoggedIn) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Connected", color = InRange, fontSize = 13.sp)
                        TextButton(onClick = onTidepoolLogout) {
                            Text("Disconnect")
                        }
                    }
                } else {
                    OutlinedButton(
                        onClick = { showConsentDialog = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Login with Tidepool")
                    }
                }

                HorizontalDivider(color = outlineVar)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Only while charging", color = onBg, fontSize = 14.sp)
                    Switch(
                        checked = tidepoolOnlyWhileCharging,
                        onCheckedChange = onTidepoolOnlyWhileChargingChange
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Only on Wi-Fi", color = onBg, fontSize = 14.sp)
                    Switch(
                        checked = tidepoolOnlyWhileWifi,
                        onCheckedChange = onTidepoolOnlyWhileWifiChange
                    )
                }

                if (tidepoolLastError.isNotBlank()) {
                    Text(tidepoolLastError, color = errorColor, fontSize = 12.sp)
                } else if (tidepoolLastUploadTime > 0) {
                    val relative = DateUtils.getRelativeTimeSpanString(
                        tidepoolLastUploadTime,
                        System.currentTimeMillis(),
                        DateUtils.MINUTE_IN_MILLIS
                    )
                    Text("Last upload: $relative", color = outline, fontSize = 12.sp)
                }
            }
        }

        SettingsSection(stringResource(R.string.settings_data_backup)) {
            OutlinedButton(
                onClick = onExportReadings,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.settings_data_export_readings))
            }
            OutlinedButton(
                onClick = onExportSettings,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.settings_data_export_settings))
            }
            OutlinedButton(
                onClick = onImportSettings,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.settings_data_import_settings))
            }
        }
    }
}

@Composable
private fun TidepoolConsentDialog(
    onAccept: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Connect to Tidepool") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Strimma is requesting access to your Tidepool account. " +
                        "Strimma wants to use this access to upload data to your Tidepool account.\n\n" +
                        "Any data uploaded to Tidepool is subject to Tidepool\u2019s Privacy Policy " +
                        "and Terms of Use.\n\n" +
                        "You may revoke access at any time by disconnecting your Tidepool account " +
                        "in Strimma Settings \u2192 Sharing \u2192 Disconnect.",
                    fontSize = 14.sp
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onAccept) {
                Text("Accept")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
