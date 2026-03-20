package com.psjostrom.strimma.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.psjostrom.strimma.data.GlucoseUnit
import com.psjostrom.strimma.notification.AlertManager
import com.psjostrom.strimma.ui.theme.InRange

@Composable
fun AlertsSettings(
    glucoseUnit: GlucoseUnit,
    alertLowEnabled: Boolean,
    alertHighEnabled: Boolean,
    alertUrgentLowEnabled: Boolean,
    alertUrgentHighEnabled: Boolean,
    alertLow: Float,
    alertHigh: Float,
    alertUrgentLow: Float,
    alertUrgentHigh: Float,
    alertStaleEnabled: Boolean,
    alertLowSoonEnabled: Boolean,
    alertHighSoonEnabled: Boolean,
    onAlertLowEnabledChange: (Boolean) -> Unit,
    onAlertHighEnabledChange: (Boolean) -> Unit,
    onAlertUrgentLowEnabledChange: (Boolean) -> Unit,
    onAlertUrgentHighEnabledChange: (Boolean) -> Unit,
    onAlertLowChange: (Float) -> Unit,
    onAlertHighChange: (Float) -> Unit,
    onAlertUrgentLowChange: (Float) -> Unit,
    onAlertUrgentHighChange: (Float) -> Unit,
    onAlertStaleEnabledChange: (Boolean) -> Unit,
    onAlertLowSoonEnabledChange: (Boolean) -> Unit,
    onAlertHighSoonEnabledChange: (Boolean) -> Unit,
    onOpenAlertSound: (String) -> Unit,
    onBack: () -> Unit
) {
    val onBg = MaterialTheme.colorScheme.onBackground
    val outline = MaterialTheme.colorScheme.outline
    val outlineVar = MaterialTheme.colorScheme.outlineVariant

    SettingsScaffold(title = "Alerts", onBack = onBack) {
        SettingsSection("Alerts") {
            Text(
                "Tap \"Sound\" to choose ringtone, vibration, and override settings per alarm.",
                color = outline,
                fontSize = 12.sp
            )

            AlertBlock(
                label = "Urgent Low",
                enabled = alertUrgentLowEnabled,
                onToggle = onAlertUrgentLowEnabledChange,
                threshold = alertUrgentLow,
                onThresholdChange = onAlertUrgentLowChange,
                thresholdLabel = "Urgent Low (${glucoseUnit.label})",
                glucoseUnit = glucoseUnit,
                channelId = AlertManager.CHANNEL_URGENT_LOW,
                onOpenSound = onOpenAlertSound,
                textColor = onBg
            )

            HorizontalDivider(color = outlineVar)

            AlertBlock(
                label = "Low",
                enabled = alertLowEnabled,
                onToggle = onAlertLowEnabledChange,
                threshold = alertLow,
                onThresholdChange = onAlertLowChange,
                thresholdLabel = "Low Alert (${glucoseUnit.label})",
                glucoseUnit = glucoseUnit,
                channelId = AlertManager.CHANNEL_LOW,
                onOpenSound = onOpenAlertSound,
                textColor = onBg
            )

            HorizontalDivider(color = outlineVar)

            AlertBlock(
                label = "High",
                enabled = alertHighEnabled,
                onToggle = onAlertHighEnabledChange,
                threshold = alertHigh,
                onThresholdChange = onAlertHighChange,
                thresholdLabel = "High Alert (${glucoseUnit.label})",
                glucoseUnit = glucoseUnit,
                channelId = AlertManager.CHANNEL_HIGH,
                onOpenSound = onOpenAlertSound,
                textColor = onBg
            )

            HorizontalDivider(color = outlineVar)

            AlertBlock(
                label = "Urgent High",
                enabled = alertUrgentHighEnabled,
                onToggle = onAlertUrgentHighEnabledChange,
                threshold = alertUrgentHigh,
                onThresholdChange = onAlertUrgentHighChange,
                thresholdLabel = "Urgent High (${glucoseUnit.label})",
                glucoseUnit = glucoseUnit,
                channelId = AlertManager.CHANNEL_URGENT_HIGH,
                onOpenSound = onOpenAlertSound,
                textColor = onBg
            )

            HorizontalDivider(color = outlineVar)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("Low Soon", color = onBg, fontSize = 14.sp)
                    Text("Predicted low within prediction window", color = outline, fontSize = 12.sp)
                }
                Switch(checked = alertLowSoonEnabled, onCheckedChange = onAlertLowSoonEnabledChange)
            }
            if (alertLowSoonEnabled) {
                TextButton(onClick = { onOpenAlertSound(AlertManager.CHANNEL_LOW_SOON) }) {
                    Text("Sound", color = InRange, fontSize = 13.sp)
                }
            }

            HorizontalDivider(color = outlineVar)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("High Soon", color = onBg, fontSize = 14.sp)
                    Text("Predicted high within prediction window", color = outline, fontSize = 12.sp)
                }
                Switch(checked = alertHighSoonEnabled, onCheckedChange = onAlertHighSoonEnabledChange)
            }
            if (alertHighSoonEnabled) {
                TextButton(onClick = { onOpenAlertSound(AlertManager.CHANNEL_HIGH_SOON) }) {
                    Text("Sound", color = InRange, fontSize = 13.sp)
                }
            }

            HorizontalDivider(color = outlineVar)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Stale Data (10+ min)", color = onBg, fontSize = 14.sp)
                Switch(checked = alertStaleEnabled, onCheckedChange = onAlertStaleEnabledChange)
            }
            if (alertStaleEnabled) {
                TextButton(onClick = { onOpenAlertSound(AlertManager.CHANNEL_STALE) }) {
                    Text("Sound", color = InRange, fontSize = 13.sp)
                }
            }
        }
    }
}
