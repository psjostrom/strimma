package com.psjostrom.strimma.ui.settings

import android.text.format.DateUtils
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.psjostrom.strimma.ui.theme.InRange

@Composable
fun TidepoolSettings(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    isLoggedIn: Boolean,
    onLogin: () -> Unit,
    onLogout: () -> Unit,
    onlyWhileCharging: Boolean,
    onOnlyWhileChargingChange: (Boolean) -> Unit,
    onlyWhileWifi: Boolean,
    onOnlyWhileWifiChange: (Boolean) -> Unit,
    lastUploadTime: Long,
    lastError: String,
    onBack: () -> Unit
) {
    val onBg = MaterialTheme.colorScheme.onBackground
    val outline = MaterialTheme.colorScheme.outline
    val error = MaterialTheme.colorScheme.error
    var showConsentDialog by remember { mutableStateOf(false) }

    if (showConsentDialog) {
        TidepoolConsentDialog(
            onAccept = {
                showConsentDialog = false
                onLogin()
            },
            onDismiss = { showConsentDialog = false }
        )
    }

    SettingsScaffold(title = "Tidepool", onBack = onBack) {
        SettingsSection("Account") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Enable Tidepool upload", color = onBg, fontSize = 14.sp)
                Switch(checked = enabled, onCheckedChange = onEnabledChange)
            }

            if (isLoggedIn) {
                OutlinedButton(
                    onClick = onLogout,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Disconnect")
                }
                Text("Connected", color = InRange, fontSize = 12.sp)
            } else {
                OutlinedButton(
                    onClick = { showConsentDialog = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Login with Tidepool")
                }
                Text("Not connected", color = outline, fontSize = 12.sp)
            }
        }

        if (enabled) {
            SettingsSection("Conditions") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Only while charging", color = onBg, fontSize = 14.sp)
                    Switch(
                        checked = onlyWhileCharging,
                        onCheckedChange = onOnlyWhileChargingChange
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Only on Wi-Fi", color = onBg, fontSize = 14.sp)
                    Switch(checked = onlyWhileWifi, onCheckedChange = onOnlyWhileWifiChange)
                }
            }

            SettingsSection("Status") {
                if (lastError.isNotBlank()) {
                    Text(lastError, color = error, fontSize = 13.sp)
                } else if (lastUploadTime > 0) {
                    val relative = DateUtils.getRelativeTimeSpanString(
                        lastUploadTime,
                        System.currentTimeMillis(),
                        DateUtils.MINUTE_IN_MILLIS
                    )
                    Text("Last upload: $relative", color = onBg, fontSize = 13.sp)
                } else {
                    Text("No uploads yet", color = outline, fontSize = 13.sp)
                }
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
                        "in Strimma Settings \u2192 Tidepool \u2192 Disconnect.",
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
