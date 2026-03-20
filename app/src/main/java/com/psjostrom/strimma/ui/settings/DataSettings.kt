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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun DataSettings(
    bgBroadcastEnabled: Boolean,
    onBgBroadcastEnabledChange: (Boolean) -> Unit,
    webServerEnabled: Boolean,
    webServerSecret: String,
    onWebServerEnabledChange: (Boolean) -> Unit,
    onWebServerSecretChange: (String) -> Unit,
    onStats: () -> Unit,
    onExportSettings: () -> Unit,
    onImportSettings: () -> Unit,
    onPullFromNightscout: (Int) -> Unit,
    onBack: () -> Unit
) {
    val onBg = MaterialTheme.colorScheme.onBackground
    val outline = MaterialTheme.colorScheme.outline
    val outlineVar = MaterialTheme.colorScheme.outlineVariant


    SettingsScaffold(title = "Data", onBack = onBack) {
        SettingsSection("Views") {
            OutlinedButton(
                onClick = onStats,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Statistics")
            }
        }

        SettingsSection("Nightscout") {
            Text("Pull from Nightscout", color = onBg, fontSize = 14.sp)
            Text(
                "Download glucose history from your Nightscout server",
                color = outline,
                fontSize = 12.sp
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(7 to "7 days", 14 to "14 days", 30 to "30 days").forEach { (days, label) ->
                    OutlinedButton(
                        onClick = { onPullFromNightscout(days) },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                    ) {
                        Text(label, fontSize = 13.sp, maxLines = 1)
                    }
                }
            }
        }

        SettingsSection("Integration") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("BG Broadcast", color = onBg, fontSize = 14.sp)
                    Text(
                        "xDrip-compatible intent for AAPS, GDH, watches",
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
                    Text("Local Web Server", color = onBg, fontSize = 14.sp)
                    Text(
                        "Serve BG data on port 17580 for watchfaces and apps",
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
                    label = { Text("API Secret") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        SettingsSection("Backup") {
            OutlinedButton(
                onClick = onExportSettings,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Export Settings")
            }
            OutlinedButton(
                onClick = onImportSettings,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Import Settings")
            }
        }
    }
}
