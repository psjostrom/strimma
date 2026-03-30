package com.psjostrom.strimma.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.psjostrom.strimma.R

@Composable
fun DataSettings(
    bgBroadcastEnabled: Boolean,
    onBgBroadcastEnabledChange: (Boolean) -> Unit,
    webServerEnabled: Boolean,
    webServerSecret: String,
    onWebServerEnabledChange: (Boolean) -> Unit,
    onWebServerSecretChange: (String) -> Unit,
    onExportSettings: () -> Unit,
    onExportReadings: () -> Unit,
    onImportSettings: () -> Unit,
    onBack: () -> Unit
) {
    val onBg = MaterialTheme.colorScheme.onBackground
    val outline = MaterialTheme.colorScheme.outline
    val outlineVar = MaterialTheme.colorScheme.outlineVariant

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
